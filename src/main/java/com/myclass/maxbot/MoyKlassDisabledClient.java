package com.myclass.maxbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoyKlassDisabledClient implements MoyKlassClient {
  private static final Logger log = LoggerFactory.getLogger(MoyKlassDisabledClient.class);

  @Override
  public MoyKlassResult createLead(long maxUserId, String note, SignupData data) {
    log.warn("MoyKlass integration disabled; createLead skipped for user {}", maxUserId);
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }

  @Override
  public java.util.List<Filial> listFilials() {
    log.warn("MoyKlass integration disabled; listFilials skipped");
    return java.util.List.of();
  }

  @Override
  public java.util.List<ClassGroup> listClasses() {
    log.warn("MoyKlass integration disabled; listClasses skipped");
    return java.util.List.of();
  }

  @Override
  public MoyKlassUser getUserInfo(long moyklassUserId) {
    log.warn("MoyKlass integration disabled; getUserInfo skipped");
    return null;
  }

  @Override
  public MoyKlassResult getRemainingLessons(long maxUserId) {
    log.warn("MoyKlass integration disabled; getRemainingLessons skipped for user {}", maxUserId);
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }

  @Override
  public MoyKlassResult getRemainingLessonsByMoyklassUserId(long moyklassUserId) {
    log.warn("MoyKlass integration disabled; getRemainingLessonsByMoyklassUserId skipped for user {}", moyklassUserId);
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }

  @Override
  public RemainingDetails getRemainingDetailsByMoyklassUserId(long moyklassUserId) {
    log.warn("MoyKlass integration disabled; getRemainingDetailsByMoyklassUserId skipped for user {}", moyklassUserId);
    return new RemainingDetails(java.util.List.of(), 0);
  }

  @Override
  public MoyKlassResult linkByPhone(long maxUserId, String phone) {
    log.warn("MoyKlass integration disabled; linkByPhone skipped for user {}", maxUserId);
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }

  @Override
  public MoyKlassResult getProfileInfo(long maxUserId) {
    log.warn("MoyKlass integration disabled; getProfileInfo skipped for user {}", maxUserId);
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }

  @Override
  public MoyKlassResult createInvoice(long maxUserId) {
    log.warn("MoyKlass integration disabled; createInvoice skipped for user {}", maxUserId);
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }

  @Override
  public MoyKlassResult createInvoiceByMoyklassUserId(long moyklassUserId) {
    log.warn("MoyKlass integration disabled; createInvoiceByMoyklassUserId skipped for user {}", moyklassUserId);
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }

  @Override
  public MoyKlassResult resolveMaxUserIdByPhone(String phone) {
    log.warn("MoyKlass integration disabled; resolveMaxUserIdByPhone skipped");
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }

  @Override
  public MoyKlassResult resolveMaxUserIdByPhoneAndName(String phone, String childName) {
    log.warn("MoyKlass integration disabled; resolveMaxUserIdByPhoneAndName skipped");
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }

  @Override
  public MoyKlassResult linkByPhoneAndName(long maxUserId, String phone, String childName) {
    log.warn("MoyKlass integration disabled; linkByPhoneAndName skipped");
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
  }
}
