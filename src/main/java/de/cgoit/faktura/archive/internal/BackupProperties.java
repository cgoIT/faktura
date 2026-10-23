package de.cgoit.faktura.archive.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the backup ({@code faktura.backup.*}).
 *
 * @param enabled   whether the nightly backup job runs
 * @param cron      schedule of the job (Spring cron syntax)
 * @param path      target directory; it receives database dumps, a mirror of the archive and manifests
 * @param keepCount how many database dumps (with their manifests) are kept; older ones are deleted. The archive
 *                  mirror is never deleted from.
 * @param verify    whether every backup is verified against its database dump
 */
@ConfigurationProperties("faktura.backup")
public record BackupProperties(boolean enabled, String cron, String path, int keepCount, boolean verify) {
}
