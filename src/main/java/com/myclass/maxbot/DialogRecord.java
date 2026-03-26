package com.myclass.maxbot;

public class DialogRecord {
  private final long id;
  private final long userId;
  private final long adminId;
  private final boolean active;
  private final long startedAt;
  private final Long endedAt;

  public DialogRecord(long id, long userId, long adminId, boolean active, long startedAt, Long endedAt) {
    this.id = id;
    this.userId = userId;
    this.adminId = adminId;
    this.active = active;
    this.startedAt = startedAt;
    this.endedAt = endedAt;
  }

  public long getId() {
    return id;
  }

  public long getUserId() {
    return userId;
  }

  public long getAdminId() {
    return adminId;
  }

  public boolean isActive() {
    return active;
  }

  public long getStartedAt() {
    return startedAt;
  }

  public Long getEndedAt() {
    return endedAt;
  }
}
