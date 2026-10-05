package com.schedulewidget.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URL

class AppUpdatesTest {
    private val digest = "a".repeat(64)
    private val release = """{
        "draft":false,"prerelease":false,"tag_name":"v0.1",
        "html_url":"${AppUpdates.RELEASES_URL}/tag/v0.1",
        "assets":[{"id":123,"name":"ScheduleWidget-mobile-notes.apk","size":1024,
        "state":"uploaded","digest":"sha256:$digest",
        "url":"https://api.github.com/repos/${AppUpdates.REPOSITORY}/releases/assets/123"}]
    }"""

    @Test fun numericVersionsUseNumericOrderingAndNormalizeTrailingZeros() {
        assertTrue(AppUpdates.compareVersions("v0.10", "Ver 0.2")!! > 0)
        assertEquals(0, AppUpdates.compareVersions("0.0", "0.0.0"))
        assertTrue(AppUpdates.compareVersions("0.1.1", "0.1")!! > 0)
        assertTrue(AppUpdates.compareVersions("0.0", "0.1")!! < 0)
        for (bad in listOf("", "0.-1", "0.1-beta", "notes-test-20261005", "0.1x", "99999999999", "0/1")) {
            assertNull("Invalid version: $bad", AppUpdates.compareVersions(bad, "0.0"))
        }
    }

    @Test fun onlyNewerStableReleaseCanOfferAnUpdate() {
        val found = requireNotNull(AppUpdates.parseLatestRelease(release, "0.0"))
        assertEquals("0.1", found.version)
        assertEquals(123L, found.assetId)
        assertEquals(digest, found.sha256)
        assertNull(AppUpdates.parseLatestRelease(release, "0.1.0"))
        assertNull(AppUpdates.parseLatestRelease(release, "0.2"))
        rejects(release.replace("\"draft\":false", "\"draft\":true"))
        rejects(release.replace("\"prerelease\":false", "\"prerelease\":true"))
        rejects(release.replace("v0.1", "v0.1-beta"))
    }

    @Test fun releaseMustContainAnUploadedApkWithExactRepositoryAndDigest() {
        rejects(release.replace("sha256:$digest", ""))
        rejects(release.replace("sha256:$digest", "sha256:abc"))
        rejects(release.replace("\"state\":\"uploaded\"", "\"state\":\"new\""))
        rejects(release.replace("\"size\":1024", "\"size\":0"))
        rejects(release.replace("\"size\":1024", "\"size\":${AppUpdates.MAX_APK_BYTES + 1}"))
        rejects(release.replace("\"id\":123", "\"id\":-1"))
        rejects(release.replace(AppUpdates.REPOSITORY, "attacker/other-app"))
        rejects(release.replace("ScheduleWidget-mobile-notes.apk", "app-debug-androidTest.apk"))
        rejects("{}")
    }

    @Test fun assetRedirectsCannotLeakTokensToUntrustedOrInsecureHosts() {
        for (host in listOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")) {
            assertTrue(AppUpdates.isAllowedDownloadUrl(URL("https://$host/file.apk")))
        }
        for (url in listOf("http://api.github.com/file.apk", "https://api.github.com.evil.invalid/file.apk",
            "https://github.com@evil.invalid/file.apk", "https://user@api.github.com/file.apk",
            "https://api.github.com:8443/file.apk", "https://example.invalid/file.apk")) {
            assertFalse(url, AppUpdates.isAllowedDownloadUrl(URL(url)))
        }
    }

    private fun rejects(body: String) {
        try {
            AppUpdates.parseLatestRelease(body, "0.0")
            fail("Unsafe release metadata was accepted")
        } catch (_: UpdateException) {
            // This metadata must never enable the download/install action.
        }
    }
}
