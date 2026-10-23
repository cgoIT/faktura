package de.cgoit.faktura.security.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the users. Rows are never deleted.
 */
@Repository
public class UserRepository {

    private static final String SELECT = """
            SELECT id, username, display_name, password_hash, admin, active, last_login_at FROM app_user
            """;

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A stored user including the password hash.
     */
    public record Row(String id, String username, String displayName, String passwordHash, boolean admin,
            boolean active, String lastLogin) {

        /**
         * Hides names and the hash from logs.
         */
        @Override
        public String toString() {
            return "Row[id=" + id + "]";
        }
    }

    public List<Row> findAll() {
        return jdbc.sql(SELECT + " ORDER BY username")
                .query(UserRepository::map)
                .list();
    }

    public Optional<Row> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(UserRepository::map)
                .optional();
    }

    public Optional<Row> findByUsername(String username) {
        return jdbc.sql(SELECT + " WHERE username = :username")
                .param("username", username)
                .query(UserRepository::map)
                .optional();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("password_hash"), rs.getInt("admin") == 1, rs.getInt("active") == 1,
                rs.getString("last_login_at"));
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM app_user")
                .query(Long.class)
                .single();
    }

    /**
     * Inserts a user unless the name is taken.
     *
     * @return {@code false} if a user with that name exists already (also when another request created it just now)
     */
    public boolean insert(String id, String username, String displayName, String passwordHash, boolean admin,
            String now) {
        return jdbc.sql("""
                        INSERT INTO app_user (id, username, display_name, password_hash, admin, active, created_at)
                        VALUES (:id, :username, :displayName, :passwordHash, :admin, 1, :now)
                        ON CONFLICT (username) DO NOTHING
                        """)
                .param("id", id)
                .param("username", username)
                .param("displayName", displayName)
                .param("passwordHash", passwordHash)
                .param("admin", admin ? 1 : 0)
                .param("now", now)
                .update() == 1;
    }

    public void updateDisplayName(String id, String displayName) {
        jdbc.sql("UPDATE app_user SET display_name = :displayName WHERE id = :id")
                .param("displayName", displayName)
                .param("id", id)
                .update();
    }

    public void updatePassword(String id, String passwordHash) {
        jdbc.sql("UPDATE app_user SET password_hash = :hash WHERE id = :id")
                .param("hash", passwordHash)
                .param("id", id)
                .update();
    }

    /**
     * Sets the administrator right. Taking it away only happens while another active administrator exists; the
     * check and the update are one statement, so two parallel requests cannot remove the last two.
     *
     * @return {@code false} if the change would leave no active administrator
     */
    public boolean updateAdmin(String id, boolean admin) {
        return jdbc.sql("""
                        UPDATE app_user SET admin = :admin
                        WHERE id = :id AND (:admin = 1 OR admin = 0 OR active = 0 OR
                              (SELECT COUNT(*) FROM app_user WHERE admin = 1 AND active = 1 AND id <> :id) > 0)
                        """)
                .param("admin", admin ? 1 : 0)
                .param("id", id)
                .update() == 1;
    }

    /**
     * Activates or deactivates a user; like {@link #updateAdmin} it never deactivates the last active administrator.
     *
     * @return {@code false} if the change would leave no active administrator
     */
    public boolean updateActive(String id, boolean active) {
        return jdbc.sql("""
                        UPDATE app_user SET active = :active
                        WHERE id = :id AND (:active = 1 OR admin = 0 OR active = 0 OR
                              (SELECT COUNT(*) FROM app_user WHERE admin = 1 AND active = 1 AND id <> :id) > 0)
                        """)
                .param("active", active ? 1 : 0)
                .param("id", id)
                .update() == 1;
    }

    public void updateLastLogin(String username, String now) {
        jdbc.sql("UPDATE app_user SET last_login_at = :now WHERE username = :username")
                .param("now", now)
                .param("username", username)
                .update();
    }
}
