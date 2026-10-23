package de.cgoit.faktura.archive.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Pattern;

import de.cgoit.faktura.archive.ArchiveVerifier;

/**
 * Writes copies into the flat export directory. A copy is written to a hidden {@code .part} file, checked against the
 * expected hash and then renamed, so a watching program never sees a half-written file. An existing file is never
 * overwritten and nothing in the export directory is deleted except the {@code .part} file of a failed attempt.
 */
public final class ExportFiles {

    /**
     * What happened to a copy.
     */
    public enum Outcome {
        WRITTEN, ALREADY_PRESENT
    }

    /**
     * The target name is taken by a file with different content.
     */
    public static final class ConflictException extends IOException {

        public ConflictException(String message) {
            super(message);
        }
    }

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    private static final String PART_SUFFIX = ".part";

    private ExportFiles() {
    }

    /**
     * Derives the flat file name from an archive path: its last segment, e.g. {@code RE202700001.pdf} for
     * {@code 2027/RE202700001.pdf}.
     *
     * @throws IllegalArgumentException if the name contains anything but letters, digits, {@code . _ -}
     */
    public static String exportName(String archivePath) {
        var name = archivePath.substring(archivePath.lastIndexOf('/') + 1);

        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe export name: " + name);
        }

        return name;
    }

    /**
     * Writes the content as {@code name} into the directory unless a file with the same content exists.
     *
     * @throws ConflictException if a file with that name and a different hash exists
     * @throws IOException if the directory is missing or not writable, or the written copy does not match the hash
     */
    public static Outcome write(Path directory, String name, byte[] content, String sha256) throws IOException {
        var root = directory.toAbsolutePath().normalize();
        var target = root.resolve(name).normalize();

        if (!root.equals(target.getParent()) || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe export name: " + name);
        }

        if (!Files.isDirectory(root)) {
            throw new IOException("Export directory does not exist");
        }

        if (Files.exists(target)) {
            if (ArchiveVerifier.sha256(target).equals(sha256)) {
                return Outcome.ALREADY_PRESENT;
            }

            throw new ConflictException("Export file exists with different content: " + name);
        }

        var part = root.resolve("." + name + "." + UUID.randomUUID() + PART_SUFFIX);

        try {
            Files.write(part, content, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);

            if (!ArchiveVerifier.sha256(part).equals(sha256)) {
                throw new IOException("Export copy does not match its hash: " + name);
            }

            Files.move(part, target);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }

            throw e;
        }

        return Outcome.WRITTEN;
    }
}
