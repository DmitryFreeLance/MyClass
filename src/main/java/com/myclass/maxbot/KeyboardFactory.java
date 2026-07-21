package com.myclass.maxbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KeyboardFactory {
  private final BotProperties properties;

  public KeyboardFactory(BotProperties properties) {
    this.properties = properties;
  }

  public List<Map<String, Object>> mainMenuAttachments(boolean linked) {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(messageButton("📝 Записаться")));
    rows.add(List.of(messageButton("🔐 Авторизоваться")));
    rows.add(List.of(messageButton("🎟️ Абонементы")));
    rows.add(List.of(messageButton("💳 Счет на оплату")));
    rows.add(List.of(linkButton("💬 Задать вопрос", contactUrl())));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> menuOnlyAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(messageButton("🏠 В меню")));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> signupChoiceAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(
        messageButton("✅ Да, уже зарегистрирован(а)"),
        messageButton("🆕 Нет, я новый")
    ));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> signupMenuAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(messageButton("📝 Записаться")));
    rows.add(List.of(messageButton("🏠 В меню")));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> linkAccountAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(messageButton("Авторизоваться")));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> scheduleLinkAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(linkButton("Записаться", registrationUrl())));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  private Map<String, Object> messageButton(String text) {
    return Map.of(
        "type", "message",
        "text", text
    );
  }

  private Map<String, Object> linkButton(String text, String url) {
    return Map.of(
        "type", "link",
        "text", text,
        "url", url
    );
  }

  private String registrationUrl() {
    String value = properties.getSite() == null ? null : properties.getSite().getRegistrationUrl();
    return value == null || value.isBlank() ? "https://roboacademiya.ru/" : value;
  }

  private String contactUrl() {
    String value = properties.getSite() == null ? null : properties.getSite().getContactUrl();
    return value == null || value.isBlank() ? "https://max.ru/id246516134480_2_bot" : value;
  }
}
