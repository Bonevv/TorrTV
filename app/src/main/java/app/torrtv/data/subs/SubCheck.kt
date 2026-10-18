package app.torrtv.data.subs

import kotlin.math.abs
import kotlin.math.max

/**
 * Груба проверка дали субтитърът е за този филм: последният му ред трябва да е близо до края на видеото.
 * Не е сигурна - дълъг епилог или заключителни надписи без диалог дават фалшиво предупреждение, а субтитри с
 * грешна честота на кадрите или друга версия с близка дължина минават незабелязано. Хваща само явните грешки.
 */
object SubCheck {
    enum class Verdict { OK, TOO_LONG, TOO_SHORT, UNKNOWN }

    class Result(val verdict: Verdict, val diffMs: Long) {
        /** Кратко обяснение на български или празно, когато всичко е наред. */
        fun message(): String {
            val min = abs(diffMs) / 60_000
            return when (verdict) {
                Verdict.TOO_LONG -> "Субтитрите свършват $min мин. СЛЕД филма - може да са за друга версия."
                Verdict.TOO_SHORT -> "Субтитрите свършват $min мин. ПРЕДИ края на филма - може да са за друга версия."
                else -> ""
            }
        }
    }

    /** subEndMs - кога свършва последният ред (вече с изместването от синхрона); videoMs - дължина на видеото. */
    fun check(subEndMs: Long, videoMs: Long): Result {
        if (subEndMs <= 0 || videoMs <= 0) return Result(Verdict.UNKNOWN, 0)
        val diff = videoMs - subEndMs // > 0: субтитрите свършват по-рано
        if (-diff > 90_000) return Result(Verdict.TOO_LONG, diff)
        // Заключителните надписи без диалог са нормално: до 15% от филма, но не по-малко от 5 минути.
        val allowed = max(5 * 60_000L, videoMs * 15 / 100)
        if (diff > allowed) return Result(Verdict.TOO_SHORT, diff)
        return Result(Verdict.OK, diff)
    }
}
