package com.example.mystatus

import android.app.Notification
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicReadingTest {
    @Test
    fun titleIsRequiredButArtistAndAlbumMayBeMissing() {
        assertTrue(MusicMetadata("Song", "Artist").isUsable)
        assertFalse(MusicMetadata("", "Artist").isUsable)
        assertTrue(MusicMetadata("Song", null).isUsable)
    }

    @Test
    fun notificationMetadataUsesStandardFieldsAndInfersPlaying() {
        val notification = Notification().apply {
            category = Notification.CATEGORY_TRANSPORT
            flags = Notification.FLAG_ONGOING_EVENT
            extras.putCharSequence(Notification.EXTRA_TITLE, "Song")
            extras.putCharSequence(Notification.EXTRA_TEXT, "Artist")
            extras.putCharSequence(Notification.EXTRA_SUB_TEXT, "Album")
        }

        assertEquals(
            NotificationMusicFields("Song", "Artist", "Album", MusicPlaybackState.PLAYING),
            parseNotificationMusicFields(notification)
        )
    }

    @Test
    fun mediaNotificationCanHaveTitleWithoutArtist() {
        val notification = Notification().apply {
            category = Notification.CATEGORY_TRANSPORT
            extras.putCharSequence(Notification.EXTRA_TITLE, "Song")
        }

        assertEquals("Song", parseNotificationMusicFields(notification)?.title)
        assertTrue(parseNotificationMusicFields(notification)?.artist == null)
    }

    @Test
    fun ordinaryAndDownloadNotificationsAreFiltered() {
        val ordinary = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "Reminder")
            extras.putCharSequence(Notification.EXTRA_TEXT, "Take a break")
        }
        val download = Notification().apply {
            flags = Notification.FLAG_ONGOING_EVENT
            extras.putCharSequence(Notification.EXTRA_TITLE, "Download")
            extras.putCharSequence(Notification.EXTRA_TEXT, "Song.mp3")
        }

        assertTrue(parseNotificationMusicFields(ordinary) == null)
        assertTrue(parseNotificationMusicFields(download) == null)
    }

    @Test
    fun bigTextIsUsedAsArtistFallbackAndPauseIsRecognized() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE_BIG, "Song")
            putCharSequence(Notification.EXTRA_BIG_TEXT, "Paused")
        }
        val media = Notification().apply {
            category = Notification.CATEGORY_TRANSPORT
            this.extras.putAll(extras)
        }

        assertEquals("Paused", parseNotificationMusicFields(media)?.artist)
        assertEquals(MusicPlaybackState.PAUSED, parseNotificationMusicFields(media)?.state)
    }

    @Test
    fun deduplicatesRepeatedEventsAndPrefersPlayingNotification() {
        val deduper = MusicEventDeduper()
        assertFalse(deduper.isDuplicate("same"))
        assertTrue(deduper.isDuplicate("same"))
        val paused = MusicCandidate(
            "notification:paused", "com.example.player", MusicPlaybackState.PAUSED,
            MusicMetadata("Old song", "Artist")
        )
        val playing = MusicCandidate(
            "notification:playing", "com.example.other", MusicPlaybackState.PLAYING,
            MusicMetadata("Current song", null)
        )

        assertEquals(playing, selectMusicCandidate(listOf(paused, playing)))
    }

    @Test
    fun stoppedAndUnknownCandidatesDoNotReplaceUsablePlayback() {
        val stopped = MusicCandidate(
            "notification:stopped", "com.example.player", MusicPlaybackState.STOPPED,
            MusicMetadata("Old song", "Artist")
        )
        val unknown = MusicCandidate(
            "notification:unknown", "com.example.player", MusicPlaybackState.UNKNOWN,
            MusicMetadata("Current song", null)
        )

        assertEquals(unknown, selectMusicCandidate(listOf(stopped, unknown)))
    }

    @Test
    fun removingTheLastNotificationLeavesNoMusicCandidate() {
        val candidate = MusicCandidate(
            "notification:key", "com.example.player", MusicPlaybackState.PLAYING,
            MusicMetadata("Song", "Artist")
        )

        assertEquals(candidate, selectMusicCandidate(listOf(candidate)))
        assertNull(selectMusicCandidate(emptyList()))
    }
}
