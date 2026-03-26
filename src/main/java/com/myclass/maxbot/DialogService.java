package com.myclass.maxbot;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DialogService {
  private static final Logger log = LoggerFactory.getLogger(DialogService.class);

  private final DialogRepository dialogRepository;
  private final MessageRepository messageRepository;
  private final MaxApiClient maxApiClient;
  private final KeyboardFactory keyboardFactory;

  public DialogService(
      DialogRepository dialogRepository,
      MessageRepository messageRepository,
      MaxApiClient maxApiClient,
      KeyboardFactory keyboardFactory
  ) {
    this.dialogRepository = dialogRepository;
    this.messageRepository = messageRepository;
    this.maxApiClient = maxApiClient;
    this.keyboardFactory = keyboardFactory;
  }

  public DialogRecord startDialog(long userId, long adminId, String introMessage) {
    DialogRecord dialog = dialogRepository.findActiveByUserId(userId)
        .orElseGet(() -> dialogRepository.create(userId, adminId, now()));

    if (introMessage != null && !introMessage.isBlank()) {
      sendMessageToUser(userId, introMessage);
    } else {
      sendMessageToUser(userId, "Администратор подключился. Напишите, чем можем помочь.");
    }

    return dialog;
  }

  public void closeDialog(long dialogId, String reason) {
    DialogRecord dialog = dialogRepository.findById(dialogId).orElse(null);
    if (dialog == null || !dialog.isActive()) {
      return;
    }

    dialogRepository.close(dialogId, now());
    sendMessageToUser(dialog.getUserId(), "Диалог завершен. Спасибо за обращение!");
    sendMessageToAdmin(dialog.getAdminId(), "Диалог завершен" + (reason == null ? "." : ": " + reason));
  }

  public void forwardUserMessage(DialogRecord dialog, String text) {
    sendMessageToAdmin(
        dialog.getAdminId(),
        "Пользователь " + dialog.getUserId() + ":\n" + text,
        dialog.getId()
    );
    messageRepository.insert(dialog.getId(), "user", text, now());
  }

  public void forwardAdminMessage(DialogRecord dialog, String text) {
    sendMessageToUser(dialog.getUserId(), text);
    sendMessageToAdmin(
        dialog.getAdminId(),
        "Вы -> " + dialog.getUserId() + ":\n" + text,
        dialog.getId()
    );
    messageRepository.insert(dialog.getId(), "admin", text, now());
  }

  private void sendMessageToUser(long userId, String text) {
    try {
      maxApiClient.sendMessageToUser(userId, Map.of("text", text));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      log.warn("Failed to send message to user {}: {}", userId, e.getMessage());
    }
  }

  private void sendMessageToAdmin(long adminId, String text) {
    try {
      maxApiClient.sendMessageToUser(adminId, Map.of("text", text));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      log.warn("Failed to send message to admin {}: {}", adminId, e.getMessage());
    }
  }

  private void sendMessageToAdmin(long adminId, String text, long dialogId) {
    try {
      maxApiClient.sendMessageToUser(adminId, Map.of(
          "text", text,
          "attachments", keyboardFactory.closeDialogAttachments(dialogId)
      ));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      log.warn("Failed to send message to admin {}: {}", adminId, e.getMessage());
    }
  }

  private long now() {
    return Instant.now().toEpochMilli();
  }
}
