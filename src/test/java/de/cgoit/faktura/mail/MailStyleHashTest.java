package de.cgoit.faktura.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.mail.internal.MailRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * The preview frame of a mail inherits the Content-Security-Policy of the page. It may only use the one style element of
 * the mail, which the policy allows by its hash. If the style changes, the hash in application.yml must change too.
 */
@SqliteTest
class MailStyleHashTest {

    @Autowired
    private MailRenderer renderer;

    @Value("${faktura.security.csp}")
    private String policy;

    @Test
    void policyAllowsTheStyleElementOfTheMail() throws Exception {
        var matcher = Pattern.compile("<style>(.*)</style>", Pattern.DOTALL).matcher(renderer.html("Subject", "Text"));

        assertThat(matcher.find()).isTrue();

        var digest = MessageDigest.getInstance("SHA-256").digest(matcher.group(1).getBytes(StandardCharsets.UTF_8));

        assertThat(policy).contains("'sha256-" + Base64.getEncoder().encodeToString(digest) + "'");
    }
}
