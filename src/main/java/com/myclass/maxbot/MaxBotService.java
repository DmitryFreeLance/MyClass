package com.myclass.maxbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
  private static final String STATE_ADMIN_TEXT_EDIT = "admin_text_edit";
  private static final String SCHEDULE_URL = "https://дкразвитие.рф/schedule.html";
  private static final String TEXT_WELCOME = "text.welcome";
  private static final String TEXT_SIGNUP_REDIRECT = "text.signup_redirect";
  private static final String TEXT_LINK_PROMPT = "text.link_prompt";
  private static final String TEXT_AUTH_PROMPT = "text.auth_prompt";
  private static final String TEXT_FIRST_AUTH_NOTICE = "text.first_auth_notice";
  private static final String STATE_LAST_LESSON_RECORD = "notify.lastLessonRecordId";
  private static final String STATE_LESSONS_BOOT_ID = "notify.lessons.bootId";
  private static final String STATE_LAST_PAYMENT = "notify.lastPaymentId";
  private static final String STATE_PAYMENTS_BOOT_ID = "notify.payments.bootId";
  private static final long MARKER_RESET_AFTER_MS = 3 * 60 * 1000L;
  private static final long MARKER_RESET_COOLDOWN_MS = 10 * 60 * 1000L;
  private static final long LONG_POLL_ERROR_DELAY_MS = 2_000L;
  private static final long LONG_POLL_429_BASE_DELAY_MS = 30_000L;
  private static final long LONG_POLL_429_MAX_DELAY_MS = 120_000L;
  private static final long NOTIFY_LESSONS_INTERVAL_SEC = 60;
  private static final long NOTIFY_PAYMENTS_INTERVAL_SEC = 30;
  private static final long REFERENCE_CACHE_TTL_MS = 60 * 1000L;

  private final BotProperties properties;
  private final MaxApiClient maxApiClient;
  private final KeyboardFactory keyboardFactory;
  private final BotStateRepository botStateRepository;
  private final UserRepository userRepository;
  private final UserChildRepository userChildRepository;
  private final BotTextRepository botTextRepository;
  private final UserNotificationRepository userNotificationRepository;
  private final LessonNotificationRepository lessonNotificationRepository;
  private final AdminUserRepository adminUserRepository;
  private final DialogRepository dialogRepository;
  private final DialogService dialogService;
  private final MoyKlassClient moyKlassClient;
  private final UserStateRepository userStateRepository;
  private final ObjectMapper objectMapper;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final long bootId = System.currentTimeMillis();
  private volatile boolean running = true;
  private volatile long lastUpdateAt = System.currentTimeMillis();
  private volatile Long lastMarkerValue = null;
  private volatile long lastMarkerChangeAt = System.currentTimeMillis();
  private volatile long lastMarkerResetAt = 0L;
  private volatile long lastHeartbeatAt = 0L;
  private volatile int longPoll429Streak = 0;
  private volatile Map<Long, MoyKlassClient.ClassGroup> classCache = Map.of();
  private volatile Map<Long, String> courseCache = Map.of();
  private volatile long classCacheUpdatedAt = 0;
  private volatile long courseCacheUpdatedAt = 0;

  public MaxBotService(
      BotProperties properties,
      MaxApiClient maxApiClient,
      KeyboardFactory keyboardFactory,
      BotStateRepository botStateRepository,
      UserRepository userRepository,
      UserChildRepository userChildRepository,
      BotTextRepository botTextRepository,
      UserNotificationRepository userNotificationRepository,
      LessonNotificationRepository lessonNotificationRepository,
      AdminUserRepository adminUserRepository,
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
    this.botTextRepository = botTextRepository;
    this.userNotificationRepository = userNotificationRepository;
    this.lessonNotificationRepository = lessonNotificationRepository;
    this.adminUserRepository = adminUserRepository;
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
    if (properties.getMax().getAdminUserIdAsLong() <= 0) {
      log.warn("MAX_ADMIN_USER_ID is not set. Admin features will be disabled.");
    }

    if (properties.getMax().isWebhookEnabled()) {
      log.info("MAX webhook mode enabled. Long polling is disabled.");
    } else {
      executor.submit(this::pollLoop);
    }
    if (properties.getMoyklass().isEnabled() && properties.getMoyklass().getToken() != null
        && !properties.getMoyklass().getToken().isBlank()) {
      scheduler.scheduleAtFixedRate(this::pollLessonNotifications, 10,
          NOTIFY_LESSONS_INTERVAL_SEC, TimeUnit.SECONDS);
      scheduler.scheduleAtFixedRate(this::pollPaymentNotifications, 10,
          NOTIFY_PAYMENTS_INTERVAL_SEC, TimeUnit.SECONDS);
    }
  }

  @PreDestroy
  public void shutdown() {
    running = false;
    executor.shutdownNow();
    scheduler.shutdownNow();
  }

  private void pollLoop() {
    Long marker = botStateRepository.get(STATE_MARKER).map(Long::parseLong).orElse(null);
    if (marker != null) {
      lastMarkerValue = marker;
      lastMarkerChangeAt = System.currentTimeMillis();
    }

    while (running) {
      try {
        JsonNode response = maxApiClient.getUpdates(
            marker,
            properties.getMax().getLongPollLimit(),
            properties.getMax().getLongPollTimeoutSec(),
            null
        );
        longPoll429Streak = 0;

        JsonNode updates = response.path("updates");
        int updatesCount = 0;
        if (updates.isArray()) {
          for (JsonNode update : updates) {
            handleUpdate(update);
            updatesCount++;
          }
        }

        if (response.hasNonNull("marker")) {
          marker = response.get("marker").asLong();
          botStateRepository.set(STATE_MARKER, String.valueOf(marker));
        }

        logLongPollHeartbeat(marker, updatesCount);
        marker = maybeResetMarker(marker, updatesCount);
      } catch (Exception e) {
        log.warn("Error in long polling loop: {}", e.getMessage());
        sleepQuietly(computeLongPollErrorDelayMs(e));
      }
    }
  }

  private long computeLongPollErrorDelayMs(Exception error) {
    String message = error == null || error.getMessage() == null
        ? ""
        : error.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("429") || message.contains("too.many.requests")) {
      longPoll429Streak = Math.min(longPoll429Streak + 1, 4);
      long delay = LONG_POLL_429_BASE_DELAY_MS * (1L << (longPoll429Streak - 1));
      long bounded = Math.min(delay, LONG_POLL_429_MAX_DELAY_MS);
      log.warn("MAX API rate limit detected (streak={}). Backing off for {} ms.", longPoll429Streak, bounded);
      return bounded;
    }
    longPoll429Streak = 0;
    return LONG_POLL_ERROR_DELAY_MS;
  }

  private void logLongPollHeartbeat(Long marker, int updatesCount) {
    long now = System.currentTimeMillis();
    if (now - lastHeartbeatAt < 60_000L) {
      return;
    }
    lastHeartbeatAt = now;
    String markerText = marker == null ? "null" : String.valueOf(marker);
    log.info("Long-poll heartbeat: marker={}, updatesLastPoll={}", markerText, updatesCount);
  }

  private Long maybeResetMarker(Long marker, int updatesCount) {
    long now = System.currentTimeMillis();
    if (updatesCount > 0) {
      lastUpdateAt = now;
    }
    if (marker != null) {
      if (lastMarkerValue == null || !marker.equals(lastMarkerValue)) {
        lastMarkerValue = marker;
        lastMarkerChangeAt = now;
      }
    }
    boolean noUpdatesTooLong = now - lastUpdateAt > MARKER_RESET_AFTER_MS;
    boolean markerStale = now - lastMarkerChangeAt > MARKER_RESET_AFTER_MS;
    boolean cooldownPassed = now - lastMarkerResetAt > MARKER_RESET_COOLDOWN_MS;
    if (noUpdatesTooLong && markerStale && cooldownPassed) {
      log.warn("No updates for {} ms and marker stale; resetting marker.", MARKER_RESET_AFTER_MS);
      botStateRepository.delete(STATE_MARKER);
      lastMarkerResetAt = now;
      lastMarkerValue = null;
      lastMarkerChangeAt = now;
      return null;
    }
    return marker;
  }

  private void pollNotifications() {
    try {
      pollLessonNotifications();
    } catch (Exception e) {
      log.warn("Failed to poll lesson notifications: {}", e.getMessage());
    }
    try {
      pollPaymentNotifications();
    } catch (Exception e) {
      log.warn("Failed to poll payment notifications: {}", e.getMessage());
    }
  }

  private void pollLessonNotifications() {
    long storedBootId = botStateRepository.get(STATE_LESSONS_BOOT_ID)
        .map(this::parseLongSafe)
        .orElse(0L);
    boolean bootstrap = storedBootId != bootId;
    long lastId = botStateRepository.get(STATE_LAST_LESSON_RECORD)
        .map(this::parseLongSafe)
        .orElse(0L);
    List<MoyKlassClient.LessonRecordEvent> events = moyKlassClient.listVisitedLessonRecords(lastId);
    if (events.isEmpty()) {
      return;
    }
    List<Long> recordIds = events.stream()
        .map(MoyKlassClient.LessonRecordEvent::getId)
        .filter(id -> id > 0)
        .toList();
    java.util.Set<Long> notified = lessonNotificationRepository.findNotifiedRecordIds(recordIds);
    long maxId = lastId;
    for (MoyKlassClient.LessonRecordEvent event : events) {
      if (event.getId() > maxId) {
        maxId = event.getId();
      }
      if (event.getId() > 0 && !notified.contains(event.getId()) && isLessonConducted(event)) {
        if (!bootstrap) {
          sendLessonNotification(event);
        }
        lessonNotificationRepository.markNotified(event.getId(), System.currentTimeMillis());
      }
    }
    if (maxId > lastId) {
      botStateRepository.set(STATE_LAST_LESSON_RECORD, String.valueOf(maxId));
    }
    if (bootstrap) {
      botStateRepository.set(STATE_LESSONS_BOOT_ID, String.valueOf(bootId));
    }
  }

  private void pollPaymentNotifications() {
    long storedBootId = botStateRepository.get(STATE_PAYMENTS_BOOT_ID)
        .map(this::parseLongSafe)
        .orElse(0L);
    if (storedBootId != bootId) {
      long maxId = 0L;
      List<MoyKlassClient.PaymentEvent> recent = collectRecentPaymentEvents(0);
      for (MoyKlassClient.PaymentEvent event : recent) {
        if (event.getId() > maxId) {
          maxId = event.getId();
        }
        if (event.getUserId() > 0) {
          updatePaymentBaselineForUser(event.getUserId(), event.getId());
        }
      }
      if (maxId > 0) {
        botStateRepository.set(STATE_LAST_PAYMENT, String.valueOf(maxId));
      }
      botStateRepository.set(STATE_PAYMENTS_BOOT_ID, String.valueOf(bootId));
      return;
    }

    long lastId = botStateRepository.get(STATE_LAST_PAYMENT)
        .map(this::parseLongSafe)
        .orElse(0L);
    List<MoyKlassClient.PaymentEvent> events = collectRecentPaymentEvents(lastId);
    if (events.isEmpty()) {
      return;
    }
    long maxId = lastId;
    Map<Long, Long> maxByUser = new java.util.HashMap<>();
    for (MoyKlassClient.PaymentEvent event : events) {
      if (event.getId() > maxId) {
        maxId = event.getId();
      }
      if (event.getUserId() > 0) {
        long current = maxByUser.getOrDefault(event.getUserId(), 0L);
        if (event.getId() > current) {
          maxByUser.put(event.getUserId(), event.getId());
        }
      }
      sendPaymentNotification(event);
    }
    if (maxId > lastId) {
      botStateRepository.set(STATE_LAST_PAYMENT, String.valueOf(maxId));
    }
    if (!maxByUser.isEmpty()) {
      for (Map.Entry<Long, Long> entry : maxByUser.entrySet()) {
        updatePaymentBaselineForUser(entry.getKey(), entry.getValue());
      }
    }
  }

  private List<MoyKlassClient.PaymentEvent> collectRecentPaymentEvents(long lastId) {
    List<Long> linkedIds = userChildRepository.listDistinctMoyklassUserIds();
    if (!linkedIds.isEmpty() && linkedIds.size() <= 200) {
      Map<Long, MoyKlassClient.PaymentEvent> merged = new java.util.LinkedHashMap<>();
      for (Long moyklassUserId : linkedIds) {
        if (moyklassUserId == null || moyklassUserId <= 0) {
          continue;
        }
        long userBaseline = getPaymentBaselineForUser(moyklassUserId, lastId);
        for (MoyKlassClient.PaymentEvent event : moyKlassClient
            .listIncomingPaymentsByUser(moyklassUserId, userBaseline)) {
          if (event.getId() > 0) {
            merged.putIfAbsent(event.getId(), event);
          }
        }
      }
      java.util.List<MoyKlassClient.PaymentEvent> result = new java.util.ArrayList<>(merged.values());
      result.sort(java.util.Comparator.comparingLong(MoyKlassClient.PaymentEvent::getId));
      return result;
    }

    List<MoyKlassClient.PaymentEvent> events = moyKlassClient.listIncomingPayments(lastId);
    if (events.isEmpty()) {
      return events;
    }
    events.sort(java.util.Comparator.comparingLong(MoyKlassClient.PaymentEvent::getId));
    return events;
  }

  private long getPaymentBaselineForUser(long moyklassUserId, long fallback) {
    return botStateRepository.get(paymentUserKey(moyklassUserId))
        .map(this::parseLongSafe)
        .filter(value -> value > 0)
        .orElse(fallback);
  }

  private void updatePaymentBaselineForUser(long moyklassUserId, long lastPaymentId) {
    if (moyklassUserId <= 0 || lastPaymentId <= 0) {
      return;
    }
    botStateRepository.set(paymentUserKey(moyklassUserId), String.valueOf(lastPaymentId));
  }

  private void ensurePaymentBaselineForUser(long moyklassUserId) {
    if (moyklassUserId <= 0) {
      return;
    }
    String key = paymentUserKey(moyklassUserId);
    boolean exists = botStateRepository.get(key)
        .map(this::parseLongSafe)
        .filter(value -> value > 0)
        .isPresent();
    if (exists) {
      return;
    }
    long maxId = 0L;
    for (MoyKlassClient.PaymentEvent event : moyKlassClient.listIncomingPaymentsByUser(moyklassUserId, 0)) {
      if (event.getId() > maxId) {
        maxId = event.getId();
      }
    }
    if (maxId > 0) {
      botStateRepository.set(key, String.valueOf(maxId));
    }
  }

  private String paymentUserKey(long moyklassUserId) {
    return "notify.lastPaymentId.user." + moyklassUserId;
  }

  private boolean isLessonConducted(MoyKlassClient.LessonRecordEvent event) {
    if (event == null) {
      return false;
    }
    // Notify only for actually visited lessons.
    // Conducted lesson status alone is not enough: skipped records must not trigger alerts.
    return event.isVisited() && event.getLessonStatus() == 1;
  }

  private void sendLessonNotification(MoyKlassClient.LessonRecordEvent event) {
    if (event == null || event.getUserId() <= 0) {
      return;
    }
    List<Long> maxUserIds = userChildRepository.listMaxUserIdsByMoyklassUserId(event.getUserId());
    if (maxUserIds.isEmpty()) {
      return;
    }
    Map<Long, MoyKlassClient.ClassGroup> classMap = getClassMap();
    Map<Long, String> courseMap = getCourseMap();
    MoyKlassClient.ClassGroup group = event.getClassId() > 0 ? classMap.get(event.getClassId()) : null;
    if (group == null && event.getClassId() > 0) {
      invalidateReferenceCaches();
      classMap = getClassMap();
      courseMap = getCourseMap();
      group = classMap.get(event.getClassId());
    }
    String className = group != null && group.getName() != null && !group.getName().isBlank()
        ? group.getName()
        : (event.getClassId() > 0 ? "Группа #" + event.getClassId() : "Группа");
    String courseName = resolveCourseName(group, courseMap);
    MoyKlassClient.RemainingDetails details = moyKlassClient.getRemainingDetailsByMoyklassUserId(event.getUserId());
    int remaining = findRemainingFor(details, courseName, className);
    int remainingCount = remaining >= 0
        ? remaining
        : (details != null ? details.getTotal() : -1);
    RemainingSnapshot snapshot = findSubscriptionRemainingSnapshot(event.getUserId(), courseName, className);
    String programKey = buildProgramKey(courseName, className);
    RemainingState previous = readRemainingState(event.getUserId(), programKey);
    RemainingState current = buildRemainingState(snapshot, remainingCount);
    String direction = formatDirectionDative(courseName);
    log.info("Lesson notify calc: recordId={}, userId={}, classId={}, className='{}', courseName='{}', direction='{}', total={}, debt={}",
        event.getId(), event.getUserId(), event.getClassId(), className, courseName, direction,
        current == null ? -999 : current.getTotal(), current != null && current.isDebt());
    if (!shouldSendRemainingAlert(current, previous)) {
      writeRemainingState(event.getUserId(), programKey, current);
      return;
    }
    for (Long maxUserId : maxUserIds) {
      if (maxUserId != null && maxUserId > 0) {
        String childName = resolveChildName(maxUserId, event.getUserId());
        String message = buildRemainingAlert(childName, direction, current);
        sendUserMessage(maxUserId, message);
      }
    }
    writeRemainingState(event.getUserId(), programKey, current);
  }

  private void sendPaymentNotification(MoyKlassClient.PaymentEvent event) {
    if (event == null || event.getUserId() <= 0) {
      return;
    }
    List<Long> maxUserIds = userChildRepository.listMaxUserIdsByMoyklassUserId(event.getUserId());
    if (maxUserIds.isEmpty()) {
      return;
    }
    String amountText = formatAmount(event.getAmount());
    String program = formatProgramLabel(event.getComment());
    for (Long maxUserId : maxUserIds) {
      if (maxUserId != null && maxUserId > 0) {
        String childName = resolveChildName(maxUserId, event.getUserId());
        String message = "Спасибо!\nВаш платеж на сумму " + amountText
            + "руб. по программе " + program + " за ребенка " + childName + " получен.";
        sendUserMessage(maxUserId, message);
      }
    }
  }

  private Map<Long, MoyKlassClient.ClassGroup> getClassMap() {
    long now = System.currentTimeMillis();
    if (classCache.isEmpty() || now - classCacheUpdatedAt > REFERENCE_CACHE_TTL_MS) {
      Map<Long, MoyKlassClient.ClassGroup> next = new java.util.LinkedHashMap<>();
      for (MoyKlassClient.ClassGroup group : moyKlassClient.listClasses()) {
        if (group.getId() > 0) {
          next.put(group.getId(), group);
        }
      }
      classCache = next;
      classCacheUpdatedAt = now;
    }
    return classCache;
  }

  private Map<Long, String> getCourseMap() {
    long now = System.currentTimeMillis();
    if (courseCache.isEmpty() || now - courseCacheUpdatedAt > REFERENCE_CACHE_TTL_MS) {
      Map<Long, String> next = new java.util.LinkedHashMap<>();
      for (MoyKlassClient.Course course : moyKlassClient.listCourses()) {
        if (course.getId() > 0 && course.getName() != null && !course.getName().isBlank()) {
          next.put(course.getId(), course.getName());
        }
      }
      courseCache = next;
      courseCacheUpdatedAt = now;
    }
    return courseCache;
  }

  private void invalidateReferenceCaches() {
    classCache = Map.of();
    courseCache = Map.of();
    classCacheUpdatedAt = 0;
    courseCacheUpdatedAt = 0;
  }

  private String resolveCourseName(MoyKlassClient.ClassGroup group, Map<Long, String> courseMap) {
    if (group != null && group.getCourseId() > 0) {
      String name = courseMap.get(group.getCourseId());
      if (name != null && !name.isBlank()) {
        return name;
      }
      return "Курс #" + group.getCourseId();
    }
    return "Прочее";
  }

  private int findRemainingFor(MoyKlassClient.RemainingDetails details, String courseName, String className) {
    if (details == null || details.getItems() == null) {
      return -1;
    }
    for (MoyKlassClient.RemainingItem item : details.getItems()) {
      if (item == null) {
        continue;
      }
      String course = item.getCourseName() == null ? "" : item.getCourseName();
      String clazz = item.getClassName() == null ? "" : item.getClassName();
      if (course.equalsIgnoreCase(courseName) && clazz.equalsIgnoreCase(className)) {
        return item.getRemaining();
      }
    }
    return -1;
  }

  private String formatPaymentRemaining(MoyKlassClient.RemainingDetails details) {
    if (details == null) {
      return "Остаток занятий: 0";
    }
    List<MoyKlassClient.RemainingItem> items = details.getItems();
    if (items == null || items.isEmpty()) {
      return "Остаток занятий: " + details.getTotal();
    }
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (MoyKlassClient.RemainingItem item : items) {
      String course = item.getCourseName() == null ? "Прочее" : item.getCourseName();
      String clazz = item.getClassName();
      String label = clazz == null || clazz.isBlank()
          ? course
          : course + " - " + clazz;
      if (!first) {
        sb.append("\n");
      }
      first = false;
      sb.append(label).append(": ").append(item.getRemaining());
    }
    return sb.toString();
  }

  private String resolveChildName(long maxUserId, long moyklassUserId) {
    return userChildRepository.findChild(maxUserId, moyklassUserId)
        .map(UserChildRepository.UserChild::getChildName)
        .filter(name -> name != null && !name.isBlank())
        .orElse("Ребенок " + moyklassUserId);
  }

  private String formatProgramLabel(String comment) {
    String value = comment == null ? "" : comment.trim();
    if (value.isBlank()) {
      return "Без программы";
    }
    return value;
  }

  private String formatProgramLabel(String courseName, String className) {
    String course = courseName == null ? "" : courseName.trim();
    String clazz = className == null ? "" : className.trim();
    if (course.isBlank() && clazz.isBlank()) {
      return "Без программы";
    }
    if (clazz.isBlank()) {
      return course;
    }
    if (course.isBlank()) {
      return clazz;
    }
    return course + " - " + clazz;
  }

  private String formatDirectionDative(String courseName) {
    String value = courseName == null ? "" : courseName.toLowerCase(Locale.ROOT);
    if (value.contains("англий")) {
      return "Английскому языку";
    }
    if (value.contains("творчеств")) {
      return "Творчеству";
    }
    return courseName == null || courseName.isBlank() ? "Без программы" : courseName;
  }

  private RemainingSnapshot findSubscriptionRemainingSnapshot(long moyklassUserId, String courseName, String className) {
    List<MoyKlassClient.SubscriptionRemaining> subs = moyKlassClient.listSubscriptionRemainings(moyklassUserId);
    if (subs.isEmpty()) {
      return RemainingSnapshot.notFound();
    }
    String courseNeed = courseName == null ? "" : courseName;
    int sumRemaining = 0;
    boolean found = false;
    for (MoyKlassClient.SubscriptionRemaining sub : subs) {
      if (sub == null) {
        continue;
      }
      String course = sub.getCourseName() == null ? "" : sub.getCourseName();
      if (!courseNeed.isBlank() && course.equalsIgnoreCase(courseNeed)) {
        found = true;
        sumRemaining += sub.getRemaining();
      }
    }
    if (found) {
      return RemainingSnapshot.found(sumRemaining, sumRemaining < 0);
    }
    String classNeed = className == null ? "" : className;
    if (classNeed.isBlank()) {
      return RemainingSnapshot.notFound();
    }
    sumRemaining = 0;
    for (MoyKlassClient.SubscriptionRemaining sub : subs) {
      if (sub == null) {
        continue;
      }
      String clazz = sub.getClassName() == null ? "" : sub.getClassName();
      if (clazz.equalsIgnoreCase(classNeed)) {
        found = true;
        sumRemaining += sub.getRemaining();
      }
    }
    return found ? RemainingSnapshot.found(sumRemaining, sumRemaining < 0) : RemainingSnapshot.notFound();
  }

  private boolean shouldSendRemainingAlert(RemainingState current, RemainingState previous) {
    if (current == null || !current.isValid()) {
      return false;
    }
    if (current.isDebt()) {
      return previous == null || !previous.isDebt();
    }
    if (current.getTotal() <= 1) {
      if (previous == null) {
        return true;
      }
      if (previous.isDebt()) {
        return true;
      }
      return previous.getTotal() != current.getTotal();
    }
    return false;
  }

  private String buildRemainingAlert(String childName, String directionLabel, RemainingState current) {
    if (current != null && current.isDebt()) {
      return "Здравствуйте!\n\nВаш ребенок (" + childName + ") посетил занятие по " + directionLabel + ".\n\n"
          + "Пожалуйста, не забудьте приобрести новый абонемент.";
    }
    int remaining = current == null ? 0 : current.getTotal();
    if (remaining <= 0) {
      return "Здравствуйте!\n\nУ вашего ребенка (" + childName + ")" +
          " не осталось оплаченных занятий по " + directionLabel + ".\n\n" +
          "Пожалуйста, не забудьте приобрести новый абонемент.";
    }
    if (remaining == 1) {
      return "Здравствуйте!\n\nУ вашего ребенка (" + childName + ")" +
          " осталось 1 оплаченное занятие по " + directionLabel + ".\n\n" +
          "Пожалуйста, не забудьте приобрести новый абонемент.";
    }
    return "Здравствуйте!\n\nУ вашего ребенка (" + childName + ")" +
        " осталось " + remaining + " оплаченных занятий по " + directionLabel + ".\n\n" +
        "Пожалуйста, не забудьте приобрести новый абонемент.";
  }

  private RemainingState buildRemainingState(RemainingSnapshot snapshot, int remainingFallback) {
    if (snapshot != null && snapshot.isFound()) {
      return RemainingState.found(snapshot.getTotal(), snapshot.hasDebt());
    }
    if (remainingFallback < 0) {
      return RemainingState.notFound();
    }
    return RemainingState.found(remainingFallback, remainingFallback < 0);
  }

  private String buildProgramKey(String courseName, String className) {
    String course = courseName == null ? "" : courseName.trim();
    if (!course.isBlank()) {
      return course;
    }
    String clazz = className == null ? "" : className.trim();
    if (!clazz.isBlank()) {
      return clazz;
    }
    return "Без программы";
  }

  private RemainingState readRemainingState(long moyklassUserId, String programKey) {
    if (moyklassUserId <= 0) {
      return null;
    }
    String key = remainingStateKey(moyklassUserId, programKey);
    return botStateRepository.get(key)
        .map(RemainingState::fromValue)
        .orElse(null);
  }

  private void writeRemainingState(long moyklassUserId, String programKey, RemainingState state) {
    if (moyklassUserId <= 0 || state == null || !state.isValid()) {
      return;
    }
    String key = remainingStateKey(moyklassUserId, programKey);
    botStateRepository.set(key, state.toValue());
  }

  private String remainingStateKey(long moyklassUserId, String programKey) {
    String safe = java.net.URLEncoder.encode(programKey == null ? "" : programKey,
        java.nio.charset.StandardCharsets.UTF_8);
    return "notify.remaining." + moyklassUserId + "." + safe;
  }

  private static final class RemainingState {
    private final int total;
    private final boolean debt;
    private final boolean valid;

    private RemainingState(int total, boolean debt, boolean valid) {
      this.total = total;
      this.debt = debt;
      this.valid = valid;
    }

    static RemainingState found(int total, boolean debt) {
      return new RemainingState(total, debt, true);
    }

    static RemainingState notFound() {
      return new RemainingState(0, false, false);
    }

    static RemainingState fromValue(String value) {
      if (value == null || value.isBlank()) {
        return null;
      }
      String[] parts = value.split("\\|", -1);
      if (parts.length < 2) {
        return null;
      }
      try {
        int total = Integer.parseInt(parts[0]);
        boolean debt = "1".equals(parts[1]) || "true".equalsIgnoreCase(parts[1]);
        return found(total, debt);
      } catch (NumberFormatException e) {
        return null;
      }
    }

    String toValue() {
      return total + "|" + (debt ? "1" : "0");
    }

    int getTotal() {
      return total;
    }

    boolean isDebt() {
      return debt;
    }

    boolean isValid() {
      return valid;
    }
  }

  private static final class RemainingSnapshot {
    private final int total;
    private final boolean hasDebt;
    private final boolean found;

    private RemainingSnapshot(int total, boolean hasDebt, boolean found) {
      this.total = total;
      this.hasDebt = hasDebt;
      this.found = found;
    }

    static RemainingSnapshot found(int total, boolean hasDebt) {
      return new RemainingSnapshot(total, hasDebt, true);
    }

    static RemainingSnapshot notFound() {
      return new RemainingSnapshot(0, false, false);
    }

    int getTotal() {
      return total;
    }

    boolean hasDebt() {
      return hasDebt;
    }

    boolean isFound() {
      return found;
    }
  }

  private String paidLessonPhrase(int remaining) {
    return remaining == 1 ? "оплаченное занятие" : "оплаченных занятий";
  }

  private String formatAmount(double amount) {
    if (Math.abs(amount - Math.rint(amount)) < 0.01) {
      return String.valueOf(Math.round(amount));
    }
    return String.format(Locale.US, "%.2f", amount).replace('.', ',');
  }

  private void handleUpdate(JsonNode update) {
    String updateType = update.path("update_type").asText("");
    switch (updateType) {
      case "message_created" -> handleMessageCreated(update.path("message"));
      case "message_callback" -> handleMessageCallback(update.path("callback"));
      case "bot_started" -> handleBotStarted(update);
      default -> log.debug("Skipping update type: {}", updateType);
    }
  }

  public synchronized int handleWebhookPayload(JsonNode payload) {
    if (payload == null || payload.isMissingNode() || payload.isNull()) {
      return 0;
    }
    int processed = 0;
    JsonNode updates = payload.path("updates");
    if (updates.isArray()) {
      for (JsonNode update : updates) {
        handleUpdate(update);
        processed++;
      }
      return processed;
    }
    if (payload.has("update_type")) {
      handleUpdate(payload);
      return 1;
    }
    JsonNode update = payload.path("update");
    if (!update.isMissingNode() && !update.isNull() && update.has("update_type")) {
      handleUpdate(update);
      return 1;
    }
    log.warn("Webhook payload format is not recognized: {}", payload);
    return 0;
  }

  private void handleBotStarted(JsonNode update) {
    JsonNode user = update.path("user");
    long userId = user.path("user_id").asLong(0);
    if (userId == 0) {
      return;
    }
    String name = user.path("name").asText("");
    String username = user.path("username").asText("");
    String firstName = name;
    String lastName = null;
    if (name.contains(" ")) {
      String[] parts = name.split(" ", 2);
      firstName = parts[0];
      lastName = parts[1];
    }
    userRepository.upsertUser(userId, firstName, lastName, username, Instant.now().toEpochMilli());
    sendWelcome(userId);
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

    if (isAdmin(senderId)) {
      boolean hasAdminDialog = getActiveAdminDialog().isPresent();
      boolean isEditingText = userStateRepository.getState(senderId)
          .map(state -> STATE_ADMIN_TEXT_EDIT.equals(state.getState()))
          .orElse(false);
      if (text.startsWith("/ask") || text.startsWith("/admin") || text.startsWith("/users")
          || text.startsWith("/add") || hasAdminDialog || isEditingText) {
        handleAdminMessage(senderId, text);
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

    if (payload.startsWith("admin:text:")) {
      if (isAdmin(userId)) {
        handleAdminTextCallback(userId, payload);
      }
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
    if (payload.startsWith("child:remove:")) {
      long childId = parseLongSafe(payload.substring("child:remove:".length()));
      if (childId > 0) {
        handleChildRemove(userId, childId);
      }
      return;
    }

    if (payload.startsWith("passes:")) {
      handlePassesPayload(userId, payload);
      return;
    }
    if (payload.startsWith("invoice:")) {
      handleInvoicePayload(userId, payload);
      return;
    }

    switch (payload) {
      case "action:signup" -> handleNewSignupRedirect(userId);
      case "action:children" -> showChildrenMenu(userId);
      case "action:add_child" -> startSignupPhoneFlow(userId);
      case "action:auth" -> startSignupPhoneFlow(userId);
      case "action:remove_child" -> showRemoveChildMenu(userId);
      case "action:link" -> startSignupPhoneFlow(userId);
      case "action:passes" -> promptPassesTarget(userId);
      case "action:invoice" -> promptInvoiceTarget(userId);
      case "action:menu" -> sendWelcome(userId);
      default -> log.debug("Unknown callback payload: {}", payload);
    }
  }

  private void handleAdminMessage(long adminId, String text) {
    UserStateRepository.UserState adminState = userStateRepository.getState(adminId)
        .orElse(null);
    if (adminState != null && STATE_ADMIN_TEXT_EDIT.equals(adminState.getState())) {
      handleAdminTextEdit(adminId, text);
      return;
    }

    if (text.startsWith("/admin")) {
      showAdminMenu(adminId);
      return;
    }

    if (text.startsWith("/users")) {
      handleAdminUsers(adminId, text);
      return;
    }

    if (text.startsWith("/add")) {
      handleAdminAdd(adminId, text);
      return;
    }

    if (text.startsWith("/ask")) {
      String[] parts = text.split("\\s+", 3);
      if (parts.length < 2) {
        sendAdminMessage(adminId, "Формат: /ask <номер телефона> [ФИО ребенка]");
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
          sendAdminMessage(adminId, lookup.getMessage());
          return;
        }
        userId = parseLongSafe(lookup.getData());
      } else {
        userId = parseLongSafe(target);
      }

      if (userId <= 0) {
        sendAdminMessage(adminId, "Не смог распознать номер телефона или user_id: " + parts[1]);
        return;
      }
      String intro = "";
      DialogRecord dialog = dialogService.startDialog(userId, adminId, intro);
      botStateRepository.set(STATE_ADMIN_DIALOG, String.valueOf(dialog.getId()));
      sendAdminMessageWithClose(adminId, "Диалог начат с пользователем " + userId + ".", dialog.getId());
      return;
    }

    Optional<Long> currentDialogId = botStateRepository.get(STATE_ADMIN_DIALOG)
        .map(this::parseLongSafe)
        .filter(id -> id > 0);

    if (currentDialogId.isEmpty()) {
      sendAdminMessage(adminId, "Нет активного диалога. Используйте /ask <user_id>.");
      return;
    }

    DialogRecord dialog = dialogRepository.findById(currentDialogId.get()).orElse(null);
    if (dialog == null || !dialog.isActive()) {
      sendAdminMessage(adminId, "Диалог уже завершен. Используйте /ask <user_id>.");
      botStateRepository.delete(STATE_ADMIN_DIALOG);
      return;
    }

    dialogService.forwardAdminMessage(dialog, text);
  }

  private void handleAdminUsers(long adminId, String text) {
    int pageSize = 20;
    int page = 1;
    String[] parts = text == null ? new String[0] : text.trim().split("\\s+");
    if (parts.length >= 2) {
      try {
        page = Integer.parseInt(parts[1].trim());
      } catch (NumberFormatException e) {
        sendAdminMessage(adminId, "Формат: /users [номер страницы]");
        return;
      }
    }
    if (page < 1) {
      sendAdminMessage(adminId, "Номер страницы должен быть >= 1.");
      return;
    }

    int total = userRepository.countUsers();
    if (total <= 0) {
      sendAdminMessage(adminId, "Пользователей пока нет.");
      return;
    }
    int totalPages = (total + pageSize - 1) / pageSize;
    if (page > totalPages) {
      sendAdminMessage(adminId, "Страница вне диапазона. Доступно страниц: " + totalPages + ".");
      return;
    }

    int offset = (page - 1) * pageSize;
    List<UserRecord> users = userRepository.listUsersPage(offset, pageSize);

    StringBuilder out = new StringBuilder();
    out.append("Пользователи (стр ")
        .append(page)
        .append("/")
        .append(totalPages)
        .append(", всего ")
        .append(total)
        .append("):");

    for (UserRecord user : users) {
      out.append("\n").append(formatUserLine(user));
    }

    if (page < totalPages) {
      out.append("\n\nСледующая страница: /users ").append(page + 1);
    }
    if (page > 1) {
      out.append("\nПредыдущая страница: /users ").append(page - 1);
    }

    sendAdminMessage(adminId, out.toString());
  }

  private void handleAdminAdd(long adminId, String text) {
    String[] parts = text == null ? new String[0] : text.trim().split("\\s+");
    if (parts.length < 2) {
      sendAdminMessage(adminId, "Формат: /add <user_id>");
      return;
    }
    long userId = parseLongSafe(parts[1]);
    if (userId <= 0) {
      sendAdminMessage(adminId, "Не смог распознать user_id: " + parts[1]);
      return;
    }
    adminUserRepository.addAdmin(userId, Instant.now().toEpochMilli());
    sendAdminMessage(adminId, "Админ добавлен: " + userId);
  }

  private void handleAdminTextCallback(long adminId, String payload) {
    if ("admin:text:menu".equals(payload)) {
      showAdminTextMenu(adminId);
      return;
    }
    if ("admin:text:back".equals(payload)) {
      showAdminMenu(adminId);
      return;
    }
    if (payload.startsWith("admin:text:set:")) {
      String key = payload.substring("admin:text:set:".length());
      AdminTextOption option = findAdminTextOption(key);
      if (option == null) {
        sendAdminMessage(adminId, "Неизвестный раздел для изменения текста.");
        return;
      }
      userStateRepository.setState(
          adminId,
          STATE_ADMIN_TEXT_EDIT,
          key,
          Instant.now().toEpochMilli()
      );
      sendAdminMessage(adminId, "Введите новый текст для: " + option.label);
      return;
    }
  }

  private void handleAdminTextEdit(long adminId, String text) {
    String newText = text == null ? "" : text.trim();
    UserStateRepository.UserState adminState = userStateRepository.getState(adminId)
        .orElse(null);
    if (adminState == null || adminState.getData() == null || adminState.getData().isBlank()) {
      userStateRepository.clearState(adminId);
      sendAdminMessage(adminId, "Не удалось определить раздел. Попробуйте снова.");
      return;
    }
    if (newText.isBlank()) {
      sendAdminMessage(adminId, "Текст не может быть пустым. Введите новый текст.");
      return;
    }
    String key = adminState.getData();
    botTextRepository.upsertText(key, newText, Instant.now().toEpochMilli());
    userStateRepository.clearState(adminId);
    sendAdminMessage(adminId, "Текст обновлен.");
    showAdminMenu(adminId);
  }

  private void showAdminMenu(long adminId) {
    String url = properties.getAdmin().getPanelUrl();
    if (url == null || url.isBlank()) {
      url = "http://<ваш-домен>/admin/index.html";
    }
    sendAdminMessage(adminId, "Добро пожаловать в Админ-панель"
        + "\nСписок пользователей: /users [страница]"
        + "\nДобавить админа: /add <user_id>",
        buildAdminMenuAttachments());
  }

  private void showAdminTextMenu(long adminId) {
    sendAdminMessage(adminId, "Выберите где изменить текст", buildAdminTextMenuAttachments());
  }

  private AdminTextOption findAdminTextOption(String key) {
    for (AdminTextOption option : adminTextOptions()) {
      if (option.key.equals(key)) {
        return option;
      }
    }
    return null;
  }

  private List<AdminTextOption> adminTextOptions() {
    return List.of(
        new AdminTextOption(TEXT_WELCOME, "В стартовом меню"),
        new AdminTextOption(TEXT_SIGNUP_REDIRECT, "В Записаться"),
        new AdminTextOption(TEXT_AUTH_PROMPT, "В Авторизоваться"),
        new AdminTextOption(TEXT_LINK_PROMPT, "После записи"),
        new AdminTextOption(TEXT_FIRST_AUTH_NOTICE, "Первое уведомление")
    );
  }

  private List<Map<String, Object>> buildAdminMenuAttachments() {
    List<List<Map<String, Object>>> rows = new java.util.ArrayList<>();
    rows.add(List.of(callbackButton("Изменить текст", "admin:text:menu")));
    rows.add(List.of(callbackButton("В меню", "action:menu")));
    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  private List<Map<String, Object>> buildAdminTextMenuAttachments() {
    List<List<Map<String, Object>>> rows = new java.util.ArrayList<>();
    for (AdminTextOption option : adminTextOptions()) {
      rows.add(List.of(callbackButton(option.label, "admin:text:set:" + option.key)));
    }
    rows.add(List.of(callbackButton("Назад", "admin:text:back")));
    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
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
      handleNewSignupRedirect(userId);
      return;
    }

    if (text.equalsIgnoreCase("Авторизоваться") || text.contains("Авториз")) {
      startSignupPhoneFlow(userId);
      return;
    }

    if (text.equalsIgnoreCase("Абонементы") || text.contains("Абон")) {
      promptPassesTarget(userId);
      return;
    }

    if (text.equalsIgnoreCase("Счет на оплату") || text.contains("Счет")) {
      promptInvoiceTarget(userId);
      return;
    }

    sendWelcome(userId);
  }

  private void sendWelcome(long userId) {
    String text = getText(TEXT_WELCOME, "Здравствуйте. \nВыберите действие.");
    sendMainMenuMessage(userId, text);
  }

  private void handleSignup(long userId) {
    MoyKlassResult result = moyKlassClient.createLead(userId, "Запись из MAX", null);
    String response = formatSignupResponse(result);
    sendMenuMessage(userId, response);
  }

  private void promptSignupChoice(long userId, boolean ignoreExistingProfile) {
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
    String text = getText(TEXT_SIGNUP_REDIRECT,
        "Для записи на занятия по английскому языку/творчеству перейдите по ссылке\n(кнопка «Записаться»)");
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
    String text = getText(TEXT_LINK_PROMPT, "После успешной записи необходимо авторизоваться");
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
    sendUserMessage(userId, getText(TEXT_AUTH_PROMPT,
        "Введите номер телефона, который использовали при записи ребенка\n(Только цифры)"));
  }

  private void handleSignupPhoneExisting(long userId, String text) {
    int beforeCount = userChildRepository.listChildren(userId).size();
    MoyKlassResult result = moyKlassClient.linkByPhone(userId, text);
    if (result.isSuccess()) {
      userStateRepository.clearState(userId);
      rememberLinkedChildren(userId, result);
      sendMenuMessage(userId, "Нашли ваши данные. Теперь можно пользоваться ботом.");
      sendAuthNoticeIfNewChildren(userId, beforeCount);
      return;
    }
    String message = result.getMessage() + " Если вы новый клиент, нажмите \"Записаться\".";
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
    int beforeCount = userChildRepository.listChildren(userId).size();
    MoyKlassResult result = moyKlassClient.linkByPhoneAndName(userId, phone, childName);
    if (result.isSuccess()) {
      userStateRepository.clearState(userId);
      rememberLinkedChildren(userId, result);
      sendMenuMessage(userId, "Нашли ваши данные. Теперь можно пользоваться ботом.");
      sendAuthNoticeIfNewChildren(userId, beforeCount);
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
    String response = formatRemainingResponse(result);
    sendMenuMessage(userId, response);
  }

  private void handleInvoice(long userId) {
    MoyKlassResult result = moyKlassClient.createInvoice(userId);
    String response = formatInvoiceResponse(result);
    sendMenuMessage(userId, response);
  }

  private void sendAdminMessage(long adminId, String text) {
    if (adminId <= 0) {
      return;
    }
    sendUserMessage(adminId, text);
  }

  private void sendAdminMessage(long adminId, String text, List<Map<String, Object>> attachments) {
    if (adminId <= 0) {
      return;
    }
    sendUserMessageWithAttachments(adminId, text, attachments);
  }

  private void sendAdminMessageWithClose(long adminId, String text, long dialogId) {
    if (adminId <= 0) {
      return;
    }
    try {
      maxApiClient.sendMessageToUser(adminId, Map.of(
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

  private void promptPassesTarget(long userId) {
    List<UserChildRepository.UserChild> children = ensureChildrenLoaded(userId);
    if (children.isEmpty()) {
      sendUserMessageWithAttachments(
          userId,
          "Для получени информации нужно авторизоваться",
          keyboardFactory.linkAccountAttachments()
      );
      return;
    }
    if (children.size() == 1) {
      handlePassesForChild(userId, children.get(0).getMoyklassUserId());
      return;
    }
    sendUserMessageWithAttachments(userId, "Для кого вывести информацию?", buildTargetAttachments(children, "passes"));
  }

  private void promptInvoiceTarget(long userId) {
    List<UserChildRepository.UserChild> children = ensureChildrenLoaded(userId);
    if (children.isEmpty()) {
      sendUserMessageWithAttachments(
          userId,
          "Для получени информации нужно авторизоваться",
          keyboardFactory.linkAccountAttachments()
      );
      return;
    }
    if (children.size() == 1) {
      handleInvoiceForChild(userId, children.get(0).getMoyklassUserId());
      return;
    }
    sendUserMessageWithAttachments(userId, "Для кого вывести информацию?", buildTargetAttachments(children, "invoice"));
  }

  private void handlePassesPayload(long userId, String payload) {
    if ("passes:all".equals(payload)) {
      handlePassesForAll(userId);
      return;
    }
    if (payload.startsWith("passes:child:")) {
      long childId = parseLongSafe(payload.substring("passes:child:".length()));
      if (childId > 0) {
        handlePassesForChild(userId, childId);
      }
    }
  }

  private void handleInvoicePayload(long userId, String payload) {
    if ("invoice:all".equals(payload)) {
      handleInvoiceForAll(userId);
      return;
    }
    if (payload.startsWith("invoice:child:")) {
      long childId = parseLongSafe(payload.substring("invoice:child:".length()));
      if (childId > 0) {
        handleInvoiceForChild(userId, childId);
      }
    }
  }

  private void handlePassesForChild(long userId, long childId) {
    Optional<UserChildRepository.UserChild> childOpt = userChildRepository.findChild(userId, childId);
    if (childOpt.isEmpty()) {
      sendUserMessage(userId, "Не удалось найти выбранного ребенка. Попробуйте снова.");
      return;
    }
    String name = childOpt.get().getChildName();
    MoyKlassClient.RemainingDetails details = moyKlassClient.getRemainingDetailsByMoyklassUserId(childId);
    String response = formatRemainingDetails(details);
    String message = (name == null || name.isBlank())
        ? response
        : "Ребенок: " + name + "\n" + response;
    sendMenuMessage(userId, message);
  }

  private void handleInvoiceForChild(long userId, long childId) {
    Optional<UserChildRepository.UserChild> childOpt = userChildRepository.findChild(userId, childId);
    if (childOpt.isEmpty()) {
      sendUserMessage(userId, "Не удалось найти выбранного ребенка. Попробуйте снова.");
      return;
    }
    MoyKlassResult result = moyKlassClient.createInvoiceByMoyklassUserId(childId);
    String name = childOpt.get().getChildName();
    String response = formatInvoiceResponse(result);
    String message = (name == null || name.isBlank())
        ? response
        : "Ребенок: " + name + "\n" + response;
    sendMenuMessage(userId, message);
  }

  private void handlePassesForAll(long userId) {
    List<UserChildRepository.UserChild> children = ensureChildrenLoaded(userId);
    if (children.isEmpty()) {
      sendUserMessageWithAttachments(
          userId,
          "Для получени информации нужно авторизоваться",
          keyboardFactory.linkAccountAttachments()
      );
      return;
    }
    StringBuilder sb = new StringBuilder("📚 Остаток занятий (для всех):");
    boolean first = true;
    for (UserChildRepository.UserChild child : children) {
      String name = child.getChildName() == null || child.getChildName().isBlank()
          ? "Ребенок " + child.getMoyklassUserId()
          : child.getChildName();
      MoyKlassClient.RemainingDetails details = moyKlassClient.getRemainingDetailsByMoyklassUserId(child.getMoyklassUserId());
      if (!first) {
        sb.append("\n");
      }
      first = false;
      sb.append("\n").append(name).append(":\n").append(formatRemainingDetails(details));
    }
    sendMenuMessage(userId, sb.toString());
  }

  private void handleInvoiceForAll(long userId) {
    List<UserChildRepository.UserChild> children = ensureChildrenLoaded(userId);
    if (children.isEmpty()) {
      sendUserMessageWithAttachments(
          userId,
          "Для получени информации нужно авторизоваться",
          keyboardFactory.linkAccountAttachments()
      );
      return;
    }
    StringBuilder sb = new StringBuilder("Счета на оплату (для всех):");
    boolean first = true;
    for (UserChildRepository.UserChild child : children) {
      MoyKlassResult result = moyKlassClient.createInvoiceByMoyklassUserId(child.getMoyklassUserId());
      String name = child.getChildName() == null || child.getChildName().isBlank()
          ? "Ребенок " + child.getMoyklassUserId()
          : child.getChildName();
      if (!first) {
        sb.append("\n");
      }
      first = false;
      sb.append("\n").append(name).append(": ").append(formatInvoiceResponse(result));
    }
    sendMenuMessage(userId, sb.toString());
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

    sendUserMessageWithAttachments(userId, buildChildrenListText(children), buildChildrenAttachments(children));
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

  private void showRemoveChildMenu(long userId) {
    List<UserChildRepository.UserChild> children = ensureChildrenLoaded(userId);
    if (children.isEmpty()) {
      sendUserMessageWithAttachments(
          userId,
          "Пока нет связанных детей.",
          keyboardFactory.linkAccountAttachments()
      );
      return;
    }
    sendUserMessageWithAttachments(
        userId,
        "Выберите, какого ребенка нужно отвязать от учетной записи",
        buildRemoveChildrenAttachments(children)
    );
  }

  private void handleChildRemove(long userId, long childId) {
    Optional<UserChildRepository.UserChild> childOpt = userChildRepository.findChild(userId, childId);
    if (childOpt.isEmpty()) {
      sendUserMessage(userId, "Не удалось найти выбранного ребенка. Попробуйте снова.");
      return;
    }

    UserChildRepository.UserChild child = childOpt.get();
    userChildRepository.deleteChild(userId, childId);

    Optional<UserRecord> userOpt = userRepository.findByMaxUserId(userId);
    Long currentId = userOpt.map(UserRecord::getMoyklassUserId).orElse(null);
    List<UserChildRepository.UserChild> remaining = userChildRepository.listChildren(userId);
    if (remaining.isEmpty()) {
      userRepository.clearMoyklassUserId(userId);
    } else if (currentId != null && currentId == childId) {
      userRepository.setMoyklassUserId(userId, remaining.get(0).getMoyklassUserId());
    }

    String name = child.getChildName() == null || child.getChildName().isBlank()
        ? "Ребенок " + child.getMoyklassUserId()
        : child.getChildName();
    if (remaining.isEmpty()) {
      sendMainMenuMessage(userId, "Ребенок \"" + name + "\" отвязан. Связанных детей больше нет.");
      return;
    }

    sendUserMessageWithAttachments(
        userId,
        "Ребенок \"" + name + "\" отвязан.\n\n" + buildChildrenListText(remaining),
        buildChildrenAttachments(remaining)
    );
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

  private boolean isAdmin(long userId) {
    if (userId <= 0) {
      return false;
    }
    if (userId == properties.getMax().getAdminUserIdAsLong()) {
      return true;
    }
    String raw = properties.getMax().getAdminUserId();
    if (raw != null && !raw.isBlank()) {
      String[] parts = raw.split("[,\\s]+");
      for (String part : parts) {
        if (part == null || part.isBlank()) {
          continue;
        }
        try {
          if (Long.parseLong(part.trim()) == userId) {
            return true;
          }
        } catch (Exception e) {
          // ignore invalid ids
        }
      }
    }
    return adminUserRepository.isAdmin(userId);
  }

  private long parseLongSafe(String value) {
    try {
      return Long.parseLong(value);
    } catch (Exception e) {
      return -1L;
    }
  }

  private String formatUserLine(UserRecord user) {
    if (user == null) {
      return "Без имени — id ?";
    }
    String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
    String last = user.getLastName() == null ? "" : user.getLastName().trim();
    String name;
    if (!first.isBlank() && !last.isBlank()) {
      name = first + " " + last;
    } else if (!first.isBlank()) {
      name = first;
    } else if (!last.isBlank()) {
      name = last;
    } else {
      name = "Без имени";
    }
    String username = user.getUsername() == null ? "" : user.getUsername().trim();
    if (!username.isBlank()) {
      name = name + " (@" + username + ")";
    }
    return name + " — id " + user.getMaxUserId();
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

  private void rememberLinkedChildren(long userId, MoyKlassResult result) {
    if (result == null || !result.isSuccess()) {
      return;
    }
    List<MoyKlassClient.MoyKlassUser> users = parseLinkedUsers(result.getData());
    if (users.isEmpty()) {
      long moyklassUserId = parseLongSafe(result.getData());
      if (moyklassUserId > 0) {
        rememberChild(userId, moyklassUserId, null);
      }
      return;
    }
    for (MoyKlassClient.MoyKlassUser user : users) {
      rememberChild(userId, user.getId(), user.getName());
    }
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
    ensurePaymentBaselineForUser(moyklassUserId);
  }

  private String buildChildrenListText(List<UserChildRepository.UserChild> children) {
    StringBuilder sb = new StringBuilder("Связанные дети:");
    for (UserChildRepository.UserChild child : children) {
      String name = child.getChildName() == null || child.getChildName().isBlank()
          ? "Ребенок " + child.getMoyklassUserId()
          : child.getChildName();
      sb.append("\n• ").append(name);
    }
    return sb.toString();
  }

  private List<Map<String, Object>> buildChildrenAttachments(List<UserChildRepository.UserChild> children) {
    List<List<Map<String, Object>>> rows = new java.util.ArrayList<>();
    rows.add(List.of(callbackButton("➕ Добавить ребенка", "action:add_child")));
    rows.add(List.of(callbackButton("- Удалить ребенка", "action:remove_child")));
    rows.add(List.of(callbackButton("🏠 В меню", "action:menu")));
    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  private List<Map<String, Object>> buildRemoveChildrenAttachments(List<UserChildRepository.UserChild> children) {
    List<List<Map<String, Object>>> rows = new java.util.ArrayList<>();
    for (UserChildRepository.UserChild child : children) {
      String label = child.getChildName() == null || child.getChildName().isBlank()
          ? "Ребенок " + child.getMoyklassUserId()
          : child.getChildName();
      rows.add(List.of(callbackButton(label, "child:remove:" + child.getMoyklassUserId())));
    }
    rows.add(List.of(callbackButton("🏠 В меню", "action:menu")));
    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  private static class AdminTextOption {
    private final String key;
    private final String label;

    private AdminTextOption(String key, String label) {
      this.key = key;
      this.label = label;
    }
  }

  private List<Map<String, Object>> buildTargetAttachments(List<UserChildRepository.UserChild> children, String prefix) {
    List<List<Map<String, Object>>> rows = new java.util.ArrayList<>();
    for (UserChildRepository.UserChild child : children) {
      String label = child.getChildName() == null || child.getChildName().isBlank()
          ? "Ребенок " + child.getMoyklassUserId()
          : child.getChildName();
      rows.add(List.of(callbackButton(label, prefix + ":child:" + child.getMoyklassUserId())));
    }
    if (children.size() > 1) {
      rows.add(List.of(callbackButton("Для всех", prefix + ":all")));
    }
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

  private String getText(String key, String fallback) {
    return botTextRepository.findText(key)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .orElse(fallback);
  }

  private void sendAuthNoticeIfNewChildren(long userId, int beforeCount) {
    int afterCount = userChildRepository.listChildren(userId).size();
    if (afterCount <= beforeCount) {
      return;
    }
    String text = getText(TEXT_FIRST_AUTH_NOTICE, "Здесь текст для первого уведомления");
    if (text != null && !text.isBlank()) {
      sendUserMessage(userId, text);
    }
  }

  private String safeText(String text) {
    if (text == null) {
      return null;
    }
    String value = text.trim();
    return value.isBlank() ? null : value;
  }

  private String formatRemainingResponse(MoyKlassResult result) {
    if (result == null) {
      return "Не удалось получить данные.";
    }
    if (!result.isSuccess()) {
      return result.getMessage();
    }
    return result.getData() == null ? result.getMessage() : "Осталось занятий: " + result.getData();
  }

  private String formatRemainingDetails(MoyKlassClient.RemainingDetails details) {
    if (details == null) {
      return "Не удалось получить данные.";
    }
    List<MoyKlassClient.RemainingItem> items = details.getItems();
    if (items == null || items.isEmpty()) {
      return "Остаток занятий: " + details.getTotal();
    }
    StringBuilder sb = new StringBuilder("📚 Остаток занятий:");
    for (MoyKlassClient.RemainingItem item : items) {
      String course = item.getCourseName() == null ? "Прочее" : item.getCourseName();
      String className = item.getClassName();
      String label = className == null || className.isBlank()
          ? course
          : course + " — " + className;
      sb.append("\n").append(emojiForCourse(course)).append(" ").append(label).append(": ").append(item.getRemaining());
    }
    return sb.toString();
  }

  private String emojiForCourse(String course) {
    if (course == null) {
      return "📚";
    }
    String normalized = course.toLowerCase();
    if (normalized.contains("англий")) {
      return "🇬🇧";
    }
    if (normalized.contains("твор")) {
      return "🎨";
    }
    return "📚";
  }

  private String formatInvoiceResponse(MoyKlassResult result) {
    if (result == null) {
      return "Не удалось получить счет.";
    }
    if (!result.isSuccess()) {
      return result.getMessage();
    }
    return result.getData() == null ? result.getMessage() : "Счет сформирован: " + result.getData();
  }

  private List<MoyKlassClient.MoyKlassUser> parseLinkedUsers(String data) {
    if (data == null || data.isBlank()) {
      return List.of();
    }
    try {
      JsonNode node = objectMapper.readTree(data);
      if (node != null && node.isArray()) {
        List<MoyKlassClient.MoyKlassUser> result = new java.util.ArrayList<>();
        for (JsonNode item : node) {
          long id = item.path("id").asLong(0);
          if (id <= 0) {
            continue;
          }
          String name = item.path("name").asText(null);
          result.add(new MoyKlassClient.MoyKlassUser(id, name, null));
        }
        return result;
      }
      return List.of();
    } catch (Exception e) {
      return List.of();
    }
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
