package de.cgoit.faktura.archive;

import java.time.Instant;

/**
 * Metadata of an archived file. The content is immutable; {@code sha256} is the hash of the content when it was
 * stored and is verified every time the file is read.
 *
 * @param path relative path inside the archive directory, separated by {@code /}
 */
public record ArchivedFile(
        String id,
        ArchiveOwnerType ownerType,
        String ownerId,
        ArchiveFileType type,
        String path,
        long sizeBytes,
        String sha256,
        Instant createdAt) {
}
