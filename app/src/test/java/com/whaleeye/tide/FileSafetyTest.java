package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class FileSafetyTest {
    @Test
    public void stripsPathComponentsFromDisplayName() {
        assertEquals("passwd", FileSafety.sanitizeFileName("../../etc/passwd"));
        assertEquals("report.pdf", FileSafety.sanitizeFileName("..\\..\\report.pdf"));
    }

    @Test
    public void removesControlsAndFallsBackForEmptyNames() {
        assertEquals("ab.txt", FileSafety.sanitizeFileName("a\u0000b\u001f.txt"));
        assertEquals(FileSafety.DEFAULT_FILE_NAME, FileSafety.sanitizeFileName("../"));
        assertEquals(FileSafety.DEFAULT_FILE_NAME, FileSafety.sanitizeFileName(".."));
    }

    @Test
    public void preservesExtensionWhenTruncating() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 260; i++) name.append('a');
        name.append(".pdf");
        String cleaned = FileSafety.sanitizeFileName(name.toString());
        assertTrue(cleaned.length() <= 200);
        assertTrue(cleaned.endsWith(".pdf"));
    }

    @Test
    public void normalizesMimeType() {
        assertEquals("image/jpeg", FileSafety.normalizeMimeType(" Image/JPEG; charset=binary "));
        assertEquals(FileSafety.DEFAULT_MIME_TYPE, FileSafety.normalizeMimeType("garbage"));
        assertEquals(FileSafety.DEFAULT_MIME_TYPE, FileSafety.normalizeMimeType(null));
    }

    @Test
    public void canonicalPathRejectsEscape() throws Exception {
        File root = tempDir();
        File inside = new File(root, "ok.txt");
        File outside = new File(root.getParentFile(), "outside-" + UUID.randomUUID() + ".txt");
        assertTrue(FileSafety.isWithin(root, inside));
        assertFalse(FileSafety.isWithin(root, outside));
        assertFalse(FileSafety.isWithin(root, root));
        assertFalse(FileSafety.isWithin(root, new File(root, "../outside.txt")));
        // childOf 先清洗掉 ../；落入 root 内的名字仍要能正常解析。
        assertEquals("outside.txt", FileSafety.childOf(root, "../outside.txt").getName());
    }

    @Test
    public void formatsSizes() {
        assertEquals("12 B", FileSafety.formatSize(12));
        assertEquals("1.0 KB", FileSafety.formatSize(1024));
        assertEquals("20.0 MB", FileSafety.formatSize(FileSafety.MAX_UPLOAD_BYTES));
    }

    private static File tempDir() throws IOException {
        File base = new File(System.getProperty("user.dir"), "build/tmp");
        if (!base.isDirectory() && !base.mkdirs()) {
            fail("cannot create build temp dir: " + base);
        }
        File dir = new File(base, "tide-file-safety-" + UUID.randomUUID());
        if (!dir.mkdirs() && !dir.isDirectory()) {
            fail("cannot create temp dir: " + dir);
        }
        dir.deleteOnExit();
        return dir.getCanonicalFile();
    }
}
