package com.myclass.maxbot;

public class UserRecord {
  private final long id;
  private final long maxUserId;
  private final Long moyklassUserId;
  private final String firstName;
  private final String lastName;
  private final String username;
  private final long lastSeen;

  public UserRecord(long id, long maxUserId, Long moyklassUserId, String firstName, String lastName, String username,
                    long lastSeen) {
    this.id = id;
    this.maxUserId = maxUserId;
    this.moyklassUserId = moyklassUserId;
    this.firstName = firstName;
    this.lastName = lastName;
    this.username = username;
    this.lastSeen = lastSeen;
  }

  public long getId() {
    return id;
  }

  public long getMaxUserId() {
    return maxUserId;
  }

  public Long getMoyklassUserId() {
    return moyklassUserId;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public String getUsername() {
    return username;
  }

  public long getLastSeen() {
    return lastSeen;
  }
}
