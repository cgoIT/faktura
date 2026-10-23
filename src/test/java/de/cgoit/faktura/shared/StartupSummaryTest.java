package de.cgoit.faktura.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.env.MockEnvironment;

class StartupSummaryTest {

    @Test
    void secretsNeverAppearInTheSummary() {
        var environment = new MockEnvironment()
                .withProperty("faktura.security.mode", "FORM")
                .withProperty("faktura.security.admin-password", "very-secret-admin")
                .withProperty("spring.datasource.url", "jdbc:postgresql://dbuser:dbpass@db:5432/faktura?password=leak")
                .withProperty("spring.datasource.password", "dbsecret")
                .withProperty("faktura.mail.enabled", "true")
                .withProperty("faktura.mail.from", "billing@example.org")
                .withProperty("spring.mail.host", "smtp.example.org")
                .withProperty("spring.mail.port", "587")
                .withProperty("spring.mail.password", "mailsecret");

        var text = String.join("\n", summary(environment).describe());

        assertThat(text).contains("PostgreSQL db:5432/faktura", "smtp.example.org:587", "sign-in        FORM");
        assertThat(text).doesNotContain("very-secret-admin", "dbpass", "dbsecret", "leak", "mailsecret", "billing@");
    }

    @Test
    void signInOffIsWarnedAbout() {
        var environment = new MockEnvironment().withProperty("faktura.security.mode", "NONE");

        assertThat(summary(environment).describe()).anyMatch(line -> line.startsWith("WARNING"));
    }

    @Test
    void mailEnabledWithoutHostIsWarnedAbout() {
        var environment = new MockEnvironment()
                .withProperty("faktura.security.mode", "FORM")
                .withProperty("faktura.mail.enabled", "true");

        assertThat(summary(environment).describe()).anyMatch(line -> line.contains("host or sender is missing"));
    }

    @Test
    void databaseUrlsAreReduced() {
        assertThat(StartupSummary.database("jdbc:postgresql://u:p@host/app?x=1")).isEqualTo("PostgreSQL host/app");
        assertThat(StartupSummary.database("jdbc:sqlite:./data/faktura.db")).startsWith("SQLite ").endsWith("data/faktura.db");
        assertThat(StartupSummary.database("")).isEqualTo("not set");
    }

    private static StartupSummary summary(MockEnvironment environment) {
        return new StartupSummary(environment, new DefaultListableBeanFactory().getBeanProvider(BuildProperties.class));
    }
}
