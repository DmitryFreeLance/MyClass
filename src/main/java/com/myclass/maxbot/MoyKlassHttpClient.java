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
    if (user.getMoyklassUserId() != null) {
      return MoyKlassResult.success("Уже записан", String.valueOf(user.getMoyklassUserId()));
    }

    String name = data != null && data.getChildName() != null && !data.getChildName().isBlank()
        ? data.getChildName()
        : buildName(user, maxUserId);
    try {
      Map<String, Object> payload = new java.util.LinkedHashMap<>();
      payload.put("name", name);
      if (config.getLeadStateId() != null) {
        payload.put("clientStateId", config.getLeadStateId());
      }
      if (data != null) {
        if (data.getPhone() != null && !data.getPhone().isBlank()) {
          payload.put("phone", normalizePhone(data.getPhone()));
        }
        if (data.getEmail() != null && !data.getEmail().isBlank()) {
          payload.put("email", data.getEmail().trim());
        }
      }
      if (config.getMaxIdAttributeAlias() != null && !config.getMaxIdAttributeAlias().isBlank()) {
        List<Map<String, Object>> attributes = new ArrayList<>();
        attributes.add(Map.of(
            "attributeAlias", config.getMaxIdAttributeAlias(),
            "value", String.valueOf(maxUserId)
        ));
        if (data != null && data.getParentName() != null && !data.getParentName().isBlank()
            && config.getParentNameAttributeAlias() != null && !config.getParentNameAttributeAlias().isBlank()) {
          attributes.add(Map.of(
              "attributeAlias", config.getParentNameAttributeAlias(),
              "value", data.getParentName()
          ));
        }
        payload.put("attributes", attributes);
      }

      JsonNode response = postJson("/v1/company/users", payload);
      long moyklassUserId = response.path("id").asLong(0);
      if (moyklassUserId <= 0) {
        return MoyKlassResult.failure("CRM не вернул ID ученика.");
      }
      userRepository.setMoyklassUserId(maxUserId, moyklassUserId);
      return MoyKlassResult.success("Lead создан", String.valueOf(moyklassUserId));
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
    if (phone == null || phone.isBlank()) {
      return MoyKlassResult.failure("Телефон не указан.");
    }
    String normalized = normalizePhone(phone);
    if (normalized == null) {
      return MoyKlassResult.failure("Не удалось распознать номер телефона.");
    }

    try {
      String url = "/v1/company/users?phone=" + encode(normalized) + "&limit=2";
      JsonNode response = getJson(url);
      JsonNode users = response.path("users");
      if (!users.isArray() || users.isEmpty()) {
        return MoyKlassResult.failure("По этому номеру клиент не найден.");
      }
      if (users.size() > 1) {
        return MoyKlassResult.failure("Найдено несколько клиентов. Уточните номер телефона.");
      }

      JsonNode userNode = users.get(0);
      long moyklassUserId = userNode.path("id").asLong(0);
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
    } catch (Exception e) {
      log.warn("Failed to link by phone: {}", e.getMessage());
      return MoyKlassResult.failure("Ошибка при поиске клиента по телефону.");
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
