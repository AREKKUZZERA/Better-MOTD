package bettermotd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiniMotdImporterTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void importsLinePairsIntoImportedProfile() throws Exception {
        java.nio.file.Path source = tempDir.resolve("main.conf");
        Files.writeString(
                source,
                """
                motds=[
                  { line1="<green>One</green>" line2="<gray>Two</gray>" icon="default.png" }
                ]
                """);
        YamlConfiguration target = new YamlConfiguration();

        MiniMotdImporter.ImportResult result = MiniMotdImporter.importInto(source.toFile(), target);

        assertTrue(result.success());
        assertEquals(1, result.presets());
        assertEquals("imported", target.getString("activeProfile"));
        assertEquals(
                "minimotd-1",
                target.getMapList("profiles.imported.presets").getFirst().get("id"));
    }
}
