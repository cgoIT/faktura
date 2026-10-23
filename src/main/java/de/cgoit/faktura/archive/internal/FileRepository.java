package de.cgoit.faktura.archive.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.archive.ArchiveFileType;
import de.cgoit.faktura.archive.ArchiveOwnerType;
import de.cgoit.faktura.archive.ArchivedFile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of archive file metadata. The table is append-only: this class only inserts and reads.
 */
@Repository
public class FileRepository {

    private static final String SELECT = """
            SELECT id, owner_type, owner_id, type, path, size_bytes, sha256, created_at FROM file
            """;

    private final JdbcClient jdbc;

    public FileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ArchivedFile file) {
        jdbc.sql("""
                        INSERT INTO file (id, owner_type, owner_id, type, path, size_bytes, sha256, created_at)
                        VALUES (:id, :ownerType, :ownerId, :type, :path, :size, :sha256, :createdAt)
                        """)
                .param("id", file.id())
                .param("ownerType", file.ownerType().name())
                .param("ownerId", file.ownerId())
                .param("type", file.type().name())
                .param("path", file.path())
                .param("size", file.sizeBytes())
                .param("sha256", file.sha256())
                .param("createdAt", file.createdAt().toString())
                .update();
    }

    /**
     * Lists all archive entries, ordered by path.
     */
    public List<ArchivedFile> findAll() {
        return jdbc.sql(SELECT + " ORDER BY path")
                .query(FileRepository::map)
                .list();
    }

    public Optional<ArchivedFile> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(FileRepository::map)
                .optional();
    }

    public List<ArchivedFile> findByOwner(ArchiveOwnerType ownerType, String ownerId) {
        return jdbc.sql(SELECT + " WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY created_at, type")
                .param("ownerType", ownerType.name())
                .param("ownerId", ownerId)
                .query(FileRepository::map)
                .list();
    }

    static ArchivedFile map(ResultSet rs, int rowNum) throws SQLException {
        return new ArchivedFile(rs.getString("id"), ArchiveOwnerType.valueOf(rs.getString("owner_type")),
                rs.getString("owner_id"), ArchiveFileType.valueOf(rs.getString("type")), rs.getString("path"),
                rs.getLong("size_bytes"), rs.getString("sha256"), Instant.parse(rs.getString("created_at")));
    }
}
