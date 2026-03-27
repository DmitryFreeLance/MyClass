package com.myclass.maxbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KeyboardFactory {
  public List<Map<String, Object>> mainMenuAttachments(boolean linked) {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    if (linked) {
      rows.add(List.of(button("callback", "👨‍👩‍👧 Мои дети", "action:children")));
    } else {
      rows.add(List.of(button("callback", "📝 Записаться", "action:signup")));
    }
    rows.add(List.of(button("callback", "🎟️ Абонементы", "action:passes")));
    rows.add(List.of(button("callback", "💳 Счет на оплату", "action:invoice")));
    rows.add(List.of(linkButton("💬 Написать в чат", "https://max.ru/u/f9LHodD0cOI1bQhXnFdFq9sJL6NGD_9AD2zjkNxHcHNh0Om0GOo-RQYznQE")));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> menuOnlyAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(button("callback", "🏠 В меню", "action:menu")));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> closeDialogAttachments(long dialogId) {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(button("callback", "Завершить диалог", "close_dialog:" + dialogId)));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> signupChoiceAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(
        button("callback", "✅ Да, уже зарегистрирован(а)", "signup:existing_yes"),
        button("callback", "🆕 Нет, я новый", "signup:existing_no")
    ));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> signupMenuAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(button("callback", "📝 Записаться", "action:signup")));
    rows.add(List.of(button("callback", "🏠 В меню", "action:menu")));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> linkAccountAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(button("callback", "Связать", "action:link")));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  public List<Map<String, Object>> scheduleLinkAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(linkButton("Зарегистрироваться", "https://дкразвитие.рф/schedule.html")));

    return List.of(Map.of(
        "type", "inline_keyboard",
        "payload", Map.of("buttons", rows)
    ));
  }

  private Map<String, Object> button(String type, String text, String payload) {
    return Map.of(
        "type", type,
        "text", text,
        "payload", payload
    );
  }

  private Map<String, Object> linkButton(String text, String url) {
    return Map.of(
        "type", "link",
        "text", text,
        "url", url
    );
  }
}
