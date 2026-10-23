package de.cgoit.faktura.shared;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs the effective configuration once the application is up, so an operator can check that the environment
 * variables arrived. Secrets (passwords, user names, mail credentials), customer data and the content of the
 * database are never part of it; a database URL is reduced to its driver and location.
 */
@Component
public class StartupSummary {

    private static final Logger LOG = LoggerFactory.getLogger(StartupSummary.class);

    private final Environment environment;
    private final ObjectProvider<BuildProperties> build;

    public StartupSummary(Environment environment, ObjectProvider<BuildProperties> build) {
        this.environment = environment;
        this.build = build;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logSummary() {
        LOG.info("Effective configuration:\n  {}", String.join("\n  ", describe()));
    }

    /**
     * Describes the effective settings as one line per topic. Warnings for risky combinations start with
     * {@code WARNING}.
     */
    List<String> describe() {
        var lines = new ArrayList<String>();
        var mode = value("faktura.security.mode", "NONE");

        lines.add("version        " + version() + ", Java " + System.getProperty("java.version")
                + ", time zone " + ZoneId.systemDefault());
        lines.add("profiles       " + profiles());
        lines.add("port           " + value("server.port", "?"));
        lines.add("database       " + database(value("spring.datasource.url", "")));
        lines.add("archive        " + resolved(value("faktura.archive.path", "")));
        lines.add("export folder  " + (blank("faktura.archive.export-path") ? "off"
                : resolved(value("faktura.archive.export-path", ""))));
        lines.add("backup         " + backup());
        lines.add("sign-in        " + mode);
        lines.add("mail           " + mail());
        lines.add("notifications  " + onOff("faktura.notification.enabled")
                + ", base URL " + (blank("faktura.base-url") ? "not set" : value("faktura.base-url", "")));
        lines.add("recurring job  " + onOff("faktura.recurring.enabled") + ", cron " + value("faktura.recurring.cron", "?"));
        lines.add("logo           " + (blank("faktura.render.logo-path") ? "placeholder"
                : resolved(value("faktura.render.logo-path", ""))));

        if ("NONE".equals(mode)) {
            lines.add("WARNING        sign-in is off (SECURITY_MODE=NONE): safe only behind a proxy that "
                    + "authenticates every request; otherwise use SECURITY_MODE=FORM");
        }

        if (!"NONE".equals(mode) && !"FORM".equals(mode)) {
            lines.add("proxy header   " + value("faktura.security.header", "?") + " from "
                    + value("faktura.security.trusted-proxies", "?"));
        }

        if (isTrue("faktura.mail.enabled") && (blank("spring.mail.host") || blank("faktura.mail.from"))) {
            lines.add("WARNING        mail is enabled but host or sender is missing");
        }

        return lines;
    }

    private String version() {
        var properties = build.getIfAvailable();

        return properties == null ? "dev (no build info)" : properties.getVersion();
    }

    private String profiles() {
        var active = environment.getActiveProfiles();

        return active.length == 0 ? "default" : String.join(", ", active);
    }

    private String backup() {
        if (!isTrue("faktura.backup.enabled")) {
            return "off";
        }

        return "on, cron " + value("faktura.backup.cron", "?") + ", keep " + value("faktura.backup.keep-count", "?")
                + ", path " + resolved(value("faktura.backup.path", ""));
    }

    private String mail() {
        if (!isTrue("faktura.mail.enabled")) {
            return "off";
        }

        return "on, server " + value("spring.mail.host", "?") + ":" + value("spring.mail.port", "?")
                + ", sender " + (blank("faktura.mail.from") ? "not set" : "set");
    }

    private String onOff(String key) {
        return isTrue(key) ? "on" : "off";
    }

    private boolean isTrue(String key) {
        return Boolean.parseBoolean(value(key, "false"));
    }

    private boolean blank(String key) {
        return value(key, "").isBlank();
    }

    private String value(String key, String fallback) {
        var found = environment.getProperty(key);

        return found == null ? fallback : found;
    }

    private static String resolved(String path) {
        return path.isBlank() ? "not set" : Path.of(path).toAbsolutePath().normalize().toString();
    }

    /**
     * Reduces a JDBC URL to driver and location, without user information or query parameters.
     */
    static String database(String url) {
        if (url.startsWith("jdbc:sqlite:")) {
            return "SQLite " + resolved(url.substring("jdbc:sqlite:".length()));
        }

        if (url.startsWith("jdbc:postgresql:")) {
            var rest = url.substring("jdbc:postgresql:".length());
            var end = rest.indexOf('?');
            var location = end < 0 ? rest : rest.substring(0, end);
            var at = location.indexOf('@');

            return "PostgreSQL " + (at < 0 ? location : location.substring(at + 1));
        }

        return url.isBlank() ? "not set" : "other";
    }
}
