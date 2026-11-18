package app.torrtv.core

/** Short, actionable failures for TV screens. Raw transport messages belong in diagnostics. */
object UiFailure {
    fun message(error:Throwable):String {
        val causes=generateSequence(error){it.cause}.take(5).toList()
        val combined=causes.joinToString(" "){"${it.javaClass.simpleName} ${it.message.orEmpty()}"}
        val result=message(combined)
        return if(result==redact(combined).take(360))
            message(causes.mapNotNull{it.message}.firstOrNull{Regex("[а-яА-Я]").containsMatchIn(it)}.orEmpty()) else result
    }

    fun message(raw:String):String {
        val text=raw.lowercase()
        return when {
            listOf("enospc","no space left","няма място","недостатъчно място").any{text.contains(it)} ->
                "Няма достатъчно свободно място. Избери по-малък файл или друго място за съхранение."
            listOf("unknownhost","unable to resolve","unreachable","connectexception","няма интернет").any{text.contains(it)} ->
                "Няма връзка с източника. Провери интернет връзката и опитай пак или избери друг източник."
            text.contains("429") || text.contains("too many requests") ->
                "Източникът временно ограничава заявките. Изчакай малко или избери друг."
            text.contains("401") || text.contains("unauthorized") ->
                "Източникът изисква валиден достъп. Провери акаунта или API ключа в настройките."
            text.contains("403") || text.contains("forbidden") ->
                "Източникът отказа достъп. Опитай отново по-късно или избери друг източник."
            text.contains("timeout") || text.contains("timed out") || text.contains("не отговори навреме") ->
                "Източникът не отговори навреме. Опитай пак или избери друг."
            text.contains("decoder") || text.contains("codec") ->
                "Този формат не се поддържа от избрания плеър. Избери друга версия или друг плеър."
            Regex("[а-яА-Я]").containsMatchIn(raw) -> redact(raw).take(360)
            else -> "Зареждането не успя. Опитай пак или избери друг източник."
        }
    }

    fun redact(text:String):String = text
        .replace(Regex("https?://[^\\s]+",RegexOption.IGNORE_CASE),"[адрес]")
        .replace(Regex("(?i)(bearer\\s+)[^\\s,;]+"),"$1[скрит]")
        .replace(Regex("(?i)((?:api[_-]?key|token|password|passkey)\\s*[=:]\\s*)[^\\s,;&]+"),"$1[скрит]")
}
