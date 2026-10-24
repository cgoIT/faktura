package de.cgoit.faktura.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import de.cgoit.faktura.archive.internal.ArchiveProperties;
import de.cgoit.faktura.archive.internal.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stores finalized documents (PDF, XML) in the archive directory. A file is written to a staging directory first,
 * its metadata and SHA-256 hash are stored in the caller's transaction, and only after the commit it is moved to its
 * final path. If the transaction rolls back, the staged file is deleted. Files in the archive are never overwritten
 * or deleted; every read verifies the hash.
 */
@Service
public class ArchiveService {

    static final String STAGING_DIRECTORY = ".staging";
    static final String STAGING_SUFFIX = ".tmp";

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveService.class);
    private static final Pattern SAFE_PATH = Pattern
            .compile("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

    private final FileRepository files;
    private final ArchiveProperties properties;
    private final Clock clock;
    private final ObjectProvider<ArchiveExportService> exports;

    public ArchiveService(FileRepository files, ArchiveProperties properties, Clock clock,
            ObjectProvider<ArchiveExportService> exports) {
        this.files = files;
        this.properties = properties;
        this.clock = clock;
        this.exports = exports;
    }

    /**
     * Stores a file for an owner. The file becomes visible at its final path only if the surrounding transaction
     * commits; until then it exists only in the staging directory.
     *
     * @param relativePath path inside the archive directory, separated by {@code /}, e.g. {@code 2027/RE202700001.pdf}
     * @throws IllegalArgumentException if the path is not a safe relative path
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ArchivedFile store(ArchiveOwnerType ownerType, String ownerId, ArchiveFileType type, String relativePath,
            byte[] content) {
        if (!SAFE_PATH.matcher(relativePath).matches()) {
            throw new IllegalArgumentException("Unsafe archive path: " + relativePath);
        }

        var file = new ArchivedFile(UUID.randomUUID().toString(), ownerType, ownerId, type, relativePath,
                content.length, sha256(content), Instant.now(clock));
        var staged = stagingFile(file.id());

        try {
            Files.createDirectories(staged.getParent());
            Files.write(staged, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stage archive file " + file.id(), e);
        }

        files.insert(file);
        TransactionSynchronizationManager.registerSynchronization(new Publish(file));

        return file;
    }

    /**
     * Turns a document number into a safe file name without extension. Characters outside {@code A-Z a-z 0-9 . _ -}
     * and a leading dot become {@code _}; then a part of the owner id is appended, so two different numbers never
     * share a path.
     */
    public static String fileName(String number, String ownerId) {
        var safe = number.replaceAll("[^A-Za-z0-9._-]|^\\.", "_");

        return safe.equals(number) ? safe : safe + "-" + ownerId.substring(0, 8);
    }

    @Transactional(readOnly = true)
    public Optional<ArchivedFile> findById(String id) {
        return files.findById(id);
    }

    /**
     * Lists the files of an owner, oldest first.
     */
    @Transactional(readOnly = true)
    public List<ArchivedFile> findByOwner(ArchiveOwnerType ownerType, String ownerId) {
        return files.findByOwner(ownerType, ownerId);
    }

    /**
     * Reads the content of a file and verifies it against the stored hash.
     *
     * @throws ArchiveIntegrityException if the file is missing or was changed
     */
    public byte[] read(ArchivedFile file) {
        var path = finalFile(file);

        if (Files.notExists(path)) {
            publish(file);
        }

        if (Files.notExists(path)) {
            throw new ArchiveIntegrityException("Archive file is missing: " + file.id());
        }

        try {
            var content = Files.readAllBytes(path);

            if (!sha256(content).equals(file.sha256())) {
                throw new ArchiveIntegrityException("Archive file does not match its hash: " + file.id());
            }

            return content;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read archive file " + file.id(), e);
        }
    }

    /**
     * Completes the move of a staged file whose transaction committed but whose move did not happen (e.g. a crash
     * between commit and move). Without a staged file it does nothing.
     */
    void publish(ArchivedFile file) {
        var staged = stagingFile(file.id());
        var target = finalFile(file);

        if (Files.notExists(staged)) {
            return;
        }

        try {
            if (Files.exists(target)) {
                throw new IllegalStateException("Archive path already exists: " + file.id());
            }

            Files.createDirectories(target.getParent());
            move(staged, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot publish archive file " + file.id(), e);
        }
    }

    /**
     * Cleans up the staging directory at startup: a staged file whose metadata was committed is moved to its final
     * path, a staged file without metadata (its transaction never committed) is removed. Final files are never
     * touched.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaged() {
        var directory = stagingDirectory();

        if (Files.notExists(directory)) {
            return;
        }

        try (var staged = Files.list(directory)) {
            for (var path : staged.filter(entry -> entry.getFileName().toString().endsWith(STAGING_SUFFIX)).toList()) {
                var name = path.getFileName().toString();
                var id = name.substring(0, name.length() - STAGING_SUFFIX.length());
                var file = files.findById(id);

                if (file.isPresent()) {
                    publish(file.get());
                } else {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            LOG.warn("Staging directory could not be cleaned up", e);
        }
    }

    Path stagingDirectory() {
        return root().resolve(STAGING_DIRECTORY);
    }

    private Path stagingFile(String id) {
        return stagingDirectory().resolve(id + STAGING_SUFFIX);
    }

    private Path finalFile(ArchivedFile file) {
        return root().resolve(file.path());
    }

    private Path root() {
        return Path.of(properties.path()).toAbsolutePath().normalize();
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Moves the staged file after the commit and removes it after a rollback.
     */
    private final class Publish implements TransactionSynchronization {

        private final ArchivedFile file;

        private Publish(ArchivedFile file) {
            this.file = file;
        }

        @Override
        public void afterCommit() {
            try {
                publish(file);
            } catch (RuntimeException e) {
                // The row is committed; the staged file stays and is completed on the next read or start.
                LOG.error("Archive file {} could not be published, it stays staged", file.id(), e);

                return;
            }

            if (file.type() == ArchiveFileType.PDF) {
                // The export copy is made in the background and never affects the committed document.
                exports.ifAvailable(ArchiveExportService::requestExport);
            }
        }

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
                return;
            }

            try {
                Files.deleteIfExists(stagingFile(file.id()));
            } catch (IOException e) {
                LOG.warn("Staged archive file {} could not be removed", file.id(), e);
            }
        }
    }
}
