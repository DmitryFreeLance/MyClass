package com.myclass.maxbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
  private static final String STATE_SIGNUP_NAME_EXISTING = "signup_name_existing";
  private static final String STATE_SIGNUP_CHILD_NAME = "signup_child_name";
  private static final String STATE_SIGNUP_PHONE_NEW = "signup_phone_new";
  private static final String STATE_SIGNUP_EMAIL_NEW = "signup_email_new";
  private static final String STATE_SIGNUP_FILIAL_PICK = "signup_filial_pick";
  private static final String STATE_SIGNUP_CLASS_PICK = "signup_class_pick";
  private static final String SCHEDULE_URL = "https://дкразвитие.рф/schedule.html";

  private final BotProperties properties;
  private final MaxApiClient maxApiClient;
  private final KeyboardFactory keyboardFactory;
  private final BotStateRepository botStateRepository;
  private final UserRepository userRepository;
  private final UserChildRepository userChildRepository;
  private final DialogRepository dialogRepository;
  private final DialogService dialogService;
  private final MoyKlassClient moyKlassClient;
  private final UserStateRepository userStateRepository;
  private final ObjectMapper objectMapper;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private volatile boolean running = true;

  public MaxBotService(
      BotProperties properties,
      MaxApiClient maxApiClient,
      KeyboardFactory keyboardFactory,
      BotStateRepository botStateRepository,
      UserRepository userRepository,
      UserChildRepository userChildRepository,
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
    this.userChildRepository = userChildRepository;
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
    scheduler.shutdownNow();
  }

  private void pollLoop() {
    Long marker = botStateRepository.get(STATE_MARKER).map(Long::parseLong).orElse(null);

    while (running) {
      try {
        JsonNode response = maxApiClient.getUpdates(
            marker,
            properties.getMax().getLongPollLimit(),
            properties.getMax().getLongPollTimeoutSec(),
            null
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
      sendWelcome(senderId);
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
      handleNewSignupRedirect(userId);
      return;
    }

    if (userId <= 0) {
      return;
    }

    if (payload.startsWith("child:select:")) {
      long childId = parseLongSafe(payload.substring("child:select:".length()));
      if (childId > 0) {
        handleChildSelect(userId, childId);
      }
      return;
    }

    switch (payload) {
      case "action:signup" -> promptSignupChoice(userId, false);
      case "action:children" -> showChildrenMenu(userId);
      case "action:add_child" -> promptSignupChoice(userId, true);
      case "action:link" -> startSignupPhoneFlow(userId);
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
      sendAdminMessage("Админ-панель: " + url
          + "\nДля диалога с клиентом: /ask <номер телефона>"
          + "\nЕсли найдено несколько клиентов: /ask <номер телефона> <ФИО ребенка>");
      return;
    }

    if (text.startsWith("/ask")) {
      String[] parts = text.split("\\s+", 3);
      if (parts.length < 2) {
        sendAdminMessage("Формат: /ask <номер телефона> [ФИО ребенка]");
        return;
      }
      String target = parts[1];
      String digits = target.replaceAll("\\\\D", "");
      long userId = -1;
      if (digits.length() >= 10) {
        MoyKlassResult lookup;
        if (parts.length >= 3 && !parts[2].isBlank()) {
          lookup = moyKlassClient.resolveMaxUserIdByPhoneAndName(digits, parts[2]);
        } else {
          lookup = moyKlassClient.resolveMaxUserIdByPhone(digits);
        }
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
      String intro = "";
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
      if (STATE_SIGNUP_NAME_EXISTING.equals(state.getState())) {
        handleSignupNameExisting(userId, text);
        return;
      }
      if (STATE_SIGNUP_CHILD_NAME.equals(state.getState())
          || STATE_SIGNUP_PHONE_NEW.equals(state.getState())
          || STATE_SIGNUP_EMAIL_NEW.equals(state.getState())
          || STATE_SIGNUP_FILIAL_PICK.equals(state.getState())
          || STATE_SIGNUP_CLASS_PICK.equals(state.getState())) {
        handleNewSignupRedirect(userId);
        return;
      }
    }

    if (text.equalsIgnoreCase("/start") || text.equalsIgnoreCase("start")) {
      sendWelcome(userId);
      return;
    }

    if (text.equalsIgnoreCase("Записаться") || text.contains("Запис")) {
      promptSignupChoice(userId, hasLinkedChildren(userId));
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

    if (text.equalsIgnoreCase("Мои дети") || text.contains("дет")) {
      showChildrenMenu(userId);
      return;
    }

    sendWelcome(userId);
  }

  private void sendWelcome(long userId) {
    String text = "Здравствуйте. \nВыберите действие.";
    sendMainMenuMessage(userId, text);
  }

  private void handleSignup(long userId) {
    MoyKlassResult result = moyKlassClient.createLead(userId, "Запись из MAX", null);
    String response = formatSignupResponse(result);
    sendMenuMessage(userId, response);
  }

  private void promptSignupChoice(long userId, boolean ignoreExistingProfile) {
    if (!ignoreExistingProfile) {
      MoyKlassResult profile = moyKlassClient.getProfileInfo(userId);
      if (profile.isSuccess()) {
        String phone = profile.getData();
        String response = (phone != null && !phone.isBlank())
            ? "Вы уже записаны в нашей школе по номеру телефона: " + phone
            : "Вы уже записаны в нашей школе.";
        sendMenuMessage(userId, response);
        return;
      }
    }
    userStateRepository.setState(userId, STATE_SIGNUP_CHOICE, null, Instant.now().toEpochMilli());
    String text = "Вы уже зарегистрированы в нашей школе?";
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
      handleNewSignupRedirect(userId);
      return;
    }
    sendUserMessage(userId, "Ответьте, пожалуйста, \"Да\" или \"Нет\".");
  }

  private void handleNewSignupRedirect(long userId) {
    userStateRepository.clearState(userId);
    sendScheduleMessage(userId);
    scheduler.schedule(() -> sendLinkAccountPrompt(userId), 3, TimeUnit.SECONDS);
  }

  private void sendScheduleMessage(long userId) {
    String text = "Для регистрации перейдите по ссылке: " + SCHEDULE_URL;
    try {
      maxApiClient.sendMessageToUser(userId, Map.of(
          "text", text,
          "attachments", keyboardFactory.scheduleLinkAttachments()
      ));
    } catch (Exception e) {
      log.warn("Failed to send schedule link: {}", e.getMessage());
    }
  }

  private void sendLinkAccountPrompt(long userId) {
    String text = "После успешной регистрации необходимо связать учетные записи.";
    try {
      maxApiClient.sendMessageToUser(userId, Map.of(
          "text", text,
          "attachments", keyboardFactory.linkAccountAttachments()
      ));
    } catch (Exception e) {
      log.warn("Failed to send link account prompt: {}", e.getMessage());
    }
  }

  private void startSignupPhoneFlow(long userId) {
    userStateRepository.setState(userId, STATE_SIGNUP_PHONE_EXISTING, null, Instant.now().toEpochMilli());
    sendUserMessage(userId, "Введите номер телефона, который использовали при регистрации или оплате (только цифры).");
  }

  private void handleSignupPhoneExisting(long userId, String text) {
    MoyKlassResult result = moyKlassClient.linkByPhone(userId, text);
    if (result.isSuccess()) {
      userStateRepository.clearState(userId);
      rememberLinkedChild(userId, result, null);
      sendMenuMessage(userId, "Нашли ваши данные. Теперь можно пользоваться ботом.");
      return;
    }
    if (containsMultipleClientsMessage(result.getMessage())) {
      String digits = extractDigits(text);
      try {
        String data = objectMapper.writeValueAsString(Map.of("phone", digits));
        userStateRepository.setState(userId, STATE_SIGNUP_NAME_EXISTING, data, Instant.now().toEpochMilli());
      } catch (Exception e) {
        log.warn("Failed to store signup phone for name selection: {}", e.getMessage());
      }
      sendUserMessage(userId, "По этому номеру найдено несколько клиентов. Введите ФИО ребенка.");
      return;
    }
    String message = result.getMessage() + " Если вы новый клиент, нажмите \"Записаться\" и выберите \"Нет\".";
    if (containsPhoneParseError(result.getMessage())) {
      sendSignupMenuMessage(userId, message);
      return;
    }
    sendMenuMessage(userId, message);
  }

  private void handleSignupNameExisting(long userId, String text) {
    String childName = safeText(text);
    if (childName == null) {
      sendUserMessage(userId, "Пожалуйста, введите ФИО ребенка.");
      return;
    }
    String phone = extractPhoneFromState(userId);
    if (phone == null) {
      userStateRepository.clearState(userId);
      sendMenuMessage(userId, "Не смог найти номер телефона. Нажмите \"Записаться\" и попробуйте снова.");
      return;
    }
    MoyKlassResult result = moyKlassClient.linkByPhoneAndName(userId, phone, childName);
    if (result.isSuccess()) {
      userStateRepository.clearState(userId);
      rememberLinkedChild(userId, result, childName);
      sendMenuMessage(userId, "Нашли ваши данные. Теперь можно пользоваться ботом.");
      return;
    }
    sendUserMessage(userId, result.getMessage());
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
    startSignupFilialSelection(userId, data);
  }

  private void startSignupFilialSelection(long userId, SignupData data) {
    List<MoyKlassClient.Filial> filials = moyKlassClient.listFilials();
    List<MoyKlassClient.Filial> filtered = filials.stream()
        .filter(this::isActiveFilial)
        .toList();
    if (filtered.isEmpty()) {
      filtered = filials;
    }
    if (filtered.isEmpty()) {
      userStateRepository.clearState(userId);
      sendMenuMessage(userId, "Не удалось получить список филиалов. Попробуйте позже.");
      return;
    }

    data.filialOptions = filtered.stream()
        .map(this::toFilialOption)
        .toList();
    data.classOptions = null;
    data.filialId = null;
    data.filialName = null;
    saveSignupData(userId, STATE_SIGNUP_FILIAL_PICK, data);

    sendUserMessage(userId, formatOptionsMessage(
        "Выберите филиал (введите номер):",
        data.filialOptions
    ));
  }

  private void handleSignupFilialPick(long userId, String text) {
    SignupData data = getSignupData(userId);
    if (data.filialOptions == null || data.filialOptions.isEmpty()) {
      startSignupFilialSelection(userId, data);
      return;
    }
    Integer index = parseSelectionIndex(text, data.filialOptions.size());
    if (index == null) {
      sendUserMessage(userId, "Введите номер от 1 до " + data.filialOptions.size() + ".");
      return;
    }
    SignupOption selected = data.filialOptions.get(index - 1);
    data.filialId = selected.id;
    data.filialName = selected.name;
    startSignupClassSelection(userId, data);
  }

  private void startSignupClassSelection(long userId, SignupData data) {
    if (data.filialId == null || data.filialId <= 0) {
      startSignupFilialSelection(userId, data);
      return;
    }
    List<MoyKlassClient.ClassGroup> classes = moyKlassClient.listClasses();
    List<MoyKlassClient.ClassGroup> filtered = classes.stream()
        .filter(item -> item.getFilialId() == data.filialId)
        .filter(this::isOpenedClass)
        .toList();

    if (filtered.isEmpty()) {
      data.filialId = null;
      data.filialName = null;
      saveSignupData(userId, STATE_SIGNUP_FILIAL_PICK, data);
      sendUserMessage(userId, "Для выбранного филиала нет доступных групп. Выберите другой филиал (номер).");
      return;
    }

    data.classOptions = filtered.stream()
        .map(this::toClassOption)
        .toList();
    saveSignupData(userId, STATE_SIGNUP_CLASS_PICK, data);

    String title = data.filialName == null ? "Выберите группу (введите номер):"
        : "Филиал: " + data.filialName + "\nВыберите группу (введите номер):";
    sendUserMessage(userId, formatOptionsMessage(title, data.classOptions));
  }

  private void handleSignupClassPick(long userId, String text) {
    SignupData data = getSignupData(userId);
    if (data.classOptions == null || data.classOptions.isEmpty()) {
      startSignupClassSelection(userId, data);
      return;
    }
    Integer index = parseSelectionIndex(text, data.classOptions.size());
    if (index == null) {
      sendUserMessage(userId, "Введите номер от 1 до " + data.classOptions.size() + ".");
      return;
    }
    SignupOption selected = data.classOptions.get(index - 1);
    data.classId = selected.id;
    data.className = selected.name;

    userStateRepository.clearState(userId);
    MoyKlassClient.SignupData payload = new MoyKlassClient.SignupData(
        data.childName, data.phone, data.email, data.filialId, data.classId
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

  private void sendUserMessageWithAttachments(long userId, String text, List<Map<String, Object>> attachments) {
    try {
      maxApiClient.sendMessageToUser(userId, Map.of(
          "text", text,
          "attachments", attachments
      ));
    } catch (Exception e) {
      log.warn("Failed to send message with attachments to user {}: {}", userId, e.getMessage());
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

  private void sendMainMenuMessage(long userId, String text) {
    boolean linked = hasLinkedChildren(userId);
    try {
      maxApiClient.sendMessageToUser(userId, Map.of(
          "text", text,
          "attachments", keyboardFactory.mainMenuAttachments(linked)
      ));
    } catch (Exception e) {
      log.warn("Failed to send main menu message to user {}: {}", userId, e.getMessage());
    }
  }

  private void showChildrenMenu(long userId) {
    List<UserChildRepository.UserChild> children = ensureChildrenLoaded(userId);
    if (children.isEmpty()) {
      sendUserMessageWithAttachments(
          userId,
          "Пока нет связанных детей. Нажмите \"Связать\", чтобы привязать учетную запись.",
          keyboardFactory.linkAccountAttachments()
      );
      return;
    }

    sendUserMessageWithAttachments(userId, "Выберите ребенка:", buildChildrenAttachments(children));
  }

  private void handleChildSelect(long userId, long childId) {
    Optional<UserChildRepository.UserChild> childOpt = userChildRepository.findChild(userId, childId);
    if (childOpt.isEmpty()) {
      sendUserMessage(userId, "Не удалось найти выбранного ребенка. Попробуйте снова.");
      return;
    }
    UserChildRepository.UserChild child = childOpt.get();
    userRepository.setMoyklassUserId(userId, child.getMoyklassUserId());
    String name = child.getChildName() == null || child.getChildName().isBlank()
        ? "Ребенок " + child.getMoyklassUserId()
        : child.getChildName();
    sendMainMenuMessage(userId, "Выбран ребенок: " + name);
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

  private boolean containsMultipleClientsMessage(String message) {
    if (message == null) {
      return false;
    }
    String normalized = message.toLowerCase();
    return normalized.contains("несколько клиентов") || normalized.contains("несколько клиентов по этому номеру");
  }

  private String extractDigits(String value) {
    if (value == null) {
      return null;
    }
    String digits = value.replaceAll("\\\\D", "");
    return digits.isBlank() ? null : digits;
  }

  private String extractPhoneFromState(long userId) {
    UserStateRepository.UserState state = userStateRepository.getState(userId).orElse(null);
    if (state == null || state.getData() == null) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(state.getData());
      String phone = node.path("phone").asText(null);
      return phone == null || phone.isBlank() ? null : phone;
    } catch (Exception e) {
      return null;
    }
  }

  private String formatSignupResponse(MoyKlassResult result) {
    if (result == null) {
      return "Не удалось записать. Попробуйте позже.";
    }
    if (!result.isSuccess()) {
      return result.getMessage();
    }
    String raw = result.getMessage();
    String msg = raw == null ? "" : raw.toLowerCase();
    if (msg.contains("уже записан")) {
      return "Вы уже записаны в нашей школе.";
    }
    if (raw != null && !raw.isBlank()) {
      return raw;
    }
    return "Ребенок успешно записан";
  }

  private boolean hasLinkedChildren(long userId) {
    return !ensureChildrenLoaded(userId).isEmpty();
  }

  private List<UserChildRepository.UserChild> ensureChildrenLoaded(long userId) {
    List<UserChildRepository.UserChild> children = userChildRepository.listChildren(userId);
    if (!children.isEmpty()) {
      return children;
    }
    Optional<UserRecord> userOpt = userRepository.findByMaxUserId(userId);
    if (userOpt.isEmpty() || userOpt.get().getMoyklassUserId() == null) {
      return children;
    }
    long moyklassUserId = userOpt.get().getMoyklassUserId();
    rememberChild(userId, moyklassUserId, null);
    return userChildRepository.listChildren(userId);
  }

  private void rememberLinkedChild(long userId, MoyKlassResult result, String fallbackName) {
    if (result == null || !result.isSuccess()) {
      return;
    }
    long moyklassUserId = parseLongSafe(result.getData());
    if (moyklassUserId <= 0) {
      return;
    }
    rememberChild(userId, moyklassUserId, fallbackName);
  }

  private void rememberChild(long userId, long moyklassUserId, String fallbackName) {
    String name = fallbackName;
    MoyKlassClient.MoyKlassUser info = moyKlassClient.getUserInfo(moyklassUserId);
    if (info != null && info.getName() != null && !info.getName().isBlank()) {
      name = info.getName();
    }
    if (name == null || name.isBlank()) {
      name = "Ребенок " + moyklassUserId;
    }
    userChildRepository.upsertChild(userId, moyklassUserId, name, Instant.now().toEpochMilli());
    userRepository.setMoyklassUserId(userId, moyklassUserId);
  }

  private List<Map<String, Object>> buildChildrenAttachments(List<UserChildRepository.UserChild> children) {
    List<List<Map<String, Object>>> rows = new java.util.ArrayList<>();
    for (UserChildRepository.UserChild child : children) {
      String label = child.getChildName() == null || child.getChildName().isBlank()
          ? "Ребенок " + child.getMoyklassUserId()
          : child.getChildName();
      rows.add(List.of(callbackButton(label, "child:select:" + child.getMoyklassUserId())));
    }
    rows.add(List.of(callbackButton("➕ Добавить ребенка", "action:add_child")));
    rows.add(List.of(callbackButton("🏠 В меню", "action:menu")));
    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  private Map<String, Object> callbackButton(String text, String payload) {
    return Map.of(
        "type", "callback",
        "text", text,
        "payload", payload
    );
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
    public String phone;
    public String email;
    public Long filialId;
    public String filialName;
    public Long classId;
    public String className;
    public List<SignupOption> filialOptions;
    public List<SignupOption> classOptions;
  }

  private static class SignupOption {
    public long id;
    public String name;

    public SignupOption() {
    }

    public SignupOption(long id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  private SignupOption toFilialOption(MoyKlassClient.Filial filial) {
    String name = filial.getName();
    String shortName = filial.getShortName();
    if (shortName != null && !shortName.isBlank()) {
      if (name == null || name.isBlank()) {
        name = shortName;
      } else if (!name.toLowerCase().contains(shortName.toLowerCase())) {
        name = shortName + " — " + name;
      }
    }
    return new SignupOption(filial.getId(), name == null ? "" : name);
  }

  private SignupOption toClassOption(MoyKlassClient.ClassGroup group) {
    String name = group.getName();
    return new SignupOption(group.getId(), name == null ? "" : name);
  }

  private boolean isActiveFilial(MoyKlassClient.Filial filial) {
    if (filial == null) {
      return false;
    }
    String status = filial.getStatus();
    return status == null || status.isBlank() || status.equalsIgnoreCase("active");
  }

  private boolean isOpenedClass(MoyKlassClient.ClassGroup group) {
    if (group == null) {
      return false;
    }
    String status = group.getStatus();
    return status == null || status.isBlank() || status.equalsIgnoreCase("opened");
  }

  private Integer parseSelectionIndex(String text, int max) {
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(trimmed);
    if (!matcher.find()) {
      return null;
    }
    try {
      int value = Integer.parseInt(matcher.group(1));
      if (value < 1 || value > max) {
        return null;
      }
      return value;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String formatOptionsMessage(String title, List<SignupOption> options) {
    StringBuilder sb = new StringBuilder();
    sb.append(title);
    for (int i = 0; i < options.size(); i++) {
      SignupOption option = options.get(i);
      sb.append("\n").append(i + 1).append(". ").append(option.name == null ? "" : option.name);
    }
    return sb.toString();
  }

  private void sleepQuietly(long millis) {
    try {
      TimeUnit.MILLISECONDS.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
