package com.bubblelens.app

enum class Script { LATIN, CHINESE, JAPANESE, KOREAN }

data class Lang(val code: String, val name: String, val script: Script)

object Langs {
    /** Idiomas de los que se puede leer texto en pantalla (OCR) y traducir. */
    val sources = listOf(
        Lang("en", "Inglés", Script.LATIN),
        Lang("ja", "Japonés", Script.JAPANESE),
        Lang("zh", "Chino", Script.CHINESE),
        Lang("ko", "Coreano", Script.KOREAN),
        Lang("fr", "Francés", Script.LATIN),
        Lang("de", "Alemán", Script.LATIN),
        Lang("it", "Italiano", Script.LATIN),
        Lang("pt", "Portugués", Script.LATIN),
        Lang("id", "Indonesio", Script.LATIN),
        Lang("tr", "Turco", Script.LATIN),
        Lang("vi", "Vietnamita", Script.LATIN),
        Lang("pl", "Polaco", Script.LATIN),
        Lang("nl", "Neerlandés", Script.LATIN),
    )

    val targets = listOf(
        Lang("es", "Español", Script.LATIN),
        Lang("en", "Inglés", Script.LATIN),
        Lang("pt", "Portugués", Script.LATIN),
        Lang("fr", "Francés", Script.LATIN),
        Lang("de", "Alemán", Script.LATIN),
        Lang("it", "Italiano", Script.LATIN),
        Lang("ja", "Japonés", Script.JAPANESE),
        Lang("ko", "Coreano", Script.KOREAN),
        Lang("zh", "Chino", Script.CHINESE),
    )

    fun byCode(code: String): Lang =
        sources.firstOrNull { it.code == code }
            ?: targets.firstOrNull { it.code == code }
            ?: Lang(code, code, Script.LATIN)

    fun name(code: String): String = byCode(code).name
}
