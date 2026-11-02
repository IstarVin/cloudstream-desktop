package desktop

import desktop.api.SubtitleFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleFormatTest {
    @Test
    fun sniffsTheContentBeforeTheUrl() {
        assertEquals(SubtitleFormat.ASS, SubtitleFormat.sniff("﻿[Script Info]\nTitle: x\n", "https://x/sub.srt"))
        assertEquals(SubtitleFormat.ASS, SubtitleFormat.sniff("; comment\n[V4+ Styles]\n"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.sniff("WEBVTT\n\n00:00.000 --> 00:01.000\nhi", "https://x/a.srt"))
        assertEquals(SubtitleFormat.TTML, SubtitleFormat.sniff("<?xml version=\"1.0\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\">"))
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.sniff("1\n00:00:00,000 --> 00:00:01,000\nhi"))
    }

    @Test
    fun fallsBackToTheExtension() {
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.sniff("garbled", "https://x/a.vtt?token=1"))
        assertEquals(SubtitleFormat.ASS, SubtitleFormat.fromUrl("https://x/a.SSA"))
        assertEquals(null, SubtitleFormat.fromUrl("https://x/subtitle"))
    }
}
