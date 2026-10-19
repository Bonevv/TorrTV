package app.torrtv.web

/** Дистанционно за плеъра от страницата на телефона. Плеърът го закача, докато върви филм. */
interface PlayerRemote {
    class Snap(
        val title: String,
        val posMs: Long,
        val durMs: Long,
        val playing: Boolean,
        /** Синхронът на субтитрите в мс (+ закъснява, - избързва). */
        val offsetMs: Long,
        val subsOn: Boolean
    )

    /** Последното състояние (обновява се веднъж в секунда, може да се чете от всяка нишка). */
    fun snap(): Snap

    /** Команда: toggle, seek (arg = мс напред/назад), suboff (arg = мс), suboff0, subtoggle, stop. Изпълнява се на главната нишка. */
    fun act(cmd: String, arg: Long)

    companion object {
        val COMMANDS = setOf("toggle", "seek", "suboff", "suboff0", "subtoggle", "stop")
    }
}
