package com.myclass.maxbot;

public interface MoyKlassClient {
  MoyKlassResult createLead(long maxUserId, String note, SignupData data);

  java.util.List<Filial> listFilials();

  java.util.List<ClassGroup> listClasses();

  MoyKlassUser getUserInfo(long moyklassUserId);

  MoyKlassResult getRemainingLessons(long maxUserId);

  MoyKlassResult getRemainingLessonsByMoyklassUserId(long moyklassUserId);

  MoyKlassResult linkByPhone(long maxUserId, String phone);

  MoyKlassResult getProfileInfo(long maxUserId);

  MoyKlassResult createInvoice(long maxUserId);

  MoyKlassResult createInvoiceByMoyklassUserId(long moyklassUserId);

  MoyKlassResult resolveMaxUserIdByPhone(String phone);

  MoyKlassResult resolveMaxUserIdByPhoneAndName(String phone, String childName);

  MoyKlassResult linkByPhoneAndName(long maxUserId, String phone, String childName);

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

  class MoyKlassUser {
    private final long id;
    private final String name;
    private final String phone;

    public MoyKlassUser(long id, String name, String phone) {
      this.id = id;
      this.name = name;
      this.phone = phone;
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
  }
}
