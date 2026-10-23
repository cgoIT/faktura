package de.cgoit.faktura.archive.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Locations of the archive directory ({@code faktura.archive.path} in application.yml) and of the optional export
 * directory ({@code faktura.archive.export-path}; empty means no export).
 */
@ConfigurationProperties("faktura.archive")
public record ArchiveProperties(String path, String exportPath) {

    /**
     * Tells whether finalized PDFs are also copied to an export directory.
     */
    public boolean exportEnabled() {
        return exportPath != null && !exportPath.isBlank();
    }
}
