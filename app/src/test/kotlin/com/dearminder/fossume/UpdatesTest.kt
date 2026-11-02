package com.dearminder.fossume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    @Test
    fun higherVersionsAreNewer() {
        assertTrue(isNewerVersion("1.0.4", "1.0.3"))
        assertTrue(isNewerVersion("1.0.10", "1.0.9"))
        assertTrue(isNewerVersion("2.0", "1.9.9"))
        assertTrue(isNewerVersion("1.0.1", "1.0"))
    }

    @Test
    fun sameOrLowerVersionsAreNotNewer() {
        assertFalse(isNewerVersion("1.0.3", "1.0.3"))
        assertFalse(isNewerVersion("1.0", "1.0.0"))
        assertFalse(isNewerVersion("1.0.9", "1.0.10"))
    }

    @Test
    fun prefixesAndSuffixesAreIgnored() {
        assertTrue(isNewerVersion("v1.2.0", "1.1.5-debug"))
        assertFalse(isNewerVersion("v1.2.0", "1.2.0-debug"))
    }

    @Test
    fun aBuildWithoutAVersionIsOlderThanAnyRelease() {
        assertTrue(isNewerVersion("1.0.1", "dev"))
        assertTrue(isNewerVersion("1.0.1", ""))
        assertFalse(isNewerVersion("nightly", "1.0.1"))
    }

    @Test
    fun releaseWithAnApk() {
        val release = parseRelease(
            """{
                "tag_name": "1.0.5",
                "html_url": "https://github.com/dearminder/FOSSume/releases/tag/1.0.5",
                "assets": [
                    {"name": "FOSSume-1.0.5.apk.sha256", "browser_download_url": "https://example.com/FOSSume-1.0.5.apk.sha256"},
                    {"name": "FOSSume-1.0.5.apk", "browser_download_url": "https://example.com/FOSSume-1.0.5.apk"}
                ]
            }"""
        )
        assertEquals(
            Release("1.0.5", "https://github.com/dearminder/FOSSume/releases/tag/1.0.5", "https://example.com/FOSSume-1.0.5.apk"),
            release
        )
    }

    @Test
    fun releaseWithoutAssetsFallsBackToItsPage() {
        val release = parseRelease("""{"tag_name": "v2.0.0", "assets": []}""")
        assertEquals(Release("2.0.0", RELEASES_PAGE, null), release)
    }

    @Test
    fun anythingElseIsNoRelease() {
        assertNull(parseRelease("not json"))
        assertNull(parseRelease("""{"message": "Not Found"}"""))
        assertNull(parseRelease("""{"tag_name": ""}"""))
    }
}
