package com.example.mystatus

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicReadingTest {
    @Test
    fun mapsPlayingPausedStoppedAndUnknownSeparately() {
        assertEquals(MusicPlaybackState.PLAYING, playbackStateOf(PlaybackState.STATE_PLAYING))
        assertEquals(MusicPlaybackState.PAUSED, playbackStateOf(PlaybackState.STATE_PAUSED))
        assertEquals(MusicPlaybackState.STOPPED, playbackStateOf(PlaybackState.STATE_STOPPED))
        assertEquals(MusicPlaybackState.UNKNOWN, playbackStateOf(null))
    }

    @Test
    fun requiresTitleAndArtistForUsableMetadata() {
        assertTrue(MusicMetadata("Song", "Artist").isUsable)
        assertFalse(MusicMetadata("", "Artist").isUsable)
        assertFalse(MusicMetadata("Song", null).isUsable)
    }

    @Test
    fun recognizesKnownAndUnknownPackageNames() {
        assertEquals("com.spotify.music", MusicPlayers.knownPackages["Spotify"])
        assertTrue(MusicPlayers.isValidPackage("com.example.player"))
        assertFalse(MusicPlayers.isValidPackage("not a package"))
    }

    @Test
    fun deduplicatesRepeatedEventsAndPrefersPlayingSession() {
        val deduper = MusicEventDeduper()
        assertFalse(deduper.isDuplicate("same"))
        assertTrue(deduper.isDuplicate("same"))
        val notification = MusicCandidate(
            "notification:key", "com.spotify.music", MusicPlaybackState.PLAYING,
            MusicMetadata("Notification", "Artist")
        )
        val session = MusicCandidate(
            "session:token", "com.spotify.music", MusicPlaybackState.PLAYING,
            MusicMetadata("Session", "Artist")
        )
        assertEquals(session, selectMusicCandidate(listOf(notification, session)))
    }
}
