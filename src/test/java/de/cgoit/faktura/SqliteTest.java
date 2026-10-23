package de.cgoit.faktura;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import de.cgoit.faktura.mail.TestMailConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full application context on a shared in-memory SQLite database. All tests using this annotation share one
 * cached context, so tests clean the tables they touch.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:faktura-test?mode=memory&cache=shared",
        "faktura.archive.path=target/test-archive", "faktura.backup.path=target/test-backup", "faktura.recurring.enabled=false", "faktura.notification.enabled=false",
        "faktura.mail.enabled=true", "spring.mail.host=smtp.example.test", "faktura.mail.from=rechnung@example.test" })
@Import({ TestMailConfiguration.class, TestSecurityConfiguration.class })
@AutoConfigureMockMvc
public @interface SqliteTest {
}
