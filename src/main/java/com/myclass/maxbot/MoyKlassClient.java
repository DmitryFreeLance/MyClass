package com.myclass.maxbot;

public interface MoyKlassClient {
  MoyKlassResult createLead(long maxUserId, String note, SignupData data);

  MoyKlassResult getRemainingLessons(long maxUserId);

  MoyKlassResult linkByPhone(long maxUserId, String phone);

  MoyKlassResult getProfileInfo(long maxUserId);

  MoyKlassResult createInvoice(long maxUserId);

  MoyKlassResult resolveMaxUserIdByPhone(String phone);

  class SignupData {
    private final String childName;
    private final String parentName;
    private final String phone;
    private final String email;

    public SignupData(String childName, String parentName, String phone, String email) {
      this.childName = childName;
      this.parentName = parentName;
      this.phone = phone;
      this.email = email;
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
  }
}
