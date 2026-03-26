package com.myclass.maxbot;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api")
public class AdminApiController {
  private final BotProperties properties;
  private final DialogRepository dialogRepository;
  private final DialogService dialogService;
  private final UserRepository userRepository;
  private final BotStateRepository botStateRepository;

  public AdminApiController(
      BotProperties properties,
      DialogRepository dialogRepository,
      DialogService dialogService,
      UserRepository userRepository,
      BotStateRepository botStateRepository
  ) {
    this.properties = properties;
    this.dialogRepository = dialogRepository;
    this.dialogService = dialogService;
    this.userRepository = userRepository;
    this.botStateRepository = botStateRepository;
  }

  @GetMapping("/dialogs")
  public ResponseEntity<?> listDialogs(HttpServletRequest request) {
    if (!isAuthorized(request)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
    }

    List<DialogView> dialogs = dialogRepository.listActive().stream()
        .map(dialog -> {
          Optional<UserRecord> user = userRepository.findByMaxUserId(dialog.getUserId());
          return new DialogView(
              dialog.getId(),
              dialog.getUserId(),
              dialog.getAdminId(),
              dialog.getStartedAt(),
              user.map(UserRecord::getFirstName).orElse(""),
              user.map(UserRecord::getLastName).orElse(""),
              user.map(UserRecord::getUsername).orElse("")
          );
        })
        .collect(Collectors.toList());

    return ResponseEntity.ok(dialogs);
  }

  @PostMapping("/dialogs/start")
  public ResponseEntity<?> startDialog(HttpServletRequest request, @RequestBody StartDialogRequest payload) {
    if (!isAuthorized(request)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
    }

    if (payload == null || payload.userId <= 0) {
      return ResponseEntity.badRequest().body("userId required");
    }

    DialogRecord dialog = dialogService.startDialog(
        payload.userId,
        properties.getMax().getAdminUserId(),
        payload.message
    );
    botStateRepository.set("admin.current_dialog_id", String.valueOf(dialog.getId()));

    return ResponseEntity.ok(new DialogView(dialog.getId(), dialog.getUserId(), dialog.getAdminId(),
        dialog.getStartedAt(), "", "", ""));
  }

  @PostMapping("/dialogs/close")
  public ResponseEntity<?> closeDialog(HttpServletRequest request, @RequestBody CloseDialogRequest payload) {
    if (!isAuthorized(request)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
    }

    if (payload == null || payload.dialogId <= 0) {
      return ResponseEntity.badRequest().body("dialogId required");
    }

    dialogService.closeDialog(payload.dialogId, "закрыто из панели");
    botStateRepository.delete("admin.current_dialog_id");
    return ResponseEntity.ok("ok");
  }

  private boolean isAuthorized(HttpServletRequest request) {
    String token = request.getHeader("X-Admin-Token");
    if (token == null || token.isBlank()) {
      token = request.getParameter("token");
    }
    String expected = properties.getAdmin().getPanelToken();
    return expected != null && !expected.isBlank() && expected.equals(token);
  }

  public static class StartDialogRequest {
    public long userId;
    public String message;
  }

  public static class CloseDialogRequest {
    public long dialogId;
  }

  public static class DialogView {
    public long id;
    public long userId;
    public long adminId;
    public long startedAt;
    public String firstName;
    public String lastName;
    public String username;

    public DialogView(long id, long userId, long adminId, long startedAt, String firstName, String lastName,
                      String username) {
      this.id = id;
      this.userId = userId;
      this.adminId = adminId;
      this.startedAt = startedAt;
      this.firstName = firstName;
      this.lastName = lastName;
      this.username = username;
    }
  }
}
