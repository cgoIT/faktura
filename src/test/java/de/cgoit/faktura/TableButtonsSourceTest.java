package de.cgoit.faktura;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Buttons inside table rows are icon buttons; text buttons belong outside of tables.
 */
class TableButtonsSourceTest {

    private static final Pattern TABLE = Pattern.compile("(?s)<table.*?</table>");
    private static final Pattern DIALOG = Pattern.compile("(?s)<dialog.*?</dialog>");
    private static final Pattern TEXT_BUTTON = Pattern.compile("<button(?![^>]*icon-button)[^>]*>|class=\"button[ \"]");

    @Test
    void tablesContainOnlyIconButtons() throws Exception {
        try (var files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".html")).toList()) {
                var html = Files.readString(file);
                var matcher = TABLE.matcher(html);

                while (matcher.find()) {
                    var table = DIALOG.matcher(matcher.group()).replaceAll("");

                    assertThat(TEXT_BUTTON.matcher(table).find())
                            .as("text button in a table of %s", file)
                            .isFalse();
                }
            }
        }
    }
}
