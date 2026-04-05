package com.myclass.maxbot;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LessonNotificationRepository {
  private final JdbcTemplate jdbcTemplate;

  public LessonNotificationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Set<Long> findNotifiedRecordIds(Collection<Long> recordIds) {
    if (recordIds == null || recordIds.isEmpty()) {
      return Set.of();
    }
    List<Long> ids = recordIds.stream()
        .filter(id -> id != null && id > 0)
        .distinct()
        .toList();
    if (ids.isEmpty()) {
      return Set.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    String sql = "SELECT record_id FROM lesson_notifications WHERE record_id IN (" + placeholders + ")";
    return new HashSet<>(jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("record_id"), ids.toArray()));
  }

  public void markNotified(long recordId, long notifiedAt) {
    if (recordId <= 0) {
      return;
    }
    jdbcTemplate.update(
        "INSERT INTO lesson_notifications(record_id, notified_at) VALUES(?, ?) "
            + "ON CONFLICT(record_id) DO UPDATE SET notified_at = excluded.notified_at",
        recordId, notifiedAt
    );
  }
}
