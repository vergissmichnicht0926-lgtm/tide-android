package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public class VoiceMessageTest {
    @Test
    public void audioAttachmentAndVoiceDuration() {
        Attachment audio = new Attachment("a1", "voice.m4a", "audio/mp4", 12_345);
        assertTrue(audio.isAudio());
        assertFalse(audio.isImage());

        Message message = new Message(
                "m1", "c1", 7, "user", "", 1000L, false,
                Collections.singletonList(audio), 3_200L
        );
        assertEquals(3_200L, message.voiceDurationMs);
        assertEquals(0L, message.asRecalled().voiceDurationMs);
    }
}
