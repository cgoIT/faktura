package de.cgoit.faktura.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class DatabaseDirectoryTest {

    @TempDir
    Path temp;

    @Test
    void missingSqliteDirectoryIsCreated() {
        var directory = temp.resolve("data").resolve("nested");

        DatabaseDirectory.ensure(environment("jdbc:sqlite:" + directory.resolve("faktura.db")));

        assertThat(directory).isDirectory();
        assertThat(directory.resolve("faktura.db")).doesNotExist();
    }

    @Test
    void queryParametersAreIgnored() {
        var directory = temp.resolve("with-query");

        DatabaseDirectory.ensure(environment("jdbc:sqlite:" + directory.resolve("faktura.db") + "?busy_timeout=5000"));

        assertThat(directory).isDirectory();
    }

    @Test
    void memoryFileUriAndOtherDatabasesAreLeftAlone() {
        DatabaseDirectory.ensure(environment("jdbc:sqlite::memory:"));
        DatabaseDirectory.ensure(environment("jdbc:sqlite:file:memdb?mode=memory&cache=shared"));
        DatabaseDirectory.ensure(environment("jdbc:postgresql://localhost/faktura"));
        DatabaseDirectory.ensure(environment(""));

        assertThat(temp).isEmptyDirectory();
    }

    private static MockEnvironment environment(String url) {
        return new MockEnvironment().withProperty("spring.datasource.url", url);
    }
}
