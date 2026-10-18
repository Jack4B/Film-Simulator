package io.github.yahiaangelo.filmsimulator.frame

enum class FrameStyle(val label: String) {
    NONE("Brak"),
    THIN("Cienka"),
    MATTE("Pasparta"),
    POLAROID("Polaroid"),
    FILM("Klatka"),
}

enum class PenFont(val label: String) {
    HAND("Pióro"),
    SERIF("Antykwa"),
    TYPEWRITER("Maszyna"),
}

/** ARGB ink colours for the caption. */
val InkColors: List<Int> = listOf(
    0xFF1B2F7A.toInt(), // niebieski atrament
    0xFF111111.toInt(), // czarny
    0xFF7A1B1B.toInt(), // bordo
    0xFF1B5E3A.toInt(), // zieleń
    0xFFF0EDE2.toInt(), // biały (na ciemnej ramce)
)

/** ARGB frame colours. */
val FrameColors: List<Int> = listOf(
    0xFFFBFAF6.toInt(), // papier
    0xFF111111.toInt(), // czerń
    0xFFE8DFC8.toInt(), // kremowy
)

data class FrameSpec(
    val style: FrameStyle = FrameStyle.NONE,
    /** Multiplier of the base frame size, 0.5..2. */
    val thickness: Float = 1f,
    val color: Int = FrameColors[0],
    val caption: String = "",
    val inkColor: Int = InkColors[0],
    val penFont: PenFont = PenFont.HAND,
    val dateStamp: Boolean = false,
) {
    val isActive: Boolean
        get() = style != FrameStyle.NONE || caption.isNotBlank() || dateStamp
}
