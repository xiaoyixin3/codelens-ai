package ai.codelens.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationManifestTest {
    @TempDir Path directory;

    @Test
    void loadsContiguousMigrationsAndHashesExactContent() throws Exception {
        Files.writeString(directory.resolve("001_first.sql"), "select 1;\n");
        Files.writeString(directory.resolve("002_second.sql"), "select 2;\n");

        MigrationManifest first = MigrationManifest.load(directory);
        Files.writeString(directory.resolve("002_second.sql"), "select 3;\n");
        MigrationManifest changed = MigrationManifest.load(directory);

        assertEquals(2, first.entries().size());
        assertEquals("001_first.sql", first.entries().get(0).name());
        assertEquals(64, first.entries().get(0).checksum().length());
        assertNotEquals(first.entries().get(1).checksum(), changed.entries().get(1).checksum());
    }

    @Test
    void rejectsGapsAndUnexpectedNames() throws Exception {
        Files.writeString(directory.resolve("001_first.sql"), "select 1;");
        Files.writeString(directory.resolve("003_gap.sql"), "select 3;");

        assertThrows(IllegalArgumentException.class, () -> MigrationManifest.load(directory));
    }

    @Test
    void rejectsAnEmptyMigrationDirectory() {
        assertThrows(IllegalArgumentException.class, () -> MigrationManifest.load(directory));
    }
}
