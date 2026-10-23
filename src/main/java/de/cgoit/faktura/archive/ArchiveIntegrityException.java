package de.cgoit.faktura.archive;

/**
 * Thrown when an archived file is missing or its content no longer matches the stored SHA-256 hash.
 */
public class ArchiveIntegrityException extends RuntimeException {

    public ArchiveIntegrityException(String message) {
        super(message);
    }
}
