package de.cgoit.faktura.archive;

/**
 * What the database knows about an archive file: where it is, how large it is and its SHA-256 hash.
 *
 * @param path relative path inside the archive directory, separated by {@code /}
 */
public record ArchiveEntry(String id, String path, long sizeBytes, String sha256) {

    public static ArchiveEntry of(ArchivedFile file) {
        return new ArchiveEntry(file.id(), file.path(), file.sizeBytes(), file.sha256());
    }
}
