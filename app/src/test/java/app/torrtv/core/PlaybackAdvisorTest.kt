package app.torrtv.core

import app.torrtv.data.torrents.PlaybackAdvisor
import app.torrtv.data.torrents.TorrentResult
import org.junit.Assert.*
import org.junit.Test

class PlaybackAdvisorTest {
    private val gib=1024L*1024*1024
    private fun torrent(id:String,size:Long,seeds:Int=30,quality:String="1080p",tags:List<String> = emptyList(),pack:Boolean=false)=
        TorrentResult(id,"magnet:?xt=urn:btih:$id",id,seeds,0,size,"Test",quality,tags,pack)
    private val capacity=PlaybackAdvisor.Capacity(120,30*gib,20.0,50.0,true)
    @Test fun aSmallerPlayableReleaseBeatsAnOversizedHighSeedRelease() {
        val heavy=torrent("heavy",24*gib,120,"4K");val light=torrent("light",3*gib,25)
        assertEquals(light,PlaybackAdvisor.choose(listOf(heavy,light),capacity))
    }
    @Test fun knownInsufficientDiskIsNeverAnAutomaticChoice() {
        assertNull(PlaybackAdvisor.choose(listOf(torrent("huge",40*gib,200)),capacity))
    }
    @Test fun unknownBandwidthIsNotReportedAsSmoothPlayback() {
        val fit=PlaybackAdvisor.assess(torrent("a",3*gib),capacity.copy(netMbps=0.0,diskMBps=0.0))
        assertEquals(StreamFit.Level.NONE,fit.smooth)
    }
    @Test fun observedSwarmSpeedIsUsedOnlyForTheMatchingHash() {
        val slow=torrent("slow",8*gib,120);val other=torrent("other",3*gib,25)
        assertEquals(other,PlaybackAdvisor.choose(listOf(slow,other),capacity.copy(netMbps=100.0)){if(it=="slow")2.0 else null})
    }
    @Test fun aFastDiskAloneDoesNotProveThatTheNetworkCanStreamTheFile() {
        assertEquals(StreamFit.Level.NONE,PlaybackAdvisor.assess(torrent("a",3*gib),capacity.copy(netMbps=0.0)).smooth)
    }
    @Test fun aStoppedSwarmIsNotMistakenForUnmeasuredBandwidth() {
        assertEquals(StreamFit.Level.BAD,PlaybackAdvisor.assess(torrent("a",3*gib),capacity,0.0).smooth)
    }
    @Test fun seasonPackTotalSizeIsNotUsedAsEpisodeBitrateOrDiskRequirement() {
        val fit=PlaybackAdvisor.assess(torrent("pack",100*gib,pack=true),capacity)
        assertEquals(0.0,fit.bitrateMbps,0.0);assertEquals(StreamFit.Level.NONE,fit.smooth);assertEquals(StreamFit.Level.NONE,fit.storage)
    }
    @Test fun unsupportedAudioIsAvoidedWhenAnotherReleaseIsSuitable() {
        val hard=torrent("hard",3*gib,100,tags=listOf("DTS"));val normal=torrent("normal",3*gib,20)
        assertEquals(normal,PlaybackAdvisor.choose(listOf(hard,normal),capacity))
    }
    @Test fun camIsNotSilentlyPickedWhenNoOtherReleaseExists() {
        assertNull(PlaybackAdvisor.choose(listOf(torrent("Film HDCAM",gib,tags=listOf("CAM"))),capacity))
    }
    @Test fun transferSamplesIgnoreWarmupDuplicatesFullBuffersAndExpire() {
        var now=0L;val rates=TransferRates({now},2)
        repeat(3){rates.observe("one",2_000_000);now+=1000};assertNull(rates.mbps("one"))
        rates.observe("one",2_000_000);assertEquals(16.0,rates.mbps("one")!!,0.001)
        rates.observe("one",0);assertEquals(16.0,rates.mbps("one")!!,0.001)
        now+=1000;rates.observe("one",0,demanding=false);assertEquals(16.0,rates.mbps("one")!!,0.001)
        assertNull(rates.mbps("unrelated"));now+=31*60_000L;assertNull(rates.mbps("one"))
    }
    @Test fun transferHistoryRemainsBounded() {
        var now=0L;val rates=TransferRates({now},2)
        for(hash in listOf("one","two","three"))repeat(4){rates.observe(hash,1_000_000);now+=1000}
        assertNull(rates.mbps("one"));assertEquals(8.0,rates.mbps("three")!!,0.001)
    }
}
