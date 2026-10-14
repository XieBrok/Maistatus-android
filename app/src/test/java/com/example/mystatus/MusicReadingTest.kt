package com.example.mystatus

import android.media.session.PlaybackState
import android.app.Notification
import android.os.Bundle
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
    fun titleIsRequiredButArtistMayBeMissing() {
        assertTrue(MusicMetadata("Song", "Artist").isUsable)
        assertFalse(MusicMetadata("", "Artist").isUsable)
        assertTrue(MusicMetadata("Song", null).isUsable)
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
        assertEquals(notification, selectMusicCandidate(listOf(notification, session)))
    }

    @Test
    fun ignoresStoppedCandidatesAndKeepsSongWhenArtistIsMissing() {
        val stopped = MusicCandidate(
            "session:stopped", "com.spotify.music", MusicPlaybackState.STOPPED,
            MusicMetadata("Old song", "Artist")
        )
        val playing = MusicCandidate(
            "session:playing", "com.spotify.music", MusicPlaybackState.PLAYING,
            MusicMetadata("Current song", null)
        )

        assertEquals(playing, selectMusicCandidate(listOf(stopped, playing)))
    }

    @Test
    fun parsesMediaNotificationWithoutArtist() {
        val notification = Notification().apply {
            category = Notification.CATEGORY_TRANSPORT
            extras.putCharSequence(Notification.EXTRA_TITLE, "Song")
        }
        assertEquals("Song", parseNotificationMusicFields(notification)?.title)
        assertTrue(parseNotificationMusicFields(notification)?.artist == null)
    }

    @Test
    fun filtersOrdinaryNotificationAndAcceptsBigTextFallback() {
        val ordinary = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "Reminder")
            extras.putCharSequence(Notification.EXTRA_TEXT, "Take a break")
        }
        assertTrue(parseNotificationMusicFields(ordinary) == null)
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE_BIG, "Song")
            putCharSequence(Notification.EXTRA_BIG_TEXT, "Artist")
        }
        val media = Notification().apply {
            category = Notification.CATEGORY_TRANSPORT
            this.extras.putAll(extras)
        }
        assertEquals("Artist", parseNotificationMusicFields(media)?.artist)
    }
}
