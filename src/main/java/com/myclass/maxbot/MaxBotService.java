package com.myclass.maxbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

@Service
public class MaxBotService implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(MaxBotService.class);
  private static final String STATE_MARKER = "max.marker";
  private static final String STATE_ADMIN_DIALOG = "admin.current_dialog_id";
  private static final String STATE_SIGNUP_CHOICE = "signup_choice";
  private static final String STATE_SIGNUP_PHONE_EXISTING = "signup_phone_existing";
  private static final String STATE_SIGNUP_CHILD_NAME = "signup_child_name";
  private static final String STATE_SIGNUP_PARENT_NAME = "signup_parent_name";
  private static final String STATE_SIGNUP_PHONE_NEW = "signup_phone_new";
  private static final String STATE_SIGNUP_EMAIL_NEW = "signup_email_new";

  private final BotProperties properties;
  private final MaxApiClient maxApiClient;
  private final KeyboardFactory keyboardFactory;
  private final BotStateRepository botStateRepository;
  private final UserRepository userRepository;
  private final DialogRepository dialogRepository;
  private final DialogService dialogService;
  private final MoyKlassClient moyKlassClient;
  private final UserStateRepository userStateRepository;
  private final ObjectMapper objectMapper;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private volatile boolean running = true;

  public MaxBotService(
      BotProperties properties,
      MaxApiClient maxApiClient,
      KeyboardFactory keyboardFactory,
      BotStateRepository botStateRepository,
      UserRepository userRepository,
      DialogRepository dialogRepository,
      DialogService dialogService,
      MoyKlassClient moyKlassClient,
      UserStateRepository userStateRepository,
      ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.maxApiClient = maxApiClient;
    this.keyboardFactory = keyboardFactory;
    this.botStateRepository = botStateRepository;
    this.userRepository = userRepository;
    this.dialogRepository = dialogRepository;
    this.dialogService = dialogService;
    this.moyKlassClient = moyKlassClient;
    this.userStateRepository = userStateRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (properties.getMax().getToken() == null || properties.getMax().getToken().isBlank()) {
      log.warn("MAX_BOT_TOKEN is empty. Bot will not start long polling.");
      return;
    }
    if (properties.getMax().getAdminUserId() <= 0) {
      log.warn("MAX_ADMIN_USER_ID is not set. Admin features will be disabled.");
    }

    executor.submit(this::pollLoop);
  }

  @PreDestroy
  public void shutdown() {
    running = false;
    executor.shutdownNow();
  }

  private void pollLoop() {
    Long marker = botStateRepository.get(STATE_MARKER).map(Long::parseLong).orElse(null);

    while (running) {
      try {
        JsonNode response = maxApiClient.getUpdates(
            marker,
            properties.getMax().getLongPollLimit(),
            properties.getMax().getLongPollTimeoutSec(),
            List.of("message_created", "message_callback")
        );

        JsonNode updates = response.path("updates");
        if (updates.isArray()) {
          for (JsonNode update : updates) {
            handleUpdate(update);
          }
        }

        if (response.hasNonNull("marker")) {
          marker = response.get("marker").asLong();
          botStateRepository.set(STATE_MARKER, String.valueOf(marker));
        }
      } catch (Exception e) {
        log.warn("Error in long polling loop: {}", e.getMessage());
        sleepQuietly(2000);
      }
    }
  }

  private void handleUpdate(JsonNode update) {
    String updateType = update.path("update_type").asText("");
    switch (updateType) {
      case "message_created" -> handleMessageCreated(update.path("message"));
      case "message_callback" -> handleMessageCallback(update.path("callback"));
      default -> log.debug("Skipping update type: {}", updateType);
    }
  }

  private void handleMessageCreated(JsonNode message) {
    if (message.isMissingNode()) {
      return;
    }

    JsonNode sender = message.path("sender");
    if (sender.path("is_bot").asBoolean(false)) {
      return;
    }

    long senderId = sender.path("user_id").asLong(0);
    String senderName = sender.path("name").asText("");
    String senderUsername = sender.path("username").asText("");

    if (senderId == 0) {
      return;
    }

    String firstName = senderName;
    String lastName = null;
    if (senderName.contains(" ")) {
      String[] parts = senderName.split(" ", 2);
      firstName = parts[0];
      lastName = parts[1];
    }

    userRepository.upsertUser(senderId, firstName, lastName, senderUsername, Instant.now().toEpochMilli());

    String text = message.path("body").path("text").asText("").trim();
    if (text.isEmpty()) {
      return;
    }

    if (senderId == properties.getMax().getAdminUserId()) {
      boolean hasAdminDialog = getActiveAdminDialog().isPresent();
      if (text.startsWith("/ask") || text.startsWith("/admin") || hasAdminDialog) {
        handleAdminMessage(text);
      } else {
        handleUserMessage(senderId, text);
      }
    } else {
      handleUserMessage(senderId, text);
    }
  }

  private void handleMessageCallback(JsonNode callback) {
    if (callback.isMissingNode()) {
      return;
    }

    String payload = callback.path("payload").asText(null);
    if (payload == null || payload.isBlank()) {
      payload = callback.path("payload").toString();
    }

    String callbackId = callback.path("callback_id").asText(null);
    long userId = callback.path("user").path("user_id").asLong(0);
    if (userId == 0) {
      userId = callback.path("sender").path("user_id").asLong(0);
    }

    if (callbackId != null && !callbackId.isBlank()) {
      try {
        maxApiClient.answerCallback(callbackId, Map.of("notification", "Принято"));
      } catch (Exception e) {
        log.debug("Failed to answer callback: {}", e.getMessage());
      }
    }

    if (payload == null) {
      return;
    }

    if (payload.startsWith("close_dialog:")) {
      long dialogId = parseLongSafe(payload.substring("close_dialog:".length()));
      if (dialogId > 0) {
        dialogService.closeDialog(dialogId, "по кнопке");
        botStateRepository.delete(STATE_ADMIN_DIALOG);
      }
      return;
    }

    if ("signup:existing_yes".equals(payload)) {
      startSignupPhoneFlow(userId);
      return;
    }
    if ("signup:existing_no".equals(payload)) {
      userStateRepository.clearState(userId);
      startSignupChildName(userId);
      return;
    }

    if (userId <= 0) {
      return;
    }

    switch (payload) {
      case "action:signup" -> promptSignupChoice(userId);
      case "action:passes" -> handleRemainingLessons(userId);
      case "action:invoice" -> handleInvoice(userId);
      case "action:menu" -> sendWelcome(userId);
      default -> log.debug("Unknown callback payload: {}", payload);
    }
  }

  private void handleAdminMessage(String text) {
    if (text.startsWith("/admin")) {
      String url = properties.getAdmin().getPanelUrl();
      if (url == null || url.isBlank()) {
        url = "http://<ваш-домен>/admin/index.html";
      }
      sendAdminMessage("Админ-панель: " + url + "\nДля диалога с клиентом: /ask <номер телефона>");
      return;
    }

    if (text.startsWith("/ask")) {
      String[] parts = text.split("\\s+", 3);
      if (parts.length < 2) {
        sendAdminMessage("Формат: /ask <номер телефона> [сообщение]");
        return;
      }
      String target = parts[1];
      String digits = target.replaceAll("\\\\D", "");
      long userId = -1;
      if (digits.length() >= 10) {
        MoyKlassResult lookup = moyKlassClient.resolveMaxUserIdByPhone(digits);
        if (!lookup.isSuccess()) {
          sendAdminMessage(lookup.getMessage());
          return;
        }
        userId = parseLongSafe(lookup.getData());
      } else {
        userId = parseLongSafe(target);
      }

      if (userId <= 0) {
        sendAdminMessage("Не смог распознать номер телефона или user_id: " + parts[1]);
        return;
      }
      String intro = parts.length >= 3 ? parts[2] : "";
      DialogRecord dialog = dialogService.startDialog(userId, properties.getMax().getAdminUserId(), intro);
      botStateRepository.set(STATE_ADMIN_DIALOG, String.valueOf(dialog.getId()));
      sendAdminMessageWithClose("Диалог начат с пользователем " + userId + ".", dialog.getId());
      return;
    }

    Optional<Long> currentDialogId = botStateRepository.get(STATE_ADMIN_DIALOG)
        .map(this::parseLongSafe)
        .filter(id -> id > 0);

    if (currentDialogId.isEmpty()) {
      sendAdminMessage("Нет активного диалога. Используйте /ask <user_id>.");
      return;
    }

    DialogRecord dialog = dialogRepository.findById(currentDialogId.get()).orElse(null);
    if (dialog == null || !dialog.isActive()) {
      sendAdminMessage("Диалог уже завершен. Используйте /ask <user_id>.");
      botStateRepository.delete(STATE_ADMIN_DIALOG);
      return;
    }

    dialogService.forwardAdminMessage(dialog, text);
  }

  private Optional<DialogRecord> getActiveAdminDialog() {
    Optional<Long> currentDialogId = botStateRepository.get(STATE_ADMIN_DIALOG)
        .map(this::parseLongSafe)
        .filter(id -> id > 0);
    if (currentDialogId.isEmpty()) {
      return Optional.empty();
    }
    return dialogRepository.findById(currentDialogId.get()).filter(DialogRecord::isActive);
  }

  private void handleUserMessage(long userId, String text) {
    DialogRecord dialog = dialogRepository.findActiveByUserId(userId).orElse(null);
    if (dialog != null && dialog.isActive()) {
      dialogService.forwardUserMessage(dialog, text);
      return;
    }

    UserStateRepository.UserState state = userStateRepository.getState(userId).orElse(null);
    if (state != null) {
      if (STATE_SIGNUP_CHOICE.equals(state.getState())) {
        handleSignupChoice(userId, text);
        return;
      }
      if (STATE_SIGNUP_PHONE_EXISTING.equals(state.getState())) {
        handleSignupPhoneExisting(userId, text);
        return;
      }
      if (STATE_SIGNUP_CHILD_NAME.equals(state.getState())) {
        handleSignupChildName(userId, text);
        return;
      }
      if (STATE_SIGNUP_PARENT_NAME.equals(state.getState())) {
        handleSignupParentName(userId, text);
        return;
      }
      if (STATE_SIGNUP_PHONE_NEW.equals(state.getState())) {
        handleSignupPhoneNew(userId, text);
        return;
      }
      if (STATE_SIGNUP_EMAIL_NEW.equals(state.getState())) {
        handleSignupEmailNew(userId, text);
        return;
      }
    }

    if (text.equalsIgnoreCase("/start") || text.equalsIgnoreCase("start")) {
      sendWelcome(userId);
      return;
    }

    if (text.equalsIgnoreCase("Записаться") || text.contains("Запис")) {
      promptSignupChoice(userId);
      return;
    }

    if (text.equalsIgnoreCase("Абонементы") || text.contains("Абон")) {
      handleRemainingLessons(userId);
      return;
    }

    if (text.equalsIgnoreCase("Счет на оплату") || text.contains("Счет")) {
      handleInvoice(userId);
      return;
    }

    sendWelcome(userId);
  }

  private void sendWelcome(long userId) {
    String text = "Привет! Я помогу записать ребенка, проверить абонементы и выставить счет. Выберите действие ниже.";
    try {
      maxApiClient.sendMessageToUser(userId, Map.of(
          "text", text,
          "attachments", keyboardFactory.mainMenuAttachments()
      ));
    } catch (Exception e) {
      log.warn("Failed to send welcome: {}", e.getMessage());
    }
  }

  private void handleSignup(long userId) {
    MoyKlassResult result = moyKlassClient.createLead(userId, "Запись из MAX", null);
    String response = formatSignupResponse(result);
    sendMenuMessage(userId, response);
  }

  private void promptSignupChoice(long userId) {
    MoyKlassResult profile = moyKlassClient.getProfileInfo(userId);
    if (profile.isSuccess()) {
      String phone = profile.getData();
      String response = (phone != null && !phone.isBlank())
          ? "Вы уже записаны в нашей школе по номеру телефона: " + phone
          : "Вы уже записаны в нашей школе.";
      sendMenuMessage(userId, response);
      return;
    }
    userStateRepository.setState(userId, STATE_SIGNUP_CHOICE, null, Instant.now().toEpochMilli());
    String text = "Вы уже занимались в нашей школе?";
    try {
      maxApiClient.sendMessageToUser(userId, Map.of(
          "text", text,
          "attachments", keyboardFactory.signupChoiceAttachments()
      ));
    } catch (Exception e) {
      log.warn("Failed to send signup choice: {}", e.getMessage());
    }
  }

  private void handleSignupChoice(long userId, String text) {
    String normalized = text == null ? "" : text.trim().toLowerCase();
    if (normalized.startsWith("да") || normalized.contains("уже") || normalized.contains("оплат")) {
      startSignupPhoneFlow(userId);
      return;
    }
    if (normalized.startsWith("нет") || normalized.contains("нов")) {
      userStateRepository.clearState(userId);
      startSignupChildName(userId);
      return;
    }
    sendUserMessage(userId, "Ответьте, пожалуйста, \"Да\" или \"Нет\".");
  }

  private void startSignupPhoneFlow(long userId) {
    userStateRepository.setState(userId, STATE_SIGNUP_PHONE_EXISTING, null, Instant.now().toEpochMilli());
    sendUserMessage(userId, "Введите номер телефона, который использовали при оплате (только цифры).");
  }

  private void handleSignupPhoneExisting(long userId, String text) {
    MoyKlassResult result = moyKlassClient.linkByPhone(userId, text);
    if (result.isSuccess()) {
      userStateRepository.clearState(userId);
      sendMenuMessage(userId, "Нашли ваши данные. Теперь можно пользоваться ботом.");
      return;
    }
    String message = result.getMessage() + " Если вы новый клиент, нажмите \"Записаться\" и выберите \"Нет\".";
    if (containsPhoneParseError(result.getMessage())) {
      sendSignupMenuMessage(userId, message);
      return;
    }
    sendMenuMessage(userId, message);
  }

  private void startSignupChildName(long userId) {
    userStateRepository.setState(userId, STATE_SIGNUP_CHILD_NAME, "{}", Instant.now().toEpochMilli());
    sendUserMessage(userId, "Введите ФИО ребенка.");
  }

  private void handleSignupChildName(long userId, String text) {
    String childName = safeText(text);
    if (childName == null) {
      sendUserMessage(userId, "Пожалуйста, введите ФИО ребенка.");
      return;
    }
    SignupData data = getSignupData(userId);
    data.childName = childName;
    saveSignupData(userId, STATE_SIGNUP_PARENT_NAME, data);
    sendUserMessage(userId, "Введите ФИО родителя.");
  }

  private void handleSignupParentName(long userId, String text) {
    String parentName = safeText(text);
    if (parentName == null) {
      sendUserMessage(userId, "Пожалуйста, введите ФИО родителя.");
      return;
    }
    SignupData data = getSignupData(userId);
    data.parentName = parentName;
    saveSignupData(userId, STATE_SIGNUP_PHONE_NEW, data);
    sendUserMessage(userId, "Введите номер телефона (только цифры).");
  }

  private void handleSignupPhoneNew(long userId, String text) {
    String phone = text == null ? "" : text.replaceAll("\\\\D", "");
    if (phone.length() < 10) {
      sendSignupMenuMessage(userId, "Не смог распознать номер. Введите номер телефона цифрами.");
      return;
    }
    SignupData data = getSignupData(userId);
    data.phone = phone;
    saveSignupData(userId, STATE_SIGNUP_EMAIL_NEW, data);
    sendUserMessage(userId, "Введите email или напишите \"Пропустить\".");
  }

  private void handleSignupEmailNew(long userId, String text) {
    String email = text == null ? "" : text.trim();
    if (!email.equalsIgnoreCase("пропустить") && !email.equalsIgnoreCase("-") && !email.isBlank()) {
      if (!email.contains("@") || email.length() < 5) {
        sendUserMessage(userId, "Некорректный email. Введите email или напишите \"Пропустить\".");
        return;
      }
    } else {
      email = null;
    }
    SignupData data = getSignupData(userId);
    data.email = email;
    userStateRepository.clearState(userId);
    MoyKlassClient.SignupData payload = new MoyKlassClient.SignupData(
        data.childName, data.parentName, data.phone, data.email
    );
    MoyKlassResult result = moyKlassClient.createLead(userId, "Запись из MAX", payload);
    String response = formatSignupResponse(result);
    sendMenuMessage(userId, response);
  }

  private void handleRemainingLessons(long userId) {
    MoyKlassResult result = moyKlassClient.getRemainingLessons(userId);
    String response = result.isSuccess()
        ? (result.getData() == null ? result.getMessage() : "Осталось занятий: " + result.getData())
        : result.getMessage();
    sendMenuMessage(userId, response);
  }

  private void handleInvoice(long userId) {
    MoyKlassResult result = moyKlassClient.createInvoice(userId);
    String response = result.isSuccess()
        ? (result.getData() == null ? result.getMessage() : "Счет сформирован: " + result.getData())
        : result.getMessage();
    sendMenuMessage(userId, response);
  }

  private void sendAdminMessage(String text) {
    if (properties.getMax().getAdminUserId() <= 0) {
      return;
    }
    sendUserMessage(properties.getMax().getAdminUserId(), text);
  }

  private void sendAdminMessageWithClose(String text, long dialogId) {
    if (properties.getMax().getAdminUserId() <= 0) {
      return;
    }
    try {
      maxApiClient.sendMessageToUser(properties.getMax().getAdminUserId(), Map.of(
          "text", text,
          "attachments", keyboardFactory.closeDialogAttachments(dialogId)
      ));
    } catch (Exception e) {
      log.warn("Failed to send admin message with close button: {}", e.getMessage());
    }
  }

  private void sendUserMessage(long userId, String text) {
    try {
      maxApiClient.sendMessageToUser(userId, Map.of("text", text));
    } catch (Exception e) {
      log.warn("Failed to send message to user {}: {}", userId, e.getMessage());
    }
  }

  private void sendMenuMessage(long userId, String text) {
    try {
      maxApiClient.sendMessageToUser(userId, Map.of(
          "text", text,
          "attachments", keyboardFactory.menuOnlyAttachments()
      ));
    } catch (Exception e) {
      log.warn("Failed to send menu message to user {}: {}", userId, e.getMessage());
    }
  }

  private void sendSignupMenuMessage(long userId, String text) {
    try {
      maxApiClient.sendMessageToUser(userId, Map.of(
          "text", text,
          "attachments", keyboardFactory.signupMenuAttachments()
      ));
    } catch (Exception e) {
      log.warn("Failed to send signup menu message to user {}: {}", userId, e.getMessage());
    }
  }

  private long parseLongSafe(String value) {
    try {
      return Long.parseLong(value);
    } catch (Exception e) {
      return -1L;
    }
  }

  private boolean isNoProfileMessage(String message) {
    if (message == null) {
      return false;
    }
    return message.toLowerCase().contains("не найден профиль");
  }

  private boolean containsPhoneParseError(String message) {
    if (message == null) {
      return false;
    }
    return message.toLowerCase().contains("не удалось распознать номер");
  }

  private String formatSignupResponse(MoyKlassResult result) {
    if (result == null) {
      return "Не удалось записать. Попробуйте позже.";
    }
    if (!result.isSuccess()) {
      return result.getMessage();
    }
    String msg = result.getMessage() == null ? "" : result.getMessage().toLowerCase();
    if (msg.contains("уже записан")) {
      return "Вы уже записаны в нашей школе.";
    }
    return "Ребенок успешно записан";
  }

  private String safeText(String text) {
    if (text == null) {
      return null;
    }
    String value = text.trim();
    return value.isBlank() ? null : value;
  }

  private SignupData getSignupData(long userId) {
    return userStateRepository.getState(userId)
        .map(state -> parseSignupData(state.getData()))
        .orElseGet(SignupData::new);
  }

  private void saveSignupData(long userId, String nextState, SignupData data) {
    userStateRepository.setState(userId, nextState, toJson(data), Instant.now().toEpochMilli());
  }

  private SignupData parseSignupData(String json) {
    if (json == null || json.isBlank()) {
      return new SignupData();
    }
    try {
      return objectMapper.readValue(json, SignupData.class);
    } catch (Exception e) {
      return new SignupData();
    }
  }

  private String toJson(SignupData data) {
    try {
      return objectMapper.writeValueAsString(data);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static class SignupData {
    public String childName;
    public String parentName;
    public String phone;
    public String email;
  }

  private void sleepQuietly(long millis) {
    try {
      TimeUnit.MILLISECONDS.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
