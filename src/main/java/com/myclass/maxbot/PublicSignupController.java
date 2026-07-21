package com.myclass.maxbot;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/signup")
public class PublicSignupController {
  private final MoyKlassClient moyKlassClient;
  private final BotProperties properties;

  public PublicSignupController(MoyKlassClient moyKlassClient, BotProperties properties) {
    this.moyKlassClient = moyKlassClient;
    this.properties = properties;
  }

  @GetMapping("/options")
  public ResponseEntity<SignupOptionsResponse> options() {
    List<FilialView> filials = moyKlassClient.listFilials().stream()
        .filter(this::isActiveFilial)
        .map(filial -> new FilialView(filial.getId(), formatFilialName(filial)))
        .sorted(Comparator.comparing(FilialView::name, String.CASE_INSENSITIVE_ORDER))
        .toList();

    List<CourseView> courses = moyKlassClient.listCourses().stream()
        .filter(course -> course.getId() > 0)
        .map(course -> new CourseView(course.getId(), course.getName()))
        .sorted(Comparator.comparing(CourseView::name, String.CASE_INSENSITIVE_ORDER))
        .toList();

    List<ClassView> classes = moyKlassClient.listClasses().stream()
        .filter(this::isOpenedClass)
        .map(group -> new ClassView(
            group.getId(),
            group.getName(),
            group.getFilialId(),
            group.getCourseId()
        ))
        .sorted(Comparator.comparing(ClassView::name, String.CASE_INSENSITIVE_ORDER))
        .toList();

    return ResponseEntity.ok(new SignupOptionsResponse(filials, courses, classes, contactUrl()));
  }

  @PostMapping
  public ResponseEntity<?> submit(@RequestBody SignupRequest request) {
    String error = validateRequiredFields(request);
    if (error != null) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "message", error));
    }

    Optional<MoyKlassClient.ClassGroup> selectedClass = moyKlassClient.listClasses().stream()
        .filter(this::isOpenedClass)
        .filter(group -> group.getId() == request.classId)
        .findFirst();
    if (selectedClass.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Выбранная группа недоступна."));
    }
    MoyKlassClient.ClassGroup group = selectedClass.get();
    if (group.getFilialId() != request.filialId) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Группа не относится к выбранному саду."));
    }
    if (request.courseId > 0 && group.getCourseId() != request.courseId) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Группа не относится к выбранному направлению."));
    }

    MoyKlassResult result = moyKlassClient.createSiteLead(new MoyKlassClient.SiteSignupData(
        request.childName,
        request.parentName,
        request.phone,
        request.email,
        request.filialId,
        request.classId
    ));
    if (!result.isSuccess()) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("ok", false, "message", result.getMessage()));
    }
    return ResponseEntity.ok(Map.of(
        "ok", true,
        "message", "Заявка отправлена",
        "payLink", result.getData() == null ? "" : result.getData(),
        "contactUrl", contactUrl()
    ));
  }

  private String validateRequiredFields(SignupRequest request) {
    if (request == null) {
      return "Заполните форму.";
    }
    if (isBlank(request.childName)) {
      return "Укажите ФИО ребенка.";
    }
    if (isBlank(request.parentName)) {
      return "Укажите ФИО родителя.";
    }
    if (isBlank(request.phone) || request.phone.replaceAll("\\D", "").length() < 10) {
      return "Укажите корректный телефон.";
    }
    if (isBlank(request.email) || !request.email.contains("@")) {
      return "Укажите корректный email.";
    }
    if (request.filialId <= 0 || request.classId <= 0) {
      return "Выберите детский сад и группу.";
    }
    if (!request.personalConsent) {
      return "Подтвердите согласие на обработку персональных данных.";
    }
    return null;
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
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

  private String formatFilialName(MoyKlassClient.Filial filial) {
    String name = filial.getName();
    String shortName = filial.getShortName();
    if (shortName != null && !shortName.isBlank()) {
      if (name == null || name.isBlank()) {
        return shortName;
      }
      if (!name.toLowerCase().contains(shortName.toLowerCase())) {
        return shortName + " - " + name;
      }
    }
    return name == null ? "" : name;
  }

  private String contactUrl() {
    String value = properties.getSite() == null ? null : properties.getSite().getContactUrl();
    return value == null || value.isBlank() ? "https://max.ru/id246516134480_2_bot" : value;
  }

  public static class SignupRequest {
    public String childName;
    public String parentName;
    public String phone;
    public String email;
    public long filialId;
    public long courseId;
    public long classId;
    public boolean payLater;
    public boolean personalConsent;
    public boolean marketingConsent;
  }

  public record SignupOptionsResponse(
      List<FilialView> filials,
      List<CourseView> courses,
      List<ClassView> classes,
      String contactUrl
  ) {}

  public record FilialView(long id, String name) {}

  public record CourseView(long id, String name) {}

  public record ClassView(long id, String name, long filialId, long courseId) {}
}
