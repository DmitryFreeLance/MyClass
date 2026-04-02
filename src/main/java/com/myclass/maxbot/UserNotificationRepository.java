package com.myclass.maxbot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserNotificationRepository {
  private final JdbcTemplate jdbcTemplate;

  public UserNotificationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean isFirstAuthSent(long maxUserId) {
    Integer value = jdbcTemplate.query(
        "SELECT first_auth_sent FROM user_notifications WHERE max_user_id = ?",
        rs -> rs.next() ? rs.getInt("first_auth_sent") : null,
        maxUserId
    );
    return value != null && value != 0;
  }

  public void markFirstAuthSent(long maxUserId) {
    jdbcTemplate.update(
        "INSERT INTO user_notifications(max_user_id, first_auth_sent) VALUES(?, 1) " +
            "ON CONFLICT(max_user_id) DO UPDATE SET first_auth_sent = 1",
        maxUserId
    );
  }
}
