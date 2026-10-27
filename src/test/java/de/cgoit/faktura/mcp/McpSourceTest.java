package de.cgoit.faktura.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The MCP tools may read and create drafts. Whatever cannot be undone stays with a person in the web interface. The
 * test lists what the tools may call; a new service or method has to be added here on purpose, after asking whether it
 * is reading or creating a draft.
 */
class McpSourceTest {

    private static final Set<String> SERVICES = Set.of("CustomerService", "CatalogService", "InvoiceService",
            "OfferService", "DunningService", "TextTemplateService");

    private static final Set<String> CALLS = Set.of("customers.search", "customers.find", "catalog.search",
            "invoices.list", "invoices.find", "invoices.proposeItem", "invoices.createDraft",
            "invoices.createOfferDraft", "dunning.suggestions", "offers.expiringSoon", "texts.defaultBody");

    private static final Pattern FIELD = Pattern.compile("private final (\\w+Service) (\\w+);");

    @Test
    void theToolsOnlyCallTheListedServiceMethods() throws IOException {
        var source = Files.readString(Path.of("src/main/java/de/cgoit/faktura/mcp/internal/McpTools.java"));
        var types = new TreeSet<String>();
        var calls = new TreeSet<String>();
        var fields = FIELD.matcher(source);

        while (fields.find()) {
            types.add(fields.group(1));

            var call = Pattern.compile("\\b" + fields.group(2) + "\\.(\\w+)\\(").matcher(source);

            while (call.find()) {
                calls.add(fields.group(2) + "." + call.group(1));
            }
        }

        assertThat(types).isSubsetOf(SERVICES);
        assertThat(calls).isNotEmpty().isSubsetOf(CALLS);
    }

    @Test
    void theToolsDoNotReachTheServicesInAnotherWay() throws IOException {
        var source = Files.readString(Path.of("src/main/java/de/cgoit/faktura/mcp/internal/McpTools.java"));

        assertThat(source)
                .doesNotContain("FinalizeService")
                .doesNotContain("PaymentService")
                .doesNotContain("MailService")
                .doesNotContain("ApplicationContext")
                .doesNotContain("JdbcClient");
    }
}
