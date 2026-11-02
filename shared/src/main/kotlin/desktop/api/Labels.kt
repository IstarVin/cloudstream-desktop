package desktop.api

/** Labels saved with progress, so Continue Watching reads the same whichever player saved it. */
object Labels {
    /** "S1E3: Name", "E7", or the position when the provider numbers nothing. */
    fun episode(e: Episode, index: Int): String {
        val num = listOfNotNull(e.season?.let { "S$it" }, e.episode?.let { "E$it" }).joinToString("")
        return listOf(num, e.name.orEmpty()).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "Episode ${index + 1}" }
    }
}
