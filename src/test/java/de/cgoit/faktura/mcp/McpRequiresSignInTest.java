package de.cgoit.faktura.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import de.cgoit.faktura.FakturaApplication;

/**
 * MCP without a sign-in mode is refused at startup: the endpoint must bypass the proxy, and the page that mints
 * tokens would then rest on the proxy alone.
 */
class McpRequiresSignInTest {

    @Test
    void startupFailsWithMcpAndNoSignInMode() {
        try (var ignored = new SpringApplicationBuilder(FakturaApplication.class)
                .run("--spring.datasource.url=jdbc:sqlite:file:faktura-test-mcp-none?mode=memory&cache=shared",
                        "--faktura.archive.path=target/test-archive", "--faktura.backup.path=target/test-backup",
                        "--faktura.recurring.enabled=false", "--faktura.notification.enabled=false",
                        "--server.port=0", "--faktura.mcp.enabled=true", "--faktura.security.mode=NONE")) {
            assertThat(ignored).as("the application must not start").isNull();
        } catch (Exception e) {
            assertThat(rootMessages(e)).contains("faktura.security.mode");
        }
    }

    private static String rootMessages(Throwable e) {
        var text = new StringBuilder();

        for (var t = e; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append('\n');
        }

        return text.toString();
    }
}
