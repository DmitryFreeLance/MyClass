package com.myclass.maxbot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserRepository {
  private final JdbcTemplate jdbcTemplate;

  public AdminUserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean isAdmin(long maxUserId) {
    Integer value = jdbcTemplate.query(
        "SELECT max_user_id FROM admin_users WHERE max_user_id = ?",
        rs -> rs.next() ? rs.getInt("max_user_id") : null,
        maxUserId
    );
    return value != null;
  }

  public void addAdmin(long maxUserId, long createdAt) {
    jdbcTemplate.update(
        "INSERT INTO admin_users(max_user_id, created_at) VALUES(?, ?) " +
            "ON CONFLICT(max_user_id) DO UPDATE SET created_at = excluded.created_at",
        maxUserId, createdAt
    );
  }
}
