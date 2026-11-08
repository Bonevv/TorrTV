package app.torrtv.web
import org.junit.Assert.*
import org.junit.Test
class WebMediaTest {
    @Test fun subtitleLanguageMustBeKnownAndUrlMustBePublic() {
        assertNotNull(WebSubtitleProvider.caption("""{"url":"https://cdn.example.com/bg.vtt","page":"https://player.example.com/embed/123","lang":"bg","label":"Български"}"""))
        assertNull(WebSubtitleProvider.caption("""{"url":"https://cdn.example.com/en.vtt","page":"https://player.example.com/embed/123","lang":"en","label":"English"}"""))
        assertNull(WebSubtitleProvider.caption("""{"url":"http://127.0.0.1:62000/save","page":"https://example.com","lang":"bg"}"""))
        assertNull(WebSubtitleProvider.caption("""{"url":"file:///etc/passwd","page":"https://example.com","lang":"bg"}"""))
        assertNull(WebSubtitleProvider.caption("""{"url":"https://cdn.example.com/test.vtt","page":"https://player.example.com/123","lang":"en","label":"Bulgarian"}"""))
        assertNull(WebSubtitleProvider.caption("not json"))
    }
    @Test fun apiResultsAreValidatedAndDeduplicatedWithoutInventingBgAudio() {
        val sources=WebApiClient.parse("""{"status_code":200,"data":{"file_name":"Film.1080p.WEB-DL.mkv","stream_urls":["https://cdn.example.com/master.m3u8","https://cdn.example.com/master.m3u8","javascript:bad","http://192.168.1.1/video"]}}""")
        assertEquals(1,sources.size)
        assertTrue(sources.first().direct)
        assertFalse(sources.first().bgAudio)
        assertEquals("Film.1080p.WEB-DL.mkv",sources.first().release)
        assertTrue(WebApiClient.parse("""{"status_code":404,"data":{"stream_urls":["https://cdn.example.com/test"]}}""").isEmpty())
    }
    @Test fun zenoAudioGroupMustBeExplicitAndEnabled() {
        val body="""{"success":true,"data":{"providers":[{"group":"bg","enabled":true,"url":"https://player.example.com/bg"},{"group":"en","url":"https://player.example.com/en"},{"group":"bg","enabled":false,"url":"https://player.example.com/off"},{"group":"bg","url":"http://127.0.0.1/admin"}]}}"""
        assertEquals(listOf("https://player.example.com/bg"),ZenoProvider.bgPlayers(body))
    }
}
