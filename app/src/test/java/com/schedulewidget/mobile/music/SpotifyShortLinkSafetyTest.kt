package com.schedulewidget.mobile.music

import com.schedulewidget.mobile.data.Spotify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class SpotifyShortLinkSafetyTest {
    @Test
    fun shortLinkParserRequiresAnExactHttpsHost() {
        assertEquals("https://spotify.link/abc", Spotify.shortLink("Shared song https://spotify.link/abc"))
        assertEquals("https://spotify.app.link/abc", Spotify.shortLink("https://spotify.app.link/abc"))
        assertNull(Spotify.shortLink("https://spotify.link.attacker.test/abc"))
        assertNull(Spotify.shortLink("https://spotify.link@attacker.test/abc"))
        assertNull(Spotify.shortLink("http://spotify.link/abc"))
        assertNull(Spotify.shortLink("https://spotify.link:8443/abc"))
    }

    @Test
    fun redirectAllowlistKeepsSpotifyTargetsAndRejectsOtherOrigins() {
        assertTrue(SpotifyFetch.isAllowedShortLinkUrl(URL("https://spotify.link/abc")))
        assertTrue(SpotifyFetch.isAllowedShortLinkUrl(URL("https://spotify.app.link/abc")))
        assertTrue(SpotifyFetch.isAllowedShortLinkRedirect(URL("https://open.spotify.com/track/123")))
        assertTrue(SpotifyFetch.isAllowedShortLinkRedirect(URL("https://spotify.app.link/abc")))
        assertFalse(SpotifyFetch.isAllowedShortLinkRedirect(URL("https://open.spotify.com.attacker.test/track/123")))
        assertFalse(SpotifyFetch.isAllowedShortLinkRedirect(URL("https://open.spotify.com@127.0.0.1/track/123")))
        assertFalse(SpotifyFetch.isAllowedShortLinkRedirect(URL("https://open.spotify.com:8443/track/123")))
        assertFalse(SpotifyFetch.isAllowedShortLinkRedirect(URL("http://open.spotify.com/track/123")))
    }
}
