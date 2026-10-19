package app.torrtv.core

import android.content.Context

/** Бърза проверка на бокса след първия вход: какво има и какво се препоръчва. Чиста логика + четене на устройството. */
object BoxCheck {
    class Result(
        val totalMb: Long,
        val cores: Int,
        val freeBytes: Long,
        val weak: Boolean,
        val lowDisk: Boolean
    ) {
        /** Кратко описание на бокса. */
        val summary: String
            get() = "Памет ${totalMb} MB · ядра $cores" + if (freeBytes > 0) " · свободно ${formatSize(freeBytes)}" else ""

        /** Какво се препоръчва, на прост език. */
        val advice: List<String>
            get() {
                val l = ArrayList<String>()
                if (weak) {
                    l.add("Боксът е със скромна памет. Препоръчвам лек режим (по-малко анимации и картинки) и да не се пуска дистанционното от телефона по време на филм.")
                } else {
                    l.add("Боксът се справя добре. Нищо не е нужно да се променя.")
                }
                if (lowDisk) l.add("Свободното място е малко. Свободни поне 2 GB помагат на филмите да се зареждат.")
                return l
            }
    }

    const val LOW_DISK_BYTES = 2L * 1024 * 1024 * 1024

    /** Чиста логика (за тест): слабо според паметта и флага на системата. */
    fun judge(totalMb: Long, lowRamFlag: Boolean, cores: Int, freeBytes: Long): Result =
        Result(totalMb, cores, freeBytes, Device.weakBy(totalMb, lowRamFlag), freeBytes in 1 until LOW_DISK_BYTES)

    fun run(ctx: Context): Result {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val free = try {
            val p = Storage.places(ctx).firstOrNull()
            if (p != null) Storage.free(p) else 0L
        } catch (e: Exception) {
            0L
        }
        return judge(Device.totalMb(ctx), am?.isLowRamDevice ?: false, Runtime.getRuntime().availableProcessors(), free)
    }
}
