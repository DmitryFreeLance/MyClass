package com.myclass.maxbot;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
  private final JdbcTemplate jdbcTemplate;

  public UserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void upsertUser(long maxUserId, String firstName, String lastName, String username, long lastSeen) {
    jdbcTemplate.update(
        "INSERT INTO users(max_user_id, first_name, last_name, username, last_seen) " +
            "VALUES(?, ?, ?, ?, ?) " +
            "ON CONFLICT(max_user_id) DO UPDATE SET first_name = excluded.first_name, " +
            "last_name = excluded.last_name, username = excluded.username, last_seen = excluded.last_seen",
        maxUserId, firstName, lastName, username, lastSeen
    );
  }

  public Optional<UserRecord> findByMaxUserId(long maxUserId) {
    return jdbcTemplate.query(
        "SELECT id, max_user_id, moyklass_user_id, first_name, last_name, username, last_seen " +
            "FROM users WHERE max_user_id = ?",
        rs -> rs.next()
            ? Optional.of(new UserRecord(
                rs.getLong("id"),
                rs.getLong("max_user_id"),
                rs.getObject("moyklass_user_id") != null ? rs.getLong("moyklass_user_id") : null,
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("username"),
                rs.getLong("last_seen")
            ))
            : Optional.empty(),
        maxUserId
    );
  }

  public List<UserRecord> listAll() {
    return jdbcTemplate.query(
        "SELECT id, max_user_id, moyklass_user_id, first_name, last_name, username, last_seen " +
            "FROM users ORDER BY last_seen DESC",
        (rs, rowNum) -> new UserRecord(
            rs.getLong("id"),
            rs.getLong("max_user_id"),
            rs.getObject("moyklass_user_id") != null ? rs.getLong("moyklass_user_id") : null,
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("username"),
            rs.getLong("last_seen")
        )
    );
  }

  public void setMoyklassUserId(long maxUserId, long moyklassUserId) {
    jdbcTemplate.update(
        "UPDATE users SET moyklass_user_id = ? WHERE max_user_id = ?",
        moyklassUserId, maxUserId
    );
  }

  public void clearMoyklassUserId(long maxUserId) {
    jdbcTemplate.update(
        "UPDATE users SET moyklass_user_id = NULL WHERE max_user_id = ?",
        maxUserId
    );
  }
}
