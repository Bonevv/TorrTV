package app.torrtv.core

/** Small session-only history. A slow swarm must never downgrade unrelated torrents. */
class TransferRates(private val clock:()->Long={System.currentTimeMillis()},private val maxEntries:Int=32) {
    private data class Sample(var speed:Double,var count:Int,var at:Long)
    private val samples=LinkedHashMap<String,Sample>(16,0.75f,true)

    @Synchronized fun observe(hash:String,bytesPerSecond:Long,demanding:Boolean=true) {
        if(hash.isBlank() || bytesPerSecond<0 || !demanding)return
        val now=clock();val old=samples[hash]
        if(old!=null && now-old.at in 0..749)return
        if(old==null || now-old.at !in 0..120_000){
            samples[hash]=Sample(bytesPerSecond.toDouble(),1,now)
        }else{
            old.speed=old.speed*0.75+bytesPerSecond*0.25;old.count++;old.at=now
        }
        while(samples.size>maxEntries)samples.remove(samples.keys.first())
    }

    @Synchronized fun mbps(hash:String):Double? {
        val s=samples[hash]?:return null
        if(s.count<4 || clock()-s.at !in 0..30*60_000L)return null
        return s.speed*8.0/1_000_000
    }
}
