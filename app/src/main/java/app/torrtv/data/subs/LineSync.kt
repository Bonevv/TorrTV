package app.torrtv.data.subs

import kotlin.math.abs

/**
 * Намества субтитрите по една реплика: гледащият казва коя реплика току-що е чул, а приложението смята
 * изместването така, че тя да излиза точно тук. Поправя само постоянно изместване.
 */
object LineSync {
    /** Колко след началото на репликата човек успява да натисне (за да не излизат субтитрите малко рано). */
    const val REACTION_MS = 800L

    /** Най-голямото изместване, което приемаме. */
    const val MAX_OFFSET_MS = 600_000L

    class Found(val cues: List<SubCue>, val nearest: Int)

    /** Репликите около позицията (с вече приложеното изместване) и коя е най-близо до мястото, което се чува. */
    fun candidates(cues: List<SubCue>, offsetMs: Long, posMs: Long, windowMs: Long = 180_000L, max: Int = 60): Found {
        val heard = posMs - REACTION_MS
        var near = cues.filter { abs(it.startMs + offsetMs - heard) <= windowMs }
        if (near.isEmpty()) return Found(emptyList(), 0)
        if (near.size > max) near = near.sortedBy { abs(it.startMs + offsetMs - heard) }.take(max).sortedBy { it.startMs }
        var best = 0
        for (i in near.indices) if (abs(near[i].startMs + offsetMs - heard) < abs(near[best].startMs + offsetMs - heard)) best = i
        return Found(near, best)
    }

    /** Новото изместване: избраната реплика да започва там, където е била чута (позицията минус забавянето). */
    fun newOffset(cue: SubCue, posMs: Long): Long =
        (posMs - REACTION_MS - cue.startMs).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
}
