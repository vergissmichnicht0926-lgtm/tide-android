package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class JoinLinkTest {
    @Test
    public void parsesGroupInvite() {
        JoinLink link = JoinLink.parse(
                "tide://join?server=https%3A%2F%2Ftide.example%2Ftide&code=Ab3d9K&kind=group&group=g1"
        );
        assertEquals("https://tide.example/tide", link.server);
        assertEquals("Ab3d9K", link.code);
        assertEquals("group", link.kind);
        assertEquals("g1", link.groupId);
    }

    @Test
    public void defaultsToBotAndTrimsServerSlash() {
        JoinLink link = JoinLink.parse(
                "tide://join?server=https%3A%2F%2Ftide.example%2Ftide%2F&code=abc"
        );
        assertEquals("https://tide.example/tide", link.server);
        assertEquals("bot", link.kind);
    }

    @Test
    public void rejectsCleartextServer() {
        expectInvalid("tide://join?server=http%3A%2F%2Ftide.example&code=abc");
    }

    @Test
    public void rejectsUserInfoAndMissingCode() {
        expectInvalid("tide://join?server=https%3A%2F%2Fbad%40tide.example&code=abc");
        expectInvalid("tide://join?server=https%3A%2F%2Ftide.example");
    }

    @Test
    public void rejectsOtherScheme() {
        expectInvalid("https://tide.example/join?code=abc");
    }

    @Test
    public void rejectsUnknownKindAndMissingServer() {
        expectInvalid("tide://join?server=https%3A%2F%2Ftide.example&code=abc&kind=admin");
        expectInvalid("tide://join?code=abc");
    }

    private static void expectInvalid(String raw) {
        try {
            JoinLink.parse(raw);
            fail("expected invalid link: " + raw);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void extractsFromWebUrl() {
        JoinLink link = JoinLink.fromAnyText(
                "https://example.test/tide/j/HSQNHUBUW3E3?k=bot");
        assertEquals("HSQNHUBUW3E3", link.code);
        assertEquals("https://example.test/tide", link.server);
        assertEquals("bot", link.kind);
    }

    @Test
    public void extractsFromWebUrlWithSurroundingText() {
        JoinLink link = JoinLink.fromAnyText(
                "来汐语找我 https://example.test/tide/j/HSQNHUBUW3E3?k=group&g=g1 一起聊");
        assertEquals("HSQNHUBUW3E3", link.code);
        assertEquals("group", link.kind);
        assertEquals("g1", link.groupId);
    }

    @Test
    public void extractsFromPastedTideLink() {
        JoinLink link = JoinLink.fromAnyText(
                "\"tide://join?server=https%3A%2F%2Ftide.example%2Ftide&code=Ab3d9K\"");
        assertEquals("https://tide.example/tide", link.server);
        assertEquals("Ab3d9K", link.code);
    }

    @Test
    public void plainCodeIsNotALink() {
        assertEquals(null, JoinLink.fromAnyText("HSQNHUBUW3E3"));
    }

    @Test
    public void distinguishesIncompleteLinksFromPlainCodes() {
        assertTrue(JoinLink.looksLikeInviteLink("tide://join"));
        assertTrue(JoinLink.looksLikeInviteLink("https://tide.example/tide/j/"));
        assertFalse(JoinLink.looksLikeInviteLink("HSQNHUBUW3E3"));
        assertEquals(null, JoinLink.fromAnyText("https://tide.example/tide/j/"));
    }
}

