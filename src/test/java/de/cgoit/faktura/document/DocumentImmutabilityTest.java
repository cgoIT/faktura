package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the GoBD rule that finalized documents are never changed or deleted: every UPDATE or DELETE on a document
 * table in the production code must be limited to drafts.
 */
class DocumentImmutabilityTest {

    private static final Pattern WRITE = Pattern.compile(
            "(UPDATE\\s+document\\b|DELETE\\s+FROM\\s+document(_item|_tax_total)?\\b)", Pattern.CASE_INSENSITIVE);

    @Test
    void everyUpdateOrDeleteOfDocumentDataIsLimitedToDrafts() throws IOException {
        var violations = new ArrayList<String>();
        var statements = 0;

        try (var files = Files.walk(Path.of("src/main"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var name = file.toString();

                if (!name.endsWith(".java") && !name.endsWith(".sql")) {
                    continue;
                }

                var text = Files.readString(file);
                var matcher = WRITE.matcher(text);

                while (matcher.find()) {
                    statements++;
                    var end = text.indexOf(".update()", matcher.start());
                    var statement = text.substring(matcher.start(), end < 0 ? text.length() : end);

                    if (!statement.contains("status = 'DRAFT'")) {
                        violations.add(file + ": " + statement.lines().findFirst().orElse(""));
                    }
                }
            }
        }

        assertThat(statements).as("statements found by the scan").isGreaterThan(0);
        assertThat(violations).isEmpty();
    }

    @Test
    void migrationsDoNotChangeOrDeleteDocumentRows() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (var file : files.toList()) {
                assertThat(WRITE.matcher(Files.readString(file)).find()).as(file.toString()).isFalse();
            }
        }
    }

    @Test
    void archiveFilesAuditLogPaymentsDunningsMailLogNotificationLogAndOfferEventsAreAppendOnly() throws IOException {
        var appendOnly = Pattern.compile("(UPDATE\\s+(file|file_export|audit_log|payment|dunning|mail_log|notification_log|offer_event)\\b|DELETE\\s+FROM\\s+(file|file_export|audit_log|payment|dunning|mail_log|notification_log|offer_event)\\b)",
                Pattern.CASE_INSENSITIVE);
        var violations = new ArrayList<String>();

        try (var files = Files.walk(Path.of("src/main"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var name = file.toString();

                if ((name.endsWith(".java") || name.endsWith(".sql")) && appendOnly.matcher(Files.readString(file)).find()) {
                    violations.add(name);
                }
            }
        }

        assertThat(violations).isEmpty();
    }
}
