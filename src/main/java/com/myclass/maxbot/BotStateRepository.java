package com.myclass.maxbot;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BotStateRepository {
  private final JdbcTemplate jdbcTemplate;

  public BotStateRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<String> get(String key) {
    return jdbcTemplate.query(
        "SELECT value FROM bot_state WHERE key = ?",
        rs -> rs.next() ? Optional.ofNullable(rs.getString("value")) : Optional.empty(),
        key
    );
  }

  public void set(String key, String value) {
    jdbcTemplate.update(
        "INSERT INTO bot_state(key, value) VALUES(?, ?) " +
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        key, value
    );
  }

  public void delete(String key) {
    jdbcTemplate.update("DELETE FROM bot_state WHERE key = ?", key);
  }
}
