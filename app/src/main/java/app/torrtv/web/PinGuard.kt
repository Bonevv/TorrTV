package app.torrtv.web

/**
 * Защита на ПИН-а за страницата от телефона. След [maxFails] грешни опита подред ПИН-ът се сменя с нов
 * и за [lockMs] не се приемат никакви опити - така познаването му "на късмет" не води доникъде. Чиста логика, без Android.
 */
class PinGuard(
    private val maxFails: Int = 5,
    private val lockMs: Long = 60_000L,
    private val newPin: () -> String
) {
    enum class Result { OK, WRONG, LOCKED }

    var pin: String = newPin()
        private set

    private var fails = 0
    private var lockedUntil = 0L

    /** Колко секунди остават до края на паузата (0 = няма пауза). */
    @Synchronized
    fun lockLeftSec(now: Long): Int = if (now < lockedUntil) ((lockedUntil - now + 999) / 1000).toInt() else 0

    /** Още колко грешни опита са позволени, преди да се смени ПИН-ът. */
    @Synchronized
    fun triesLeft(): Int = maxFails - fails

    /**
     * Проверява един опит. OK - верен ПИН; WRONG - грешен, още има опити; LOCKED - паузата тече
     * (или този опит е последният и ПИН-ът току-що се смени).
     */
    @Synchronized
    fun check(input: String?, now: Long): Result {
        if (now < lockedUntil) return Result.LOCKED
        if (input?.trim() == pin) {
            fails = 0
            return Result.OK
        }
        fails++
        if (fails >= maxFails) {
            val old = pin
            var p = newPin()
            var guard = 0
            while (p == old && guard++ < 20) p = newPin()
            pin = p
            fails = 0
            lockedUntil = now + lockMs
            return Result.LOCKED
        }
        return Result.WRONG
    }
}
