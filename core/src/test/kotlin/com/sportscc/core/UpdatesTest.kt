package com.sportscc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updates.isNewer("v1.0.27", "1.0.26"))
        assertTrue(Updates.isNewer("1.0.10", "1.0.9"))
        assertTrue(Updates.isNewer("1.1", "1.0.99"))
        assertFalse(Updates.isNewer("1.0.26", "1.0.26"))
        assertFalse(Updates.isNewer("1.0.25", "1.0.26"))
        assertFalse(Updates.isNewer("non-browser", "1.0.26"))
        assertFalse(Updates.isNewer("1.0.27", "1.0.0-debug"))
    }

    @Test
    fun readsGitHubsLatestRelease() {
        val body = """{"tag_name":"v1.0.27","name":"Sports Command Center 1.0.27",
            "body":"What's new: Lighten the gray hint text\r\nBuilt from main @ abc.\r\nInstall on Google TV: ...",
            "assets":[{"name":"other.zip","browser_download_url":"https://x/other.zip"},
                      {"name":"SportsCommandCenter.apk","browser_download_url":"https://github.com/o/r/releases/download/v1.0.27/SportsCommandCenter.apk"}]}"""
        val r = Updates.parseLatest(body)!!
        assertEquals("1.0.27", r.version)
        assertEquals("Lighten the gray hint text", r.whatsNew)
        assertEquals("https://github.com/o/r/releases/download/v1.0.27/SportsCommandCenter.apk", r.apkUrl)
        assertNull(Updates.parseLatest("""{"tag_name":"non-browser","assets":[]}"""))
        assertNull(Updates.parseLatest("""{"message":"Not Found"}"""))
        assertNull(Updates.parseLatest("<html>"))
    }
}
