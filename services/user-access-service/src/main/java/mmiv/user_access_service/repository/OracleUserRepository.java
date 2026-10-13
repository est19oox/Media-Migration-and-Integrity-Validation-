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
                SELECT user_id, username, role, active, password_hash, auth_version
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
                SELECT user_id, username, role, active, password_hash, auth_version
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
                  rs.getString("password_hash"),rs.getLong("auth_version"));
            },
            value)
        .stream()
        .findFirst();
  }

  public List<User> searchUsers(String query) {
    return jdbcTemplate.queryForList("SELECT USER_ID FROM APP_USERS WHERE (? IS NULL OR INSTR(LOWER(USERNAME),LOWER(?))>0) ORDER BY USERNAME FETCH FIRST 100 ROWS ONLY", String.class, query,query)
        .stream().map(this::findById).flatMap(Optional::stream).toList();
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
  public void create(User user) {
    jdbcTemplate.update(
        """
        INSERT INTO app_users (user_id, username, role, active, password_hash)
        VALUES (?, ?, ?, ?, ?)
        """,
        user.getUserId(),
        user.getUsername(),
        user.getRole(),
        user.isActive() ? 1 : 0,
        user.getPasswordHash());
    for (Permission permission : user.getPermissions()) {
      jdbcTemplate.update(
          "INSERT INTO user_permissions (user_id, permission) VALUES (?, ?)",
          user.getUserId(),
          permission.name());
    }
  }

  @Override
  @Transactional
  public boolean deleteVerificationAccount(String userId) {
    return jdbcTemplate.update(
        "DELETE FROM app_users WHERE user_id = ? AND role = 'USER' "
            + "AND REGEXP_LIKE(username, '^mmiv_ci_[0-9a-f]{32}$', 'c')",
        userId) == 1;
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

  @Override
  public void createAdminInvitation(String userId, String tokenHash, java.time.Instant expiresAt, String issuerId) {
    jdbcTemplate.update("INSERT INTO ADMIN_INVITATIONS (TOKEN_HASH,USER_ID,EXPIRES_AT,ISSUER_USER_ID) VALUES (?,?,?,?)",
        tokenHash, userId, java.sql.Timestamp.from(expiresAt), issuerId);
  }

  @Override
  @Transactional
  public boolean acceptAdminInvitation(String userId, String tokenHash, java.time.Instant now) {
    // Serialize role transitions for this account before consuming any invitation.
    var roles = jdbcTemplate.queryForList("SELECT ROLE FROM APP_USERS WHERE USER_ID=? AND ACTIVE=1 FOR UPDATE", String.class, userId);
    if (roles.size() != 1 || !"USER".equals(roles.get(0))) return false;
    int consumed = jdbcTemplate.update("UPDATE ADMIN_INVITATIONS SET CONSUMED_AT=? WHERE TOKEN_HASH=? AND USER_ID=? AND CONSUMED_AT IS NULL AND EXPIRES_AT>?",
        java.sql.Timestamp.from(now), tokenHash, userId, java.sql.Timestamp.from(now));
    if (consumed != 1) return false;
    jdbcTemplate.update("UPDATE APP_USERS SET ROLE='ADMIN' WHERE USER_ID=?", userId);
    jdbcTemplate.update("DELETE FROM PASSWORD_RESETS WHERE USER_ID=?",userId);
    jdbcTemplate.update("UPDATE ADMIN_INVITATIONS SET CONSUMED_AT=? WHERE USER_ID=? AND CONSUMED_AT IS NULL",
        java.sql.Timestamp.from(now), userId);
    return true;
  }


  private record LockedAccount(String role,boolean active){}
  private boolean accountManagementAllowed(String actorId,String targetId,boolean requireActiveTarget){
    var ids=new java.util.TreeSet<String>(java.util.List.of(actorId,targetId));var accounts=new java.util.HashMap<String,LockedAccount>();
    for(String id:ids){var rows=jdbcTemplate.query("SELECT ROLE,ACTIVE FROM APP_USERS WHERE USER_ID=? FOR UPDATE",(rs,n)->new LockedAccount(rs.getString("ROLE"),rs.getInt("ACTIVE")==1),id);if(!rows.isEmpty())accounts.put(id,rows.getFirst());}
    var actor=accounts.get(actorId);var target=accounts.get(targetId);return actor!=null&&actor.active()&&"ADMIN".equals(actor.role())&&target!=null&&(!requireActiveTarget||target.active())&&(actorId.equals(targetId)||"USER".equals(target.role()));
  }
  @Override @Transactional public boolean deleteAccount(String actorId,String targetId){
    // Recheck roles while locked: an invitation cannot promote a target mid-delete.
    if(!accountManagementAllowed(actorId,targetId,false))return false;
    jdbcTemplate.update("UPDATE ADMIN_INVITATIONS SET EXPIRES_AT=SYSTIMESTAMP WHERE USER_ID=? OR ISSUER_USER_ID=?",targetId,targetId);
    jdbcTemplate.update("UPDATE PASSWORD_RESETS SET EXPIRES_AT=SYSTIMESTAMP WHERE USER_ID=? OR ISSUER_USER_ID=?",targetId,targetId);
    return jdbcTemplate.update("UPDATE APP_USERS SET ACTIVE=0, AUTH_VERSION=AUTH_VERSION+1 WHERE USER_ID=?",targetId)==1;
  }
  @Override @Transactional public boolean createPasswordReset(String actorId,String targetId,String hash,java.time.Instant expires){
    if(!accountManagementAllowed(actorId,targetId,true))return false;
    jdbcTemplate.update("DELETE FROM PASSWORD_RESETS WHERE USER_ID=?",targetId);
    jdbcTemplate.update("INSERT INTO PASSWORD_RESETS(TOKEN_HASH,USER_ID,ISSUER_USER_ID,EXPIRES_AT) VALUES (?,?,?,?)",hash,targetId,actorId,java.sql.Timestamp.from(expires));return true;
  }
  @Override @Transactional public Optional<String> consumePasswordReset(String hash,java.util.function.Supplier<String> password,java.time.Instant now){
    var targets=jdbcTemplate.queryForList("SELECT USER_ID FROM PASSWORD_RESETS WHERE TOKEN_HASH=?",String.class,hash);if(targets.isEmpty())return Optional.empty();String id=targets.getFirst();
    var active=jdbcTemplate.queryForList("SELECT USER_ID FROM APP_USERS WHERE USER_ID=? AND ACTIVE=1 FOR UPDATE",String.class,id);if(active.isEmpty())return Optional.empty();
    int consumed=jdbcTemplate.update("DELETE FROM PASSWORD_RESETS WHERE TOKEN_HASH=? AND USER_ID=? AND EXPIRES_AT>?",hash,id,java.sql.Timestamp.from(now));if(consumed!=1)return Optional.empty();
    jdbcTemplate.update("UPDATE APP_USERS SET PASSWORD_HASH=?,AUTH_VERSION=AUTH_VERSION+1 WHERE USER_ID=?",password.get(),id);
    jdbcTemplate.update("DELETE FROM PASSWORD_RESETS WHERE USER_ID=?",id);
    jdbcTemplate.update("DELETE FROM ADMIN_INVITATIONS WHERE USER_ID=?",id);
    return Optional.of(id);
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
