package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ServerAddressTest {
    @Test
    public void addsHttpsWhenSchemeMissing() {
        assertEquals("https://example.test/tide",
                ServerAddress.normalize("example.test/tide"));
        assertEquals("https://tide.example/tide",
                ServerAddress.normalize("  tide.example/tide/  "));
    }

    @Test
    public void keepsSchemeAndStripsTrailingSlashes() {
        assertEquals("http://127.0.0.1:7800/tide",
                ServerAddress.normalize("http://127.0.0.1:7800/tide/"));
        assertEquals("https://tide.example/tide",
                ServerAddress.normalize("https://tide.example/tide///"));
    }

    @Test
    public void emptyStaysEmpty() {
        assertEquals("", ServerAddress.normalize(null));
        assertEquals("", ServerAddress.normalize("   "));
    }
}
