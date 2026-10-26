package app.sine.core

import app.sine.core.text.Voice
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTest {
    @Test
    fun `counts are songs, never tracks`() {
        assertEquals("1 song", Voice.songs(1))
        assertEquals("38 songs", Voice.songs(38))
        assertEquals("From Ana · 24 songs", Voice.invitation("Ana", 24))
        assertEquals("Downloading 1 song.", Voice.downloadQueued(1))
        assertEquals("No songs in this playlist.", Voice.EMPTY_PLAYLIST)
    }
}
