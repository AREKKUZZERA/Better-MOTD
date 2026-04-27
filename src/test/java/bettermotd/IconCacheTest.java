package bettermotd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class IconCacheTest {

    @Test
    void normalizesSimplePngNameIntoIconsDirectory() {
        assertEquals("icons/server.png", IconCache.normalizeIconPath("server.png"));
    }

    @Test
    void rejectsTraversalAndAbsoluteIconPaths() {
        assertNull(IconCache.normalizeIconPath("../server-icon.png"));
        assertNull(IconCache.normalizeIconPath("icons/../../server-icon.png"));
        assertNull(IconCache.normalizeIconPath("/tmp/server-icon.png"));
        assertNull(IconCache.normalizeIconPath("C:/tmp/server-icon.png"));
    }

    @Test
    void rejectsNonPngIconPaths() {
        assertNull(IconCache.normalizeIconPath("icons/readme.txt"));
    }
}
