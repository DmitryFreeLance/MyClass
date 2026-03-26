package com.myclass.maxbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KeyboardFactory {
  public List<Map<String, Object>> mainMenuAttachments() {
    List<List<Map<String, Object>>> rows = new ArrayList<>();
    rows.add(List.of(button("callback", "📝 Записаться", "action:signup")));
    rows.add(List.of(button("callback", "🎟️ Абонементы", "action:passes")));
    rows.add(List.of(button("callback", "💳 Счет на оплату", "action:invoice")));

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
        button("callback", "✅ Да, уже были оплаты", "signup:existing_yes"),
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

  private Map<String, Object> button(String type, String text, String payload) {
    return Map.of(
        "type", type,
        "text", text,
        "payload", payload
    );
  }
}
