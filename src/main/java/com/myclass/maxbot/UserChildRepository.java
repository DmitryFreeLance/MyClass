package com.myclass.maxbot;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserChildRepository {
  private final JdbcTemplate jdbcTemplate;

  public UserChildRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void upsertChild(long maxUserId, long moyklassUserId, String childName, long createdAt) {
    jdbcTemplate.update(
        "INSERT INTO user_children(max_user_id, moyklass_user_id, child_name, created_at) " +
            "VALUES(?, ?, ?, ?) " +
            "ON CONFLICT(max_user_id, moyklass_user_id) DO UPDATE SET child_name = excluded.child_name",
        maxUserId, moyklassUserId, childName, createdAt
    );
  }

  public List<UserChild> listChildren(long maxUserId) {
    return jdbcTemplate.query(
        "SELECT id, max_user_id, moyklass_user_id, child_name, created_at " +
            "FROM user_children WHERE max_user_id = ? ORDER BY child_name",
        (rs, rowNum) -> new UserChild(
            rs.getLong("id"),
            rs.getLong("max_user_id"),
            rs.getLong("moyklass_user_id"),
            rs.getString("child_name"),
            rs.getLong("created_at")
        ),
        maxUserId
    );
  }

  public Optional<UserChild> findChild(long maxUserId, long moyklassUserId) {
    return jdbcTemplate.query(
        "SELECT id, max_user_id, moyklass_user_id, child_name, created_at " +
            "FROM user_children WHERE max_user_id = ? AND moyklass_user_id = ?",
        rs -> rs.next()
            ? Optional.of(new UserChild(
                rs.getLong("id"),
                rs.getLong("max_user_id"),
                rs.getLong("moyklass_user_id"),
                rs.getString("child_name"),
                rs.getLong("created_at")
            ))
            : Optional.empty(),
        maxUserId, moyklassUserId
    );
  }

  public void deleteChild(long maxUserId, long moyklassUserId) {
    jdbcTemplate.update(
        "DELETE FROM user_children WHERE max_user_id = ? AND moyklass_user_id = ?",
        maxUserId, moyklassUserId
    );
  }

  public static class UserChild {
    private final long id;
    private final long maxUserId;
    private final long moyklassUserId;
    private final String childName;
    private final long createdAt;

    public UserChild(long id, long maxUserId, long moyklassUserId, String childName, long createdAt) {
      this.id = id;
      this.maxUserId = maxUserId;
      this.moyklassUserId = moyklassUserId;
      this.childName = childName;
      this.createdAt = createdAt;
    }

    public long getId() {
      return id;
    }

    public long getMaxUserId() {
      return maxUserId;
    }

    public long getMoyklassUserId() {
      return moyklassUserId;
    }

    public String getChildName() {
      return childName;
    }

    public long getCreatedAt() {
      return createdAt;
    }
  }
}
