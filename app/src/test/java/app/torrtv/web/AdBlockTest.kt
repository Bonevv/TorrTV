package app.torrtv.web

import org.junit.Assert.*
import org.junit.Test

class AdBlockTest {
    @Test fun adHostsSeenOnFilmi9PlayersAreBlocked() {
        for (host in listOf("n1mwq.org", "img-place.com", "stellarconductornexus.com", "static.cloudflareinsights.com", "s10.histats.com"))
            assertTrue(host, AdBlock.isBlocked(host))
    }
    @Test fun machineMadeAdHostsAreRecognisedButPlayersAreNot() {
        assertTrue(AdBlock.looksThrowaway("m7ou9kh5fp.pe78vqq3bcgtny.cfd"))
        assertTrue(AdBlock.looksThrowaway("f6j.hizcy912wbwpicuoi.rest"))
        for (host in listOf("voe.sx", "vidsrc.sh", "vidsrcme.ru", "bysekoze.com", "filemoon.sx", "wishonly.site", "cdn.filmi9.com", "click.example.cfd"))
            assertFalse(host, AdBlock.looksThrowaway(host))
        for (host in listOf("voe.sx", "vidsrc.sh", "bysekoze.com", "filemoon.sx"))
            assertFalse(host, AdBlock.isBlocked(host))
    }
}
