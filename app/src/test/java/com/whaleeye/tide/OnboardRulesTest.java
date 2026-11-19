package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class OnboardRulesTest {
    @Test
    public void acceptsFilledFields() {
        assertEquals(0, OnboardRules.errorFor("CLOUD-8KQ2", "yg", "password123"));
    }

    @Test
    public void requiresInviteCode() {
        assertEquals(R.string.onboard_error_invite,
                OnboardRules.errorFor("  ", "yg", "password123"));
    }

    @Test
    public void requiresAccount() {
        assertEquals(R.string.onboard_error_account,
                OnboardRules.errorFor("CLOUD-8KQ2", "", "password123"));
    }

    @Test
    public void requiresEightCharacterPassword() {
        assertEquals(R.string.onboard_error_password,
                OnboardRules.errorFor("CLOUD-8KQ2", "yg", "1234567"));
        assertEquals(R.string.onboard_error_password,
                OnboardRules.errorFor("CLOUD-8KQ2", "yg", null));
    }

    @Test
    public void normalizeTrimsQuotesAndSpaces() {
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("  \"HTBZ35J9TNSY\"  "));
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("HTB Z3 5J9 TNSY"));
    }

    @Test
    public void normalizeDropsChinesePrefix() {
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("邀请码：HTBZ35J9TNSY"));
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("邀请码: HTBZ35J9TNSY"));
    }

    @Test
    public void normalizeDropsEnglishPrefix() {
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("code: htbz35j9tnsy").toUpperCase());
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("InviteCode: HTBZ35J9TNSY"));
    }

    @Test
    public void normalizeKeepsLegacyCodeIntact() {
        assertEquals("uRZ5Ad-cFEaO", OnboardRules.normalizeInvite("uRZ5Ad-cFEaO"));
        assertEquals("--uRnkCCKJja", OnboardRules.normalizeInvite(" --uRnkCCKJja "));
    }

    @Test
    public void normalizeNullBecomesEmpty() {
        assertEquals("", OnboardRules.normalizeInvite(null));
    }

    @Test
    public void normalizeDropsPrefixWithoutColon() {
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("邀请码 HTBZ35J9TNSY"));
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("code HTBZ35J9TNSY"));
    }

    @Test
    public void normalizeFoldsFullWidth() {
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("ＨＴＢＺ３５Ｊ９ＴＮＳＹ"));
        assertEquals("HTBZ35J9TNSY", OnboardRules.normalizeInvite("ＣＯＤＥ：HTBZ35J9TNSY"));
    }

    @Test
    public void normalizeKeepsLegacyCodeStartingWithCode() {
        assertEquals("codeAB12cd34", OnboardRules.normalizeInvite("codeAB12cd34"));
    }
}
