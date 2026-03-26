package com.myclass.maxbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoyKlassHttpClient implements MoyKlassClient {
  private static final Logger log = LoggerFactory.getLogger(MoyKlassHttpClient.class);

  private final BotProperties.Moyklass config;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  private volatile AccessToken accessToken;

  public MoyKlassHttpClient(BotProperties.Moyklass config, UserRepository userRepository) {
    this.config = config;
    this.userRepository = userRepository;
    this.objectMapper = new ObjectMapper();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(Math.max(5, config.getTimeoutSec())))
        .build();
  }

  @Override
  public MoyKlassResult createLead(long maxUserId, String note, SignupData data) {
    if (config.getToken() == null || config.getToken().isBlank()) {
      return MoyKlassResult.failure("Нет API ключа МойКласс.");
    }

    Optional<UserRecord> userOpt = userRepository.findByMaxUserId(maxUserId);
    if (userOpt.isEmpty()) {
      return MoyKlassResult.failure("Не удалось найти пользователя в локальной базе.");
    }

    UserRecord user = userOpt.get();
    long moyklassUserId = 0;
    boolean created = false;
    if (user.getMoyklassUserId() != null) {
      long existingId = user.getMoyklassUserId();
      try {
        getJson("/v1/company/users/" + existingId);
        moyklassUserId = existingId;
      } catch (Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("404")) {
          userRepository.clearMoyklassUserId(maxUserId);
        } else {
          log.warn("Failed to verify existing user in CRM: {}", e.getMessage());
          return MoyKlassResult.failure("Не удалось проверить профиль в МойКласс.");
        }
      }
    }

    String name = data != null && data.getChildName() != null && !data.getChildName().isBlank()
        ? data.getChildName()
        : buildName(user, maxUserId);
    try {
      if (moyklassUserId <= 0) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        if (config.getLeadStateId() != null) {
          payload.put("clientStateId", config.getLeadStateId());
        }
        if (data != null) {
          if (data.getPhone() != null && !data.getPhone().isBlank()) {
            String normalizedPhone = normalizePhone(data.getPhone());
            if (normalizedPhone != null) {
              payload.put("phone", normalizedPhone);
            }
          }
          if (data.getEmail() != null && !data.getEmail().isBlank()) {
            payload.put("email", data.getEmail().trim());
          }
          if (data.getFilialId() != null && data.getFilialId() > 0) {
            payload.put("filials", List.of(data.getFilialId()));
          }
        }
        List<Map<String, Object>> attributes = new ArrayList<>();
        if (config.getMaxIdAttributeAlias() != null && !config.getMaxIdAttributeAlias().isBlank()) {
          attributes.add(Map.of(
              "attributeAlias", config.getMaxIdAttributeAlias().trim(),
              "value", String.valueOf(maxUserId)
          ));
        }
        if (!attributes.isEmpty()) {
          payload.put("attributes", attributes);
        }

        JsonNode response = postJson("/v1/company/users", payload);
        moyklassUserId = response.path("id").asLong(0);
        if (moyklassUserId <= 0) {
          return MoyKlassResult.failure("CRM не вернул ID ученика.");
        }
        userRepository.setMoyklassUserId(maxUserId, moyklassUserId);
        created = true;
      }

      if (data != null) {
        try {
          ensureFilial(moyklassUserId, data.getFilialId());
          ensureJoin(moyklassUserId, data.getClassId());
        } catch (Exception e) {
          log.warn("Failed to assign filial/class: {}", e.getMessage());
          return MoyKlassResult.failure("Не удалось записать в выбранную группу. Попробуйте позже.");
        }
      }

      if (!created) {
        if (data != null && data.getClassId() != null && data.getClassId() > 0) {
          return MoyKlassResult.success("Запись в группу выполнена.", String.valueOf(moyklassUserId));
        }
        return MoyKlassResult.success("Уже записан", String.valueOf(moyklassUserId));
      }
      return MoyKlassResult.success("Ребенок успешно записан", String.valueOf(moyklassUserId));
    } catch (Exception e) {
      log.warn("Failed to create lead: {}", e.getMessage());
      return MoyKlassResult.failure("Ошибка при создании лида в МойКласс.");
    }
  }

  @Override
  public MoyKlassResult getRemainingLessons(long maxUserId) {
    Long moyklassUserId = resolveMoyklassUserId(maxUserId);
    if (moyklassUserId == null) {
      return MoyKlassResult.failure("Не найден профиль в МойКласс. Сначала нажмите \"Записаться\".");
    }

    try {
      String url = "/v1/company/userSubscriptions?userId=" + moyklassUserId + "&limit=200";
      JsonNode response = getJson(url);
      JsonNode subs = response.path("subscriptions");
      if (!subs.isArray() || subs.isEmpty()) {
        return MoyKlassResult.success("Абонементы не найдены", "0");
      }

      int totalRemaining = 0;
      int computed = 0;
      for (JsonNode sub : subs) {
        int visitCount = sub.path("visitCount").asInt(-1);
        double visited = -1;
        if (sub.has("stats")) {
          visited = sub.path("stats").path("totalVisited").asDouble(-1);
        }
        if (visited < 0) {
          visited = sub.path("visitedCount").asDouble(-1);
        }
        if (visitCount >= 0 && visited >= 0) {
          int remaining = (int) Math.max(visitCount - Math.round(visited), 0);
          totalRemaining += remaining;
          computed++;
        }
      }

      if (computed == 0) {
        return MoyKlassResult.failure("Не удалось вычислить остаток занятий.");
      }
      return MoyKlassResult.success("Остаток занятий", String.valueOf(totalRemaining));
    } catch (Exception e) {
      log.warn("Failed to fetch subscriptions: {}", e.getMessage());
      return MoyKlassResult.failure("Ошибка при запросе абонементов.");
    }
  }

  @Override
  public MoyKlassResult linkByPhone(long maxUserId, String phone) {
    try {
      List<UserCandidate> candidates = findUsersByPhone(phone);
      if (candidates == null) {
        return MoyKlassResult.failure("Не удалось распознать номер телефона.");
      }
      if (candidates.isEmpty()) {
        return MoyKlassResult.failure("По этому номеру клиент не найден.");
      }
      if (candidates.size() > 1) {
        return MoyKlassResult.failure("Найдено несколько клиентов по этому номеру. Уточните имя ребенка.");
      }
      long moyklassUserId = candidates.get(0).id();
      return linkToMoyklassUser(maxUserId, moyklassUserId);
    } catch (Exception e) {
      log.warn("Failed to link by phone: {}", e.getMessage());
      return MoyKlassResult.failure("Ошибка при поиске клиента по телефону.");
    }
  }

  @Override
  public List<Filial> listFilials() {
    if (config.getToken() == null || config.getToken().isBlank()) {
      return List.of();
    }
    try {
      JsonNode response = getJson("/v1/company/filials");
      JsonNode items = response.isArray() ? response : response.path("filials");
      return parseFilials(items);
    } catch (Exception e) {
      log.warn("Failed to list filials: {}", e.getMessage());
      return List.of();
    }
  }

  @Override
  public List<ClassGroup> listClasses() {
    if (config.getToken() == null || config.getToken().isBlank()) {
      return List.of();
    }
    try {
      JsonNode response = getJson("/v1/company/classes");
      JsonNode items = response.isArray() ? response : response.path("classes");
      return parseClasses(items);
    } catch (Exception e) {
      log.warn("Failed to list classes: {}", e.getMessage());
      return List.of();
    }
  }

  @Override
  public MoyKlassUser getUserInfo(long moyklassUserId) {
    if (moyklassUserId <= 0) {
      return null;
    }
    try {
      JsonNode response = getJson("/v1/company/users/" + moyklassUserId);
      String name = response.path("name").asText(null);
      String phone = response.path("phone").asText(null);
      return new MoyKlassUser(moyklassUserId, name, phone);
    } catch (Exception e) {
      log.warn("Failed to fetch user info: {}", e.getMessage());
      return null;
    }
  }

  @Override
  public MoyKlassResult getProfileInfo(long maxUserId) {
    Long moyklassUserId = resolveMoyklassUserId(maxUserId);
    if (moyklassUserId == null) {
      return MoyKlassResult.failure("Профиль не найден.");
    }
    try {
      JsonNode response = getJson("/v1/company/users/" + moyklassUserId);
      String phone = response.path("phone").asText("");
      return MoyKlassResult.success("Профиль найден", phone.isBlank() ? null : phone);
    } catch (Exception e) {
      log.warn("Failed to fetch profile info: {}", e.getMessage());
      return MoyKlassResult.failure("Ошибка при получении профиля.");
    }
  }

  @Override
  public MoyKlassResult createInvoice(long maxUserId) {
    Long moyklassUserId = resolveMoyklassUserId(maxUserId);
    if (moyklassUserId == null) {
      return MoyKlassResult.failure("Не найден профиль в МойКласс. Сначала нажмите \"Записаться\".");
    }

    try {
      JsonNode response = getJson("/v1/company/users/" + moyklassUserId + "?includePayLink=true");
      String payLinkKey = response.path("payLinkKey").asText("");
      if (payLinkKey.isBlank()) {
        return MoyKlassResult.failure("CRM не вернул ссылку на оплату.");
      }
      String base = config.getPayLinkBase();
      if (base == null || base.isBlank()) {
        base = "https://pay.tvoyklass.com/key/";
      }
      String link = base.endsWith("/") ? base + payLinkKey : base + "/" + payLinkKey;
      return MoyKlassResult.success("Ссылка на оплату", link);
    } catch (Exception e) {
      log.warn("Failed to get pay link: {}", e.getMessage());
      return MoyKlassResult.failure("Ошибка при получении ссылки на оплату.");
    }
  }

  @Override
  public MoyKlassResult resolveMaxUserIdByPhone(String phone) {
    if (phone == null || phone.isBlank()) {
      return MoyKlassResult.failure("Телефон не указан.");
    }
    String alias = config.getMaxIdAttributeAlias();
    if (alias == null || alias.isBlank()) {
      return MoyKlassResult.failure("Не задан alias для max_user_id.");
    }

    try {
      List<UserCandidate> candidates = findUsersByPhone(phone);
      if (candidates == null) {
        return MoyKlassResult.failure("Не удалось распознать номер телефона.");
      }
      if (candidates.isEmpty()) {
        return MoyKlassResult.failure("Клиент с таким телефоном не найден.");
      }
      if (candidates.size() > 1) {
        return MoyKlassResult.failure("Найдено несколько клиентов по этому номеру.");
      }
      JsonNode userNode = candidates.get(0).node();
      String maxUserIdValue = extractAttributeValue(userNode, alias);
      if (maxUserIdValue == null || maxUserIdValue.isBlank()) {
        return MoyKlassResult.failure("У клиента нет max_user_id. Сначала пройдите запись через бота.");
      }
      try {
        long maxUserId = Long.parseLong(maxUserIdValue.trim());
        return MoyKlassResult.success("MAX user id найден", String.valueOf(maxUserId));
      } catch (NumberFormatException e) {
        return MoyKlassResult.failure("В CRM max_user_id заполнен некорректно.");
      }
    } catch (Exception e) {
      log.warn("Failed to resolve max_user_id by phone: {}", e.getMessage());
      return MoyKlassResult.failure("Ошибка при поиске клиента по телефону.");
    }
  }

  @Override
  public MoyKlassResult resolveMaxUserIdByPhoneAndName(String phone, String childName) {
    if (childName == null || childName.isBlank()) {
      return MoyKlassResult.failure("Имя ребенка не указано.");
    }
    String alias = config.getMaxIdAttributeAlias();
    if (alias == null || alias.isBlank()) {
      return MoyKlassResult.failure("Не задан alias для max_user_id.");
    }
    try {
      List<UserCandidate> candidates = findUsersByPhone(phone);
      if (candidates == null) {
        return MoyKlassResult.failure("Не удалось распознать номер телефона.");
      }
      if (candidates.isEmpty()) {
        return MoyKlassResult.failure("Клиент с таким телефоном не найден.");
      }
      List<UserCandidate> matched = filterByName(candidates, childName);
      if (matched.isEmpty()) {
        return MoyKlassResult.failure("Клиент с таким именем не найден. Уточните ФИО ребенка.");
      }
      if (matched.size() > 1) {
        return MoyKlassResult.failure("Найдено несколько клиентов с таким именем. Уточните ФИО ребенка полностью.");
      }
      JsonNode userNode = matched.get(0).node();
      String maxUserIdValue = extractAttributeValue(userNode, alias);
      if (maxUserIdValue == null || maxUserIdValue.isBlank()) {
        return MoyKlassResult.failure("У клиента нет max_user_id. Сначала пройдите запись через бота.");
      }
      try {
        long maxUserId = Long.parseLong(maxUserIdValue.trim());
        return MoyKlassResult.success("MAX user id найден", String.valueOf(maxUserId));
      } catch (NumberFormatException e) {
        return MoyKlassResult.failure("В CRM max_user_id заполнен некорректно.");
      }
    } catch (Exception e) {
      log.warn("Failed to resolve max_user_id by phone and name: {}", e.getMessage());
      return MoyKlassResult.failure("Ошибка при поиске клиента по телефону.");
    }
  }

  @Override
  public MoyKlassResult linkByPhoneAndName(long maxUserId, String phone, String childName) {
    if (childName == null || childName.isBlank()) {
      return MoyKlassResult.failure("Имя ребенка не указано.");
    }
    try {
      List<UserCandidate> candidates = findUsersByPhone(phone);
      if (candidates == null) {
        return MoyKlassResult.failure("Не удалось распознать номер телефона.");
      }
      if (candidates.isEmpty()) {
        return MoyKlassResult.failure("По этому номеру клиент не найден.");
      }
      List<UserCandidate> matched = filterByName(candidates, childName);
      if (matched.isEmpty()) {
        return MoyKlassResult.failure("Клиент с таким именем не найден. Уточните ФИО ребенка.");
      }
      if (matched.size() > 1) {
        return MoyKlassResult.failure("Найдено несколько клиентов с таким именем. Уточните ФИО ребенка полностью.");
      }
      long moyklassUserId = matched.get(0).id();
      return linkToMoyklassUser(maxUserId, moyklassUserId);
    } catch (Exception e) {
      log.warn("Failed to link by phone and name: {}", e.getMessage());
      return MoyKlassResult.failure("Ошибка при поиске клиента по телефону.");
    }
  }

  private Long resolveMoyklassUserId(long maxUserId) {
    Optional<UserRecord> userOpt = userRepository.findByMaxUserId(maxUserId);
    if (userOpt.isEmpty()) {
      return null;
    }
    UserRecord user = userOpt.get();
    if (user.getMoyklassUserId() != null) {
      return user.getMoyklassUserId();
    }

    String alias = config.getMaxIdAttributeAlias();
    if (alias == null || alias.isBlank()) {
      return null;
    }

    try {
      String paramName = "attributes[" + alias + "]";
      String url = "/v1/company/users?" + encode(paramName) + "=" + encode(String.valueOf(maxUserId)) + "&limit=1";
      JsonNode response = getJson(url);
      JsonNode users = response.path("users");
      if (users.isArray() && users.size() > 0) {
        long moyklassUserId = users.get(0).path("id").asLong(0);
        if (moyklassUserId > 0) {
          userRepository.setMoyklassUserId(maxUserId, moyklassUserId);
          return moyklassUserId;
        }
      }
    } catch (Exception e) {
      log.warn("Failed to resolve user by attribute: {}", e.getMessage());
    }
    return null;
  }

  private String buildName(UserRecord user, long maxUserId) {
    String first = user.getFirstName();
    String last = user.getLastName();
    String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    if (!name.isBlank()) {
      return name;
    }
    if (user.getUsername() != null && !user.getUsername().isBlank()) {
      return user.getUsername();
    }
    return "MAX user " + maxUserId;
  }

  private String extractAttributeValue(JsonNode userNode, String alias) {
    if (userNode == null || alias == null) {
      return null;
    }
    JsonNode attrs = userNode.path("attributes");
    if (!attrs.isArray()) {
      return null;
    }
    for (JsonNode attr : attrs) {
      String attrAlias = attr.path("attributeAlias").asText(null);
      if (attrAlias == null || attrAlias.isBlank()) {
        attrAlias = attr.path("alias").asText(null);
      }
      if (attrAlias != null && matchesAlias(attrAlias, alias)) {
        String value = attr.path("value").asText(null);
        if (value == null || value.isBlank()) {
          value = attr.path("valueText").asText(null);
        }
        return value;
      }
    }
    return null;
  }

  private boolean matchesAlias(String attrAlias, String targetAlias) {
    if (attrAlias == null || targetAlias == null) {
      return false;
    }
    String a = attrAlias.trim();
    String t = targetAlias.trim();
    if (a.equalsIgnoreCase(t)) {
      return true;
    }
    if (t.startsWith("user.") && a.equalsIgnoreCase(t.substring("user.".length()))) {
      return true;
    }
    if (!t.startsWith("user.") && ("user." + t).equalsIgnoreCase(a)) {
      return true;
    }
    return false;
  }

  private List<UserCandidate> findUsersByPhone(String phone) throws IOException, InterruptedException {
    if (phone == null || phone.isBlank()) {
      return null;
    }
    List<String> variants = normalizePhoneVariants(phone);
    if (variants.isEmpty()) {
      return null;
    }

    for (String variant : variants) {
      String url = "/v1/company/users?phone=" + encode(variant) + "&limit=50";
      JsonNode response = getJson(url);
      JsonNode users = response.path("users");
      if (users.isArray() && users.size() > 0) {
        return collectUsers(users);
      }
    }
    return List.of();
  }

  private List<UserCandidate> collectUsers(JsonNode users) {
    if (!users.isArray()) {
      return List.of();
    }
    List<UserCandidate> result = new ArrayList<>();
    for (JsonNode userNode : users) {
      long id = userNode.path("id").asLong(0);
      if (id <= 0) {
        continue;
      }
      String name = userNode.path("name").asText("");
      result.add(new UserCandidate(id, name, userNode));
    }
    return result;
  }

  private List<UserCandidate> filterByName(List<UserCandidate> candidates, String childName) {
    String needle = normalizeName(childName);
    if (needle == null) {
      return List.of();
    }
    List<UserCandidate> result = new ArrayList<>();
    for (UserCandidate candidate : candidates) {
      String name = normalizeName(candidate.name());
      if (name != null && name.contains(needle)) {
        result.add(candidate);
      }
    }
    return result;
  }

  private String normalizeName(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed.toLowerCase();
  }

  private List<String> normalizePhoneVariants(String phone) {
    String digits = phone.replaceAll("\\\\D", "");
    if (digits.length() < 10 || digits.length() > 15) {
      return List.of();
    }
    List<String> variants = new ArrayList<>();
    if (digits.length() == 11 && (digits.startsWith("7") || digits.startsWith("8"))) {
      String national = digits.substring(1);
      variants.add("7" + national);
      variants.add("8" + national);
      variants.add(national);
    } else if (digits.length() == 10) {
      variants.add(digits);
      variants.add("7" + digits);
      variants.add("8" + digits);
    } else {
      variants.add(digits);
    }
    return variants.stream().distinct().toList();
  }

  private MoyKlassResult linkToMoyklassUser(long maxUserId, long moyklassUserId) throws IOException, InterruptedException {
    if (moyklassUserId <= 0) {
      return MoyKlassResult.failure("Не удалось получить ID клиента.");
    }
    if (config.getMaxIdAttributeAlias() != null && !config.getMaxIdAttributeAlias().isBlank()) {
      Map<String, Object> patch = Map.of(
          "attributes", List.of(Map.of(
              "attributeAlias", config.getMaxIdAttributeAlias(),
              "value", String.valueOf(maxUserId)
          ))
      );
      patchJson("/v1/company/users/" + moyklassUserId, patch);
    }
    userRepository.setMoyklassUserId(maxUserId, moyklassUserId);
    return MoyKlassResult.success("Найдены данные клиента", String.valueOf(moyklassUserId));
  }

  private record UserCandidate(long id, String name, JsonNode node) {}

  private List<Filial> parseFilials(JsonNode items) {
    if (items == null || !items.isArray()) {
      return List.of();
    }
    List<Filial> result = new ArrayList<>();
    for (JsonNode node : items) {
      long id = node.path("id").asLong(0);
      if (id <= 0) {
        continue;
      }
      String name = node.path("name").asText("");
      String shortName = node.path("shortName").asText("");
      String status = node.path("status").asText("");
      result.add(new Filial(id, name, shortName, status));
    }
    return result;
  }

  private List<ClassGroup> parseClasses(JsonNode items) {
    if (items == null || !items.isArray()) {
      return List.of();
    }
    List<ClassGroup> result = new ArrayList<>();
    for (JsonNode node : items) {
      long id = node.path("id").asLong(0);
      if (id <= 0) {
        continue;
      }
      String name = node.path("name").asText("");
      String status = node.path("status").asText("");
      long filialId = node.path("filialId").asLong(0);
      result.add(new ClassGroup(id, name, status, filialId));
    }
    return result;
  }

  private void ensureFilial(long moyklassUserId, Long filialId) throws IOException, InterruptedException {
    if (filialId == null || filialId <= 0) {
      return;
    }
    Map<String, Object> payload = Map.of("filials", List.of(filialId));
    patchJson("/v1/company/users/" + moyklassUserId, payload);
  }

  private void ensureJoin(long moyklassUserId, Long classId) throws IOException, InterruptedException {
    if (classId == null || classId <= 0) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("userId", moyklassUserId);
    payload.put("classId", classId);
    postJson("/v1/company/joins", payload);
  }

  private JsonNode getJson(String path) throws IOException, InterruptedException {
    return sendRequest("GET", path, null);
  }

  private JsonNode postJson(String path, Object body) throws IOException, InterruptedException {
    return sendRequest("POST", path, body);
  }

  private JsonNode patchJson(String path, Object body) throws IOException, InterruptedException {
    return sendRequest("PATCH", path, body);
  }

  private JsonNode sendRequest(String method, String path, Object body)
      throws IOException, InterruptedException {
    String token = getAccessToken();
    HttpRequest request = buildRequest(method, path, body, token);
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 401) {
      synchronized (this) {
        accessToken = null;
      }
      String newToken = getAccessToken();
      HttpRequest retry = buildRequest(method, path, body, newToken);
      response = httpClient.send(retry, HttpResponse.BodyHandlers.ofString());
    }
    if (response.statusCode() >= 300) {
      throw new IOException("MoyKlass API error: " + response.statusCode() + " " + response.body());
    }
    return objectMapper.readTree(response.body());
  }

  private HttpRequest buildRequest(String method, String path, Object body, String token) throws IOException {
    String baseUrl = config.getBaseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://api.moyklass.com";
    }
    String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path : baseUrl + path;

    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(Math.max(5, config.getTimeoutSec())))
        .header("x-access-token", token)
        .header("Content-Type", "application/json");

    if ("POST".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
      String json = body == null ? "{}" : objectMapper.writeValueAsString(body);
      if ("PATCH".equalsIgnoreCase(method)) {
        builder.method("PATCH", HttpRequest.BodyPublishers.ofString(json));
      } else {
        builder.POST(HttpRequest.BodyPublishers.ofString(json));
      }
    } else {
      builder.GET();
    }

    return builder.build();
  }

  private synchronized String getAccessToken() throws IOException, InterruptedException {
    if (accessToken != null && !accessToken.isExpired()) {
      return accessToken.value;
    }

    if (config.getToken() == null || config.getToken().isBlank()) {
      throw new IOException("MOYKLASS_TOKEN is empty");
    }

    String url = (config.getBaseUrl() == null || config.getBaseUrl().isBlank())
        ? "https://api.moyklass.com/v1/company/auth/getToken"
        : config.getBaseUrl().replaceAll("/$", "") + "/v1/company/auth/getToken";

    Map<String, Object> body = Map.of("apiKey", config.getToken());
    String json = objectMapper.writeValueAsString(body);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(Math.max(5, config.getTimeoutSec())))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 300) {
      throw new IOException("Auth failed: " + response.statusCode() + " " + response.body());
    }

    JsonNode node = objectMapper.readTree(response.body());
    String token = node.path("accessToken").asText(null);
    String expiresAt = node.path("expiresAt").asText(null);
    if (token == null || token.isBlank()) {
      throw new IOException("Auth response missing accessToken");
    }
    Instant expiry = expiresAt == null ? Instant.now().plusSeconds(3600) : Instant.parse(expiresAt);
    accessToken = new AccessToken(token, expiry);
    return token;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String normalizePhone(String phone) {
    String digits = phone.replaceAll("\\\\D", "");
    if (digits.length() < 10 || digits.length() > 15) {
      return null;
    }
    return digits;
  }

  private static class AccessToken {
    private final String value;
    private final Instant expiresAt;

    private AccessToken(String value, Instant expiresAt) {
      this.value = value;
      this.expiresAt = expiresAt;
    }

    private boolean isExpired() {
      return Instant.now().isAfter(expiresAt.minusSeconds(60));
    }
  }
}
