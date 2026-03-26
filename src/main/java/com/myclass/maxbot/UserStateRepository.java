package com.myclass.maxbot;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserStateRepository {
  private final JdbcTemplate jdbcTemplate;

  public UserStateRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void setState(long maxUserId, String state, String data, long updatedAt) {
    jdbcTemplate.update(
        "INSERT INTO user_states(max_user_id, state, data, updated_at) VALUES(?, ?, ?, ?) " +
            "ON CONFLICT(max_user_id) DO UPDATE SET state = excluded.state, data = excluded.data, " +
            "updated_at = excluded.updated_at",
        maxUserId, state, data, updatedAt
    );
  }

  public Optional<UserState> getState(long maxUserId) {
    return jdbcTemplate.query(
        "SELECT max_user_id, state, data, updated_at FROM user_states WHERE max_user_id = ?",
        rs -> rs.next()
            ? Optional.of(new UserState(
                rs.getLong("max_user_id"),
                rs.getString("state"),
                rs.getString("data"),
                rs.getLong("updated_at")
            ))
            : Optional.empty(),
        maxUserId
    );
  }

  public void clearState(long maxUserId) {
    jdbcTemplate.update("DELETE FROM user_states WHERE max_user_id = ?", maxUserId);
  }

  public static class UserState {
    private final long maxUserId;
    private final String state;
    private final String data;
    private final long updatedAt;

    public UserState(long maxUserId, String state, String data, long updatedAt) {
      this.maxUserId = maxUserId;
      this.state = state;
      this.data = data;
      this.updatedAt = updatedAt;
    }

    public long getMaxUserId() {
      return maxUserId;
    }

    public String getState() {
      return state;
    }

    public String getData() {
      return data;
    }

    public long getUpdatedAt() {
      return updatedAt;
    }
  }
}
