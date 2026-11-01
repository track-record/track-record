package com.trackrecord.data

import com.trackrecord.data.update.ReleasePackage
import com.trackrecord.data.update.UpdateChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reading GitHub's newest release (feedback round 47). */
class UpdateCheckerTest {
    @Test
    fun `the version code is the tag's last part, each platform's package the attached one`() {
        val text = """{"tag_name":"v0.4.112","html_url":"https://github.com/r/releases/tag/v0.4.112","draft":false,"prerelease":false,"body":"x",
            "assets":[{"name":"notes.txt","browser_download_url":"https://a/notes.txt"},{"name":"TrackRecord-0.4.112.apk","browser_download_url":"https://a/t.apk","size":1},
            {"name":"TrackRecord-0.4.112.msi","browser_download_url":"https://a/t.msi","digest":"sha256:ab12"}]}"""
        val release = UpdateChecker.parse(text)!!
        assertEquals(112, release.versionCode)
        assertEquals("0.4.112", release.versionName)
        assertEquals("https://github.com/r/releases/tag/v0.4.112", release.pageUrl)
        assertEquals(ReleasePackage("TrackRecord-0.4.112.apk", "https://a/t.apk", null), release.packageFor(".apk"))
        assertEquals(ReleasePackage("TrackRecord-0.4.112.msi", "https://a/t.msi", "ab12"), release.packageFor(".msi"))
    }

    @Test
    fun `no package attached - the release page instead`() {
        assertNull(UpdateChecker.parse("""{"tag_name":"v1.0.5","html_url":"https://h","assets":[]}""")!!.packageFor(".apk"))
    }

    @Test
    fun `odd tags, drafts and errors are no release`() {
        assertNull(UpdateChecker.parse("""{"tag_name":"latest","html_url":"https://h"}"""))
        assertNull(UpdateChecker.parse("""{"tag_name":"v1.0.5","html_url":"https://h","prerelease":true}"""))
        assertNull(UpdateChecker.parse("""{"message":"Not Found"}"""))
    }
}
