package de.cgoit.faktura.archive;

import java.util.List;

/**
 * Result of comparing the files of an archive directory with the entries of the database.
 *
 * @param checked      number of entries that were checked
 * @param problems     entries whose file is missing or differs from the stored size or hash
 * @param unknownFiles files in the archive directory that no entry refers to (informational, not a failure)
 */
public record ArchiveVerification(int checked, List<Problem> problems, List<String> unknownFiles) {

    /**
     * Kind of problem found for an entry.
     */
    public enum Kind {
        MISSING, SIZE_MISMATCH, HASH_MISMATCH
    }

    /**
     * An entry whose file does not match.
     */
    public record Problem(String id, String path, Kind kind) {
    }

    /**
     * Tells whether every entry has a matching file.
     */
    public boolean ok() {
        return problems.isEmpty();
    }
}
