package mmiv.user_access_service.repository;

import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@Primary
@Profile("!test")
public class OracleUserRepository implements UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public OracleUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<User> findById(String userId) {
        List<User> users = jdbcTemplate.query(
                """
                        SELECT user_id, username, role, active, password_hash
                        FROM app_users
                        WHERE user_id = ?
                        """,
                (rs, rowNum) -> new User(
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getString("role"),
                        rs.getInt("active") == 1,
                        loadPermissions(rs.getString("user_id")),
                        rs.getString("password_hash")),
                userId);

        return users.stream().findFirst();
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        List<User> users = jdbcTemplate.query(
                """
                        SELECT user_id, username, role, active, password_hash
                        FROM app_users
                        WHERE username = ?
                        """,
                (rs, rowNum) -> new User(
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getString("role"),
                        rs.getInt("active") == 1,
                        loadPermissions(rs.getString("user_id")),
                        rs.getString("password_hash")),
                username);

        return users.stream().findFirst();
    }

    private Set<Permission> loadPermissions(String userId) {
        List<String> values = jdbcTemplate.queryForList(
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
                        WHEN MATCHED THEN
                            UPDATE SET
                                target.username = source.username,
                                target.role = source.role,
                                target.active = source.active,
                                target.password_hash =
                                    COALESCE(source.password_hash, target.password_hash)
                        WHEN NOT MATCHED THEN
                            INSERT (
                                user_id, username, role, active, password_hash
                            )
                            VALUES (
                                source.user_id,
                                source.username,
                                source.role,
                                source.active,
                                source.password_hash
                            )
                        """,
                user.getUserId(),
                user.getUsername(),
                user.getRole(),
                user.isActive() ? 1 : 0,
                user.getPasswordHash());

        jdbcTemplate.update(
                "DELETE FROM user_permissions WHERE user_id = ?",
                user.getUserId());

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
}
