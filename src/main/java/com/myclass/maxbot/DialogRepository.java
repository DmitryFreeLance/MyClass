package com.myclass.maxbot;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class DialogRepository {
  private final JdbcTemplate jdbcTemplate;

  public DialogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public DialogRecord create(long userId, long adminId, long startedAt) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      var ps = connection.prepareStatement(
          "INSERT INTO dialogs(user_id, admin_id, active, started_at) VALUES (?, ?, 1, ?)",
          new String[] {"id"}
      );
      ps.setLong(1, userId);
      ps.setLong(2, adminId);
      ps.setLong(3, startedAt);
      return ps;
    }, keyHolder);

    long id = keyHolder.getKey() != null ? keyHolder.getKey().longValue() : -1L;
    return new DialogRecord(id, userId, adminId, true, startedAt, null);
  }

  public Optional<DialogRecord> findActiveByUserId(long userId) {
    return jdbcTemplate.query(
        "SELECT id, user_id, admin_id, active, started_at, ended_at FROM dialogs WHERE user_id = ? AND active = 1",
        rs -> rs.next()
            ? Optional.of(new DialogRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("admin_id"),
                rs.getInt("active") == 1,
                rs.getLong("started_at"),
                rs.getObject("ended_at") != null ? rs.getLong("ended_at") : null
            ))
            : Optional.empty(),
        userId
    );
  }

  public Optional<DialogRecord> findById(long dialogId) {
    return jdbcTemplate.query(
        "SELECT id, user_id, admin_id, active, started_at, ended_at FROM dialogs WHERE id = ?",
        rs -> rs.next()
            ? Optional.of(new DialogRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("admin_id"),
                rs.getInt("active") == 1,
                rs.getLong("started_at"),
                rs.getObject("ended_at") != null ? rs.getLong("ended_at") : null
            ))
            : Optional.empty(),
        dialogId
    );
  }

  public List<DialogRecord> listActive() {
    return jdbcTemplate.query(
        "SELECT id, user_id, admin_id, active, started_at, ended_at FROM dialogs WHERE active = 1 ORDER BY started_at DESC",
        (rs, rowNum) -> new DialogRecord(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("admin_id"),
            rs.getInt("active") == 1,
            rs.getLong("started_at"),
            rs.getObject("ended_at") != null ? rs.getLong("ended_at") : null
        )
    );
  }

  public void close(long dialogId, long endedAt) {
    jdbcTemplate.update("UPDATE dialogs SET active = 0, ended_at = ? WHERE id = ?", endedAt, dialogId);
  }
}
