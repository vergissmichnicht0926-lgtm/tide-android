package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class SinceCursorTest {
    @Test
    public void emptyAndZeroSeq() {
        assertEquals("", SinceCursor.build(null));
        Map<String, long[]> cursors = new LinkedHashMap<>();
        cursors.put("a", new long[]{1, 0});
        assertEquals("", SinceCursor.build(cursors));
    }

    @Test
    public void formatsExactCursorAndActiveFirst() {
        Map<String, long[]> cursors = new LinkedHashMap<>();
        cursors.put("a", new long[]{1, 7});
        cursors.put("b", new long[]{2, 9});
        String value = SinceCursor.build(cursors, "a");
        assertEquals("a:1:7,b:2:9", value);
        assertEquals("b:2:9,a:1:7", SinceCursor.build(cursors, "b"));
    }

    @Test
    public void capsAtHundredEntries() {
        Map<String, long[]> cursors = new LinkedHashMap<>();
        for (int i = 0; i < 120; i++) {
            cursors.put("c" + i, new long[]{1, i + 1});
        }
        String value = SinceCursor.build(cursors);
        assertTrue(value.split(",").length <= 100);
    }

    @Test
    public void capsAtEightThousandCharacters() {
        Map<String, long[]> cursors = new LinkedHashMap<>();
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 80; i++) id.append('c');
        String longId = id.toString();
        for (int i = 0; i < 100; i++) {
            cursors.put(longId + i, new long[]{1, i + 1});
        }
        assertTrue(SinceCursor.build(cursors).length() <= SinceCursor.MAX_CHARS);
    }
}
