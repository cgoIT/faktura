package de.cgoit.faktura.archive.internal;

import java.time.Instant;
import java.util.List;

import de.cgoit.faktura.archive.ArchivedFile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the export markers. The table is append-only: this class only inserts and reads.
 */
@Repository
public class FileExportRepository {

    private static final String PENDING = """
            FROM file f
            WHERE f.type = 'PDF'
              AND NOT EXISTS (SELECT 1 FROM file_export e WHERE e.file_id = f.id)
            """;

    private final JdbcClient jdbc;

    public FileExportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lists the archived PDFs that have no export marker yet, oldest first.
     */
    public List<ArchivedFile> findPending() {
        return jdbc.sql("""
                        SELECT f.id, f.owner_type, f.owner_id, f.type, f.path, f.size_bytes, f.sha256, f.created_at
                        """ + PENDING + " ORDER BY f.created_at, f.path")
                .query(FileRepository::map)
                .list();
    }

    /**
     * Counts archived PDFs without an export marker that were stored at or before the cutoff, so a copy that is still
     * being made in the background does not count as open.
     */
    public long countPendingUntil(Instant cutoff) {
        return jdbc.sql("SELECT COUNT(*) " + PENDING + " AND f.created_at <= :cutoff")
                .param("cutoff", cutoff.toString())
                .query(Long.class)
                .single();
    }

    /**
     * Remembers that the file was copied; does nothing if it is already remembered.
     */
    public void mark(String fileId, String sha256, Instant at) {
        jdbc.sql("""
                        INSERT INTO file_export (file_id, exported_at, sha256) VALUES (:id, :at, :sha256)
                        ON CONFLICT (file_id) DO NOTHING
                        """)
                .param("id", fileId)
                .param("at", at.toString())
                .param("sha256", sha256)
                .update();
    }
}
