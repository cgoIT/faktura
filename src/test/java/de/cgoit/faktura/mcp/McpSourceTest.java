package de.cgoit.faktura.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The MCP tools may read and create drafts. Whatever cannot be undone stays with a person in the web interface.
 */
class McpSourceTest {

    @Test
    void theToolsNeverFinalizeCancelPayMailOrDelete() throws IOException {
        var source = Files.readString(Path.of("src/main/java/de/cgoit/faktura/mcp/internal/McpTools.java"));

        assertThat(source)
                .doesNotContain("FinalizeService")
                .doesNotContain("PaymentService")
                .doesNotContain("MailService")
                .doesNotContain("deleteDraft")
                .doesNotContain("cancelInvoice")
                .doesNotContain("DunningService.create");
    }
}
