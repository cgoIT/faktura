package de.cgoit.faktura.archive;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

import de.cgoit.faktura.archive.internal.ArchiveProperties;
import de.cgoit.faktura.archive.internal.FileRepository;
import org.springframework.stereotype.Service;

/**
 * Checks that the files of the archive are complete and unchanged: every entry of the {@code file} table must have
 * a file with the stored size and SHA-256 hash. Only reads; nothing in the archive or the database is changed.
 */
@Service
public class ArchiveVerifier {

    private final FileRepository files;
    private final ArchiveProperties properties;

    public ArchiveVerifier(FileRepository files, ArchiveProperties properties) {
        this.files = files;
        this.properties = properties;
    }

    /**
     * Verifies the archive directory of the application against the database of the application. The entries are
     * read first and the files are hashed afterwards, so the (single) database connection is not held meanwhile.
     */
    public ArchiveVerification verify() {
        var entries = files.findAll().stream()
                .map(ArchiveEntry::of)
                .toList();

        return verify(Path.of(properties.path()).toAbsolutePath().normalize(), entries);
    }

    /**
     * Verifies a directory against the given entries, e.g. a restored backup.
     */
    public ArchiveVerification verify(Path root, List<ArchiveEntry> entries) {
        var problems = new ArrayList<ArchiveVerification.Problem>();
        var known = new HashSet<String>();

        for (var entry : entries) {
            known.add(entry.path());

            var problem = check(root, entry);

            if (problem != null) {
                problems.add(new ArchiveVerification.Problem(entry.id(), entry.path(), problem));
            }
        }

        return new ArchiveVerification(entries.size(), List.copyOf(problems), unknownFiles(root, known));
    }

    private static ArchiveVerification.Kind check(Path root, ArchiveEntry entry) {
        var file = root.resolve(entry.path()).normalize();

        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return ArchiveVerification.Kind.MISSING;
        }

        try {
            if (Files.size(file) != entry.sizeBytes()) {
                return ArchiveVerification.Kind.SIZE_MISMATCH;
            }

            return sha256(file).equals(entry.sha256()) ? null : ArchiveVerification.Kind.HASH_MISMATCH;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read archive file " + entry.id(), e);
        }
    }

    private static List<String> unknownFiles(Path root, HashSet<String> known) {
        if (Files.notExists(root)) {
            return List.of();
        }

        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> !path.startsWith(ArchiveService.STAGING_DIRECTORY + "/"))
                    .filter(path -> !known.contains(path))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list archive directory", e);
        }
    }

    /**
     * Computes the SHA-256 of a file by streaming it.
     */
    public static String sha256(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");

            try (var stream = new DigestInputStream(input, digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
