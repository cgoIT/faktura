package de.cgoit.faktura.archive;

/**
 * State of the export directory feature.
 *
 * @param enabled whether an export directory is configured
 * @param open    archived PDFs that were not copied yet
 */
public record ExportStatus(boolean enabled, long open) {
}
