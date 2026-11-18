package app.torrtv.data.torrents

import app.torrtv.core.StreamFit

/** Shared recommendation rules for the main action, source picker and next episode. */
object PlaybackAdvisor {
    data class Capacity(val runtimeMin:Int,val freeBytes:Long,val netMbps:Double=0.0,val diskMBps:Double=0.0,val builtin:Boolean=true)
    data class Assessment(val storage:StreamFit.Level,val smooth:StreamFit.Level,val bitrateMbps:Double,val observed:Boolean)

    fun assess(t:TorrentResult,c:Capacity,measuredMbps:Double?=null):Assessment {
        // A season pack's total size is not the selected episode's size.
        if(t.pack)return Assessment(StreamFit.Level.NONE,StreamFit.Level.NONE,0.0,false)
        val need=StreamFit.bitrateMbps(t.size,c.runtimeMin)
        val network=listOfNotNull(c.netMbps.takeIf{it>0},measuredMbps?.takeIf{it>=0})
        val available=network.minOrNull()?:0.0
        val estimated=if(measuredMbps==0.0 && need>0)StreamFit.Level.BAD
            else StreamFit.smooth(t.size,c.runtimeMin,available,c.diskMBps,t.seeders)
        val smooth=if(network.isEmpty() && estimated!=StreamFit.Level.BAD)StreamFit.Level.NONE else estimated
        return Assessment(StreamFit.storage(t.size,c.freeBytes),smooth,need,measuredMbps!=null)
    }

    fun score(t:TorrentResult,c:Capacity,measuredMbps:Double?=null):Int {
        val fit=assess(t,c,measuredMbps)
        return TorrentAggregator.score(t,c.builtin)-when(fit.smooth){
            StreamFit.Level.BAD->if(fit.observed)220 else 140
            StreamFit.Level.EDGE->20
            else->0
        }-if(fit.storage==StreamFit.Level.EDGE)10 else 0
    }

    fun choose(list:List<TorrentResult>,c:Capacity,measured:(String)->Double?={null}):TorrentResult? = list
        .filter{!it.isCam && assess(it,c,measured(it.hash)).storage!=StreamFit.Level.BAD}
        .maxWithOrNull(compareBy<TorrentResult>{score(it,c,measured(it.hash))}.thenBy{it.hash})
}
