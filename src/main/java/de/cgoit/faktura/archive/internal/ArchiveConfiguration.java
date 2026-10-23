package de.cgoit.faktura.archive.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the archive configuration properties.
 */
@Configuration
@EnableConfigurationProperties({ ArchiveProperties.class, BackupProperties.class })
public class ArchiveConfiguration {
}
