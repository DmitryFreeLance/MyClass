package com.myclass.maxbot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
  private final JdbcTemplate jdbcTemplate;

  public MessageRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void insert(long dialogId, String fromRole, String text, long createdAt) {
    jdbcTemplate.update(
        "INSERT INTO messages(dialog_id, from_role, text, created_at) VALUES(?, ?, ?, ?)",
        dialogId, fromRole, text, createdAt
    );
  }
}
