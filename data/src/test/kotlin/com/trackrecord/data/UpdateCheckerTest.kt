package com.trackrecord.data

import com.trackrecord.data.update.AppRelease
import com.trackrecord.data.update.UpdateChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reading GitHub's newest release (feedback round 47). */
class UpdateCheckerTest {
    @Test
    fun `the version code is the tag's last part, the APK the attached one`() {
        val text = """{"tag_name":"v0.4.112","html_url":"https://github.com/r/releases/tag/v0.4.112","draft":false,"prerelease":false,"body":"x",
            "assets":[{"name":"notes.txt","browser_download_url":"https://a/notes.txt"},{"name":"TrackRecord-0.4.112.apk","browser_download_url":"https://a/t.apk","size":1}]}"""
        assertEquals(AppRelease(112, "0.4.112", "https://github.com/r/releases/tag/v0.4.112", "https://a/t.apk"), UpdateChecker.parse(text))
    }

    @Test
    fun `no APK attached - the release page instead`() {
        assertNull(UpdateChecker.parse("""{"tag_name":"v1.0.5","html_url":"https://h","assets":[]}""")!!.apkUrl)
    }

    @Test
    fun `odd tags, drafts and errors are no release`() {
        assertNull(UpdateChecker.parse("""{"tag_name":"latest","html_url":"https://h"}"""))
        assertNull(UpdateChecker.parse("""{"tag_name":"v1.0.5","html_url":"https://h","prerelease":true}"""))
        assertNull(UpdateChecker.parse("""{"message":"Not Found"}"""))
    }
}
