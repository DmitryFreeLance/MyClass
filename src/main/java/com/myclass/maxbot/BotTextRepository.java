package com.myclass.maxbot;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BotTextRepository {
  private final JdbcTemplate jdbcTemplate;

  public BotTextRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<String> findText(String key) {
    return jdbcTemplate.query(
        "SELECT text FROM bot_texts WHERE key = ?",
        rs -> rs.next() ? Optional.ofNullable(rs.getString("text")) : Optional.empty(),
        key
    );
  }

  public void upsertText(String key, String text, long updatedAt) {
    jdbcTemplate.update(
        "INSERT INTO bot_texts(key, text, updated_at) VALUES(?, ?, ?) " +
            "ON CONFLICT(key) DO UPDATE SET text = excluded.text, updated_at = excluded.updated_at",
        key, text, updatedAt
    );
  }
}
