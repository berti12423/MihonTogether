package mihon.feature.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TogetherUpdatesTest {
    private val base = "https://mihontogether.web.app"

    @Test
    fun `reads the website's apk json`() {
        val release = TogetherUpdates.parse(
            """{"file":"MihonTogether.apk","mb":42.8,"version":"0.20.4-8200","build":8200,"notes":"- Group chats"}""",
            base,
        )!!
        assertEquals(8200, release.build)
        assertEquals("0.20.4-8200", release.version)
        assertEquals("- Group chats", release.notes)
        assertEquals("$base/files/MihonTogether.apk", release.apkUrl)
        assertEquals("$base/download", release.pageUrl)
    }

    @Test
    fun `a GitHub release link is used when present, other hosts never`() {
        val github = "https://github.com/berti/mihontogether-releases/releases/download/build-8300/MihonTogether.apk"
        val withGithub = """{"file":"MihonTogether.apk","build":8300,"url":"$github"}"""
        assertEquals(github, TogetherUpdates.parse(withGithub, base)!!.apkUrl)
        val elsewhere = """{"file":"MihonTogether.apk","build":8300,"url":"https://evil.example/MihonTogether.apk"}"""
        assertEquals("$base/files/MihonTogether.apk", TogetherUpdates.parse(elsewhere, base)!!.apkUrl)
        val lookalike = "https://github.com.evil.example/a/b/releases/download/t/x.apk"
        val sneaky = """{"file":"MihonTogether.apk","build":8300,"url":"$lookalike"}"""
        assertEquals("$base/files/MihonTogether.apk", TogetherUpdates.parse(sneaky, base)!!.apkUrl)
    }

    @Test
    fun `older apk json without a build number, odd file names and junk are ignored`() {
        assertNull(TogetherUpdates.parse("""{"file":"MihonTogether.apk","mb":80}""", base))
        assertNull(TogetherUpdates.parse("""{"file":"../evil.sh","build":9}""", base))
        assertNull(TogetherUpdates.parse("not json", base))
    }
}
