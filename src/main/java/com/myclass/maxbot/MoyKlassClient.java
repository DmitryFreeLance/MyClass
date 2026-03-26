package com.myclass.maxbot;

public interface MoyKlassClient {
  MoyKlassResult createLead(long maxUserId, String note);

  MoyKlassResult getRemainingLessons(long maxUserId);

  MoyKlassResult linkByPhone(long maxUserId, String phone);

  MoyKlassResult createInvoice(long maxUserId);
}
