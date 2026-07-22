package com.myclass.maxbot;

public interface MoyKlassClient {
  MoyKlassResult createLead(long maxUserId, String note, SignupData data);

  MoyKlassResult createSiteLead(SiteSignupData data);

  java.util.List<Filial> listFilials();

  java.util.List<ClassGroup> listClasses();

  java.util.List<Course> listCourses();

  MoyKlassUser getUserInfo(long moyklassUserId);

  java.util.List<Long> resolveLinkedMaxUserIds(long moyklassUserId);

  MoyKlassResult getRemainingLessons(long maxUserId);

  MoyKlassResult getRemainingLessonsByMoyklassUserId(long moyklassUserId);

  RemainingDetails getRemainingDetailsByMoyklassUserId(long moyklassUserId);

  java.util.List<SubscriptionRemaining> listSubscriptionRemainings(long moyklassUserId);

  MoyKlassResult linkByPhone(long maxUserId, String phone);

  MoyKlassResult getProfileInfo(long maxUserId);

  MoyKlassResult createInvoice(long maxUserId);

  MoyKlassResult createInvoiceByMoyklassUserId(long moyklassUserId);

  MoyKlassResult resolveMaxUserIdByPhone(String phone);

  MoyKlassResult resolveMaxUserIdByPhoneAndName(String phone, String childName);

  MoyKlassResult linkByPhoneAndName(long maxUserId, String phone, String childName);

  java.util.List<LessonRecordEvent> listVisitedLessonRecords(long sinceId);

  java.util.List<PaymentEvent> listIncomingPayments(long sinceId);

  java.util.List<PaymentEvent> listIncomingPaymentsByUser(long moyklassUserId, long sinceId);

  class Filial {
    private final long id;
    private final String name;
    private final String shortName;
    private final String status;

    public Filial(long id, String name, String shortName, String status) {
      this.id = id;
      this.name = name;
      this.shortName = shortName;
      this.status = status;
    }

    public long getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public String getShortName() {
      return shortName;
    }

    public String getStatus() {
      return status;
    }
  }

  class ClassGroup {
    private final long id;
    private final String name;
    private final String status;
    private final long filialId;
    private final long courseId;

    public ClassGroup(long id, String name, String status, long filialId, long courseId) {
      this.id = id;
      this.name = name;
      this.status = status;
      this.filialId = filialId;
      this.courseId = courseId;
    }

    public long getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public String getStatus() {
      return status;
    }

    public long getFilialId() {
      return filialId;
    }

    public long getCourseId() {
      return courseId;
    }
  }

  class Course {
    private final long id;
    private final String name;

    public Course(long id, String name) {
      this.id = id;
      this.name = name;
    }

    public long getId() {
      return id;
    }

    public String getName() {
      return name;
    }
  }

  class SignupData {
    private final String childName;
    private final String phone;
    private final String email;
    private final Long filialId;
    private final Long classId;

    public SignupData(String childName, String phone, String email, Long filialId, Long classId) {
      this.childName = childName;
      this.phone = phone;
      this.email = email;
      this.filialId = filialId;
      this.classId = classId;
    }

    public String getChildName() {
      return childName;
    }

    public String getPhone() {
      return phone;
    }

    public String getEmail() {
      return email;
    }

    public Long getFilialId() {
      return filialId;
    }

    public Long getClassId() {
      return classId;
    }
  }

  class SiteSignupData {
    private final String childName;
    private final String parentName;
    private final String phone;
    private final String email;
    private final Long filialId;
    private final Long classId;

    public SiteSignupData(String childName, String parentName, String phone, String email, Long filialId, Long classId) {
      this.childName = childName;
      this.parentName = parentName;
      this.phone = phone;
      this.email = email;
      this.filialId = filialId;
      this.classId = classId;
    }

    public String getChildName() {
      return childName;
    }

    public String getParentName() {
      return parentName;
    }

    public String getPhone() {
      return phone;
    }

    public String getEmail() {
      return email;
    }

    public Long getFilialId() {
      return filialId;
    }

    public Long getClassId() {
      return classId;
    }
  }

  class MoyKlassUser {
    private final long id;
    private final String name;
    private final String phone;
    private final double balance;
    private final double availableBalance;

    public MoyKlassUser(long id, String name, String phone) {
      this(id, name, phone, 0, 0);
    }

    public MoyKlassUser(long id, String name, String phone, double balance, double availableBalance) {
      this.id = id;
      this.name = name;
      this.phone = phone;
      this.balance = balance;
      this.availableBalance = availableBalance;
    }

    public long getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public String getPhone() {
      return phone;
    }

    public double getBalance() {
      return balance;
    }

    public double getAvailableBalance() {
      return availableBalance;
    }
  }

  class RemainingDetails {
    private final java.util.List<RemainingItem> items;
    private final int total;
    private final double balance;
    private final double availableBalance;

    public RemainingDetails(java.util.List<RemainingItem> items, int total) {
      this(items, total, 0, 0);
    }

    public RemainingDetails(java.util.List<RemainingItem> items, int total, double balance, double availableBalance) {
      this.items = items;
      this.total = total;
      this.balance = balance;
      this.availableBalance = availableBalance;
    }

    public java.util.List<RemainingItem> getItems() {
      return items;
    }

    public int getTotal() {
      return total;
    }

    public double getBalance() {
      return balance;
    }

    public double getAvailableBalance() {
      return availableBalance;
    }
  }

  class SubscriptionRemaining {
    private final String courseName;
    private final String className;
    private final int remaining;

    public SubscriptionRemaining(String courseName, String className, int remaining) {
      this.courseName = courseName;
      this.className = className;
      this.remaining = remaining;
    }

    public String getCourseName() {
      return courseName;
    }

    public String getClassName() {
      return className;
    }

    public int getRemaining() {
      return remaining;
    }
  }

  class RemainingItem {
    private final String courseName;
    private final String className;
    private final int remaining;

    public RemainingItem(String courseName, String className, int remaining) {
      this.courseName = courseName;
      this.className = className;
      this.remaining = remaining;
    }

    public String getCourseName() {
      return courseName;
    }

    public String getClassName() {
      return className;
    }

    public int getRemaining() {
      return remaining;
    }
  }

  class LessonRecordEvent {
    private final long id;
    private final long userId;
    private final long lessonId;
    private final long classId;
    private final int lessonStatus;
    private final boolean visited;
    private final boolean paid;

    public LessonRecordEvent(long id, long userId, long lessonId, long classId, int lessonStatus,
                             boolean visited, boolean paid) {
      this.id = id;
      this.userId = userId;
      this.lessonId = lessonId;
      this.classId = classId;
      this.lessonStatus = lessonStatus;
      this.visited = visited;
      this.paid = paid;
    }

    public long getId() {
      return id;
    }

    public long getUserId() {
      return userId;
    }

    public long getLessonId() {
      return lessonId;
    }

    public long getClassId() {
      return classId;
    }

    public int getLessonStatus() {
      return lessonStatus;
    }

    public boolean isVisited() {
      return visited;
    }

    public boolean isPaid() {
      return paid;
    }
  }

  class PaymentEvent {
    private final long id;
    private final long userId;
    private final double amount;
    private final Long userSubscriptionId;
    private final String comment;

    public PaymentEvent(long id, long userId, double amount, Long userSubscriptionId, String comment) {
      this.id = id;
      this.userId = userId;
      this.amount = amount;
      this.userSubscriptionId = userSubscriptionId;
      this.comment = comment;
    }

    public long getId() {
      return id;
    }

    public long getUserId() {
      return userId;
    }

    public double getAmount() {
      return amount;
    }

    public Long getUserSubscriptionId() {
      return userSubscriptionId;
    }

    public String getComment() {
      return comment;
    }
  }
}
