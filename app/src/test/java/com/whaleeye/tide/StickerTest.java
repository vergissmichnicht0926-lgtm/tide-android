package com.whaleeye.tide;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StickerTest {
    private static Sticker sticker(String id, String lastUsedAt) {
        return new Sticker(id, "名字-" + id, "att-" + id, "", 0, lastUsedAt, null);
    }

    @Test
    public void recentFilterKeepsOnlyUsedAndPreservesOrder() {
        List<Sticker> all = Arrays.asList(
                sticker("a", "2026-11-15T12:00:00.000Z"),
                sticker("b", null),
                sticker("c", "2026-11-14T12:00:00.000Z")
        );
        List<Sticker> recent = Sticker.filter(all, true);
        assertEquals(2, recent.size());
        assertEquals("a", recent.get(0).id);
        assertEquals("c", recent.get(1).id);
        assertEquals(3, Sticker.filter(all, false).size());
        assertTrue(Sticker.filter(Collections.emptyList(), true).isEmpty());
    }

    @Test
    public void recentFlagTreatsNullAndBlankAsNeverUsed() {
        assertTrue(sticker("a", "2026-11-15T12:00:00.000Z").isRecent());
        assertFalse(sticker("b", null).isRecent());
        assertFalse(sticker("c", "null").isRecent());
    }

    @Test
    public void optimisticStickerMessageCarriesIdAndAttachment() {
        Message message = Message.sticker(
                "local-1",
                "client-1",
                0,
                "user",
                "sticker-1",
                "猫猫点赞",
                Collections.singletonList(new Attachment("att-1", "猫猫点赞", "image/png", 0)),
                123L,
                true,
                null
        );
        assertTrue(message.isSticker());
        assertEquals("sticker-1", message.stickerId);
        assertEquals("猫猫点赞", message.stickerName);
        assertEquals(1, message.attachments.size());
        assertEquals("att-1", message.attachments.get(0).id);
        assertEquals("", message.text);
        assertNull(message.replyToId);
    }
}
