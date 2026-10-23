package de.cgoit.faktura.archive;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Outcome of one backup run.
 *
 * @param database         the database dump
 * @param manifest         the manifest next to it
 * @param archiveFiles     number of files in the archive mirror after the run
 * @param newArchiveFiles  number of files copied in this run
 * @param verification     result of checking the mirror against the dump, {@code null} if verification is off
 */
public record BackupResult(
        Instant createdAt,
        Path database,
        Path manifest,
        int archiveFiles,
        int newArchiveFiles,
        ArchiveVerification verification) {

    /**
     * Tells whether the backup can be trusted: it was verified without problems, or verification is off.
     */
    public boolean ok() {
        return verification == null || verification.ok();
    }
}
