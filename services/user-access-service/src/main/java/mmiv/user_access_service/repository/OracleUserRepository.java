package mmiv.user_access_service.repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class OracleUserRepository implements UserRepository {

  private final JdbcTemplate jdbcTemplate;

  public OracleUserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<User> findById(String userId) {
    return findOne(
        """
                SELECT user_id, username, role, active, password_hash
                FROM app_users
                WHERE user_id = ?
                """,
        userId);
  }

  @Override
  public Optional<User> findByUsername(String username) {
    if (username == null || username.isBlank()) {
      return Optional.empty();
    }

    return findOne(
        """
                SELECT user_id, username, role, active, password_hash
                FROM app_users
                WHERE username = ?
                """,
        username);
  }

  private Optional<User> findOne(String sql, String value) {
    return jdbcTemplate
        .query(
            sql,
            (rs, rowNum) -> {
              String userId = rs.getString("user_id");
              return new User(
                  userId,
                  loadDestinationAccesses(userId),
                  rs.getString("username"),
                  rs.getString("role"),
                  rs.getInt("active") == 1,
                  loadGlobalPermissions(userId),
                  rs.getString("password_hash"));
            },
            value)
        .stream()
        .findFirst();
  }

  private Set<Permission> loadGlobalPermissions(String userId) {
    List<String> values =
        jdbcTemplate.queryForList(
            """
                SELECT permission
                FROM user_permissions
                WHERE user_id = ?
                """,
            String.class,
            userId);

    Set<Permission> permissions = new HashSet<>();
    for (String value : values) {
      permissions.add(Permission.valueOf(value));
    }
    return permissions;
  }

  private List<DestinationAccess> loadDestinationAccesses(String userId) {
    return jdbcTemplate.query(
        """
                SELECT destination, permission
                FROM user_destination_access
                WHERE user_id = ?
                ORDER BY destination
                """,
        (rs, rowNum) ->
            new DestinationAccess(
                rs.getString("destination"), Permission.valueOf(rs.getString("permission"))),
        userId);
  }

  @Override
  @Transactional
  public void save(User user) {
    jdbcTemplate.update(
        """
                MERGE INTO app_users target
                USING (
                    SELECT ? AS user_id,
                           ? AS username,
                           ? AS role,
                           ? AS active,
                           ? AS password_hash
                    FROM dual
                ) source
                ON (target.user_id = source.user_id)
                WHEN MATCHED THEN UPDATE SET
                    target.username = source.username,
                    target.role = source.role,
                    target.active = source.active,
                    target.password_hash =
                        COALESCE(source.password_hash, target.password_hash)
                WHEN NOT MATCHED THEN INSERT (
                    user_id, username, role, active, password_hash
                ) VALUES (
                    source.user_id, source.username, source.role,
                    source.active, source.password_hash
                )
                """,
        user.getUserId(),
        user.getUsername(),
        user.getRole(),
        user.isActive() ? 1 : 0,
        user.getPasswordHash());

    jdbcTemplate.update("DELETE FROM user_permissions WHERE user_id = ?", user.getUserId());
    for (Permission permission : user.getPermissions()) {
      jdbcTemplate.update(
          """
                    INSERT INTO user_permissions (user_id, permission)
                    VALUES (?, ?)
                    """,
          user.getUserId(),
          permission.name());
    }
  }

  @Override
  @Transactional
  public void grantDestinationAccess(String userId, String destination, Permission permission) {
    validateDestinationAccessArguments(userId, destination, permission);
    jdbcTemplate.update(
        """
                MERGE INTO user_destination_access target
                USING (
                    SELECT ? AS user_id, ? AS destination, ? AS permission
                    FROM dual
                ) source
                ON (
                    target.user_id = source.user_id
                    AND target.destination = source.destination
                )
                WHEN MATCHED THEN
                    UPDATE SET target.permission = source.permission
                WHEN NOT MATCHED THEN
                    INSERT (user_id, destination, permission)
                    VALUES (
                        source.user_id, source.destination, source.permission
                    )
                """,
        userId,
        destination,
        permission.name());
  }

  @Override
  @Transactional
  public void revokeDestinationAccess(String userId, String destination) {
    if (userId == null || userId.isBlank() || destination == null || destination.isBlank()) {
      throw new IllegalArgumentException("User ID and destination are required");
    }
    jdbcTemplate.update(
        """
                DELETE FROM user_destination_access
                WHERE user_id = ? AND destination = ?
                """,
        userId,
        destination);
  }

  private static void validateDestinationAccessArguments(
      String userId, String destination, Permission permission) {
    if (userId == null
        || userId.isBlank()
        || destination == null
        || destination.isBlank()
        || permission == null) {
      throw new IllegalArgumentException("User ID, destination, and permission are required");
    }
  }
}
