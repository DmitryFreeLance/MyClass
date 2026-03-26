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
  public MoyKlassResult getRemainingLessons(long maxUserId) {
    log.warn("MoyKlass integration disabled; getRemainingLessons skipped for user {}", maxUserId);
    return MoyKlassResult.failure("Интеграция с МойКласс пока не настроена.");
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
}
