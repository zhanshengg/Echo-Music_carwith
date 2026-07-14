package iad1tya.echo.music.playback

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import iad1tya.echo.music.db.MusicDatabase
import iad1tya.echo.music.db.entities.LyricsEntity
import iad1tya.echo.music.extensions.currentMetadata
import iad1tya.echo.music.lyrics.LyricsEntry
import iad1tya.echo.music.lyrics.LyricsUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

/**
 * Manages lyrics display on car head units (CarWith/CarLife) by updating
 * the notification bar subText with the current lyric line.
 *
 * CarWith reads lyrics from the Android notification subText field,
 * NOT from MediaSession extras or CarLife SDK data channels.
 *
 * Key principles:
 * - Parse LRC lyrics from Room database
 * - Match current playback position to find the active lyric line
 * - Update notification subText via NotificationManager.notify()
 * - Do NOT use player.replaceMediaItem() — it reloads the song and breaks cover art loading
 */
class CarLifeLyricsManager(
    private val context: Context,
    private val player: Player,
    private val database: MusicDatabase,
    private val scope: CoroutineScope,
) {
    private var parsedLyrics: List<LyricsEntry>? = null
    private var currentLyricsId: String? = null
    private var lyricsUpdateJob: Job? = null

    private val _currentLyricLine = MutableStateFlow("")
    val currentLyricLine: StateFlow<String> = _currentLyricLine.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            scope.launch { onSongChanged() }
        }
    }

    companion object {
        private const val TAG = "CarWithLyrics"
        private const val LYRIC_UPDATE_INTERVAL_MS = 200L
        private const val NOTIFICATION_ID = 888
    }

    fun init() {
        player.addListener(playerListener)
        scope.launch { onSongChanged() }
        Timber.tag(TAG).d("CarWithLyricsManager initialized (notification subText mode)")
    }

    fun destroy() {
        lyricsUpdateJob?.cancel()
        lyricsUpdateJob = null
        parsedLyrics = null
        currentLyricsId = null
        try {
            player.removeListener(playerListener)
        } catch (_: Exception) {}
    }

    private suspend fun onSongChanged() {
        lyricsUpdateJob?.cancel()
        lyricsUpdateJob = null
        parsedLyrics = null
        currentLyricsId = null
        _currentLyricLine.value = ""
        clearNotificationSubText()

        val metadata = player.currentMetadata
        val songId = metadata?.id ?: return
        currentLyricsId = songId

        // Fetch lyrics from database
        val lyricsEntity = database.lyrics(songId).firstOrNull()
        if (lyricsEntity != null && lyricsEntity.lyrics.isNotBlank()
            && lyricsEntity.lyrics != LyricsEntity.LYRICS_NOT_FOUND
        ) {
            parsedLyrics = LyricsUtils.parseLyrics(lyricsEntity.lyrics)
            Timber.tag(TAG).d("Parsed ${parsedLyrics?.size} lyrics lines for $songId")

            lyricsUpdateJob = scope.launch {
                trackCurrentLyricLine()
            }
        } else {
            Timber.tag(TAG).d("No lyrics found for $songId")
        }
    }

    private suspend fun trackCurrentLyricLine() {
        while (isActive) {
            updateCurrentLyric()
            delay(LYRIC_UPDATE_INTERVAL_MS)
        }
    }

    private fun updateCurrentLyric() {
        val lyrics = parsedLyrics ?: return
        if (lyrics.isEmpty()) return

        val position = player.currentPosition.coerceAtLeast(0)
        val index = LyricsUtils.findCurrentLineIndex(lyrics, position)
        val lineText = if (index >= 0 && index < lyrics.size) {
            lyrics[index].text
        } else {
            ""
        }

        if (lineText != _currentLyricLine.value) {
            _currentLyricLine.value = lineText
            if (lineText.isNotEmpty()) {
                Timber.tag(TAG).d("Lyric changed: $lineText")
            }
            updateNotificationSubText(lineText)
        }
    }

    /**
     * Updates the notification bar subText with the current lyric line.
     * CarWith reads this field to display lyrics on the car head unit.
     *
     * We grab the existing notification, rebuild it with subText, and re-notify.
     * This avoids disrupting the media notification provider's state.
     */
    private fun updateNotificationSubText(lyrics: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return

            val existingNotification = notificationManager.activeNotifications
                .find { it.id == NOTIFICATION_ID }?.notification ?: return

            val builder = NotificationCompat.Builder(context, existingNotification)
                .setSubText(lyrics.ifEmpty { null })
                .setOnlyAlertOnce(true)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to update notification subText with lyrics")
        }
    }

    private fun clearNotificationSubText() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return

            val existingNotification = notificationManager.activeNotifications
                .find { it.id == NOTIFICATION_ID }?.notification ?: return

            val builder = NotificationCompat.Builder(context, existingNotification)
                .setSubText(null)
                .setOnlyAlertOnce(true)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            // Ignore
        }
    }
}
