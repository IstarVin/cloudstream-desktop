package desktop.api

/** Subtitle formats by content: the server serves them with the MIME type ExoPlayer needs to pick a parser. */
enum class SubtitleFormat(val mime: String, val extension: String) {
    ASS("text/x-ssa", "ass"),
    VTT("text/vtt", "vtt"),
    SRT("application/x-subrip", "srt"),
    TTML("application/ttml+xml", "ttml");

    companion object {
        /** By the file's first bytes, falling back to the URL's extension; SRT when neither says. */
        fun sniff(text: String, url: String = ""): SubtitleFormat {
            val head = text.trimStart('﻿', ' ', '\t', '\r', '\n').take(512)
            return when {
                head.startsWith("[Script Info]", ignoreCase = true) || head.contains("\n[V4+ Styles]", ignoreCase = true) -> ASS
                head.startsWith("WEBVTT") -> VTT
                head.startsWith("<?xml") && head.contains("<tt", ignoreCase = true) || head.startsWith("<tt") -> TTML
                else -> fromUrl(url) ?: SRT
            }
        }

        fun fromUrl(url: String): SubtitleFormat? = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "ass", "ssa" -> ASS
            "vtt" -> VTT
            "srt" -> SRT
            "ttml", "dfxp", "xml" -> TTML
            else -> null
        }
    }
}
