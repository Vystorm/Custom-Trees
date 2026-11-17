package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HerbariumFileTest {
    @TempDir Path folder;

    @Test void missingFileIsAnEmptyHerbarium() {
        assertNotNull(HerbariumFeature.read(folder.resolve("herbarium.yml"), Logger.getAnonymousLogger()));
    }

    @Test void unreadableFileIsMovedAsideUnchangedAndReportedAsBroken() throws Exception {
        Path file = folder.resolve("herbarium.yml");
        String content = "players:\n  x: [unclosed\n";
        Files.writeString(file, content);
        assertNull(HerbariumFeature.read(file, Logger.getAnonymousLogger()), "broken file must switch the herbarium to read-only");
        assertFalse(Files.exists(file));
        List<Path> aside;
        try (var files = Files.list(folder)) { aside = files.toList(); }
        assertEquals(1, aside.size());
        assertTrue(aside.getFirst().getFileName().toString().startsWith("herbarium.yml.broken-"));
        assertEquals(content, Files.readString(aside.getFirst()));
    }
}
