package app.morsel.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    private val digest = "a".repeat(64)

    private fun release(tag: String = "v1.5.0", assets: String) = """
        {"tag_name": "$tag", "body": "notes", "assets": [$assets]}
    """.trimIndent()

    private fun asset(name: String, url: String) = """{"name": "$name", "browser_download_url": "$url", "size": 1}"""

    private val apk = asset("ScaleBit-1.5.0.apk", "https://github.com/Joji228/ScaleBit/releases/download/v1.5.0/ScaleBit-1.5.0.apk")
    private val sum = asset("ScaleBit-1.5.0.apk.sha256", "https://github.com/Joji228/ScaleBit/releases/download/v1.5.0/ScaleBit-1.5.0.apk.sha256")

    @Test
    fun newerVersionsCompareNumerically() {
        assertTrue(ReleaseParser.isNewer("1.5.0", "1.4.0"))
        assertTrue(ReleaseParser.isNewer("1.10.0", "1.9.0"))
        assertTrue(ReleaseParser.isNewer("v2.0", "1.9.9"))
        assertFalse(ReleaseParser.isNewer("1.4.0", "1.4.0"))
        assertFalse(ReleaseParser.isNewer("1.4", "1.4.0"))
        assertFalse(ReleaseParser.isNewer("1.3.9", "1.4.0"))
    }

    @Test
    fun releaseWithApkAndChecksumIsInstallable() {
        val parsed = ReleaseParser.parse(release(assets = "$apk, $sum"))
        assertEquals(
            Release(
                version = "1.5.0",
                apkName = "ScaleBit-1.5.0.apk",
                apkUrl = "https://github.com/Joji228/ScaleBit/releases/download/v1.5.0/ScaleBit-1.5.0.apk",
                checksumUrl = "https://github.com/Joji228/ScaleBit/releases/download/v1.5.0/ScaleBit-1.5.0.apk.sha256",
            ),
            parsed,
        )
    }

    @Test
    fun releaseWithoutChecksumIsNotOffered() {
        assertNull(ReleaseParser.parse(release(assets = apk)))
    }

    @Test
    fun releaseWithOtherAppsApkIsNotOffered() {
        val other = asset("Platebit-1.5.0.apk", "https://github.com/Joji228/Platebit/releases/download/v1.5.0/Platebit-1.5.0.apk")
        assertNull(ReleaseParser.parse(release(assets = "$other, $sum")))
    }

    @Test
    fun downloadFromAnotherHostIsRefused() {
        val elsewhere = asset("ScaleBit-1.5.0.apk", "https://example.com/ScaleBit-1.5.0.apk")
        assertNull(ReleaseParser.parse(release(assets = "$elsewhere, $sum")))
        val plainHttp = asset("ScaleBit-1.5.0.apk", "http://github.com/Joji228/ScaleBit/releases/download/v1.5.0/ScaleBit-1.5.0.apk")
        assertNull(ReleaseParser.parse(release(assets = "$plainHttp, $sum")))
    }

    @Test
    fun emptyTagIsNotOffered() {
        assertNull(ReleaseParser.parse(release(tag = "v", assets = "$apk, $sum")))
    }

    @Test
    fun checksumIsReadFromSha256sumLine() {
        assertEquals(digest, ReleaseParser.checksum("$digest  ScaleBit-1.5.0.apk\n"))
        assertEquals(digest, ReleaseParser.checksum(digest.uppercase()))
        assertNull(ReleaseParser.checksum("not a digest  ScaleBit-1.5.0.apk"))
        assertNull(ReleaseParser.checksum(""))
    }
}
