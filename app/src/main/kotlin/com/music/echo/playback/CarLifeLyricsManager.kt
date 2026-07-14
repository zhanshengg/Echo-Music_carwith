package iad1tya.echo.music.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import com.baidu.carlife.platform.CLPlatformCallback
import com.baidu.carlife.platform.CLPlatformManager
import com.baidu.carlife.platform.model.CLSongData
import com.baidu.carlife.platform.request.CLGetSongDataReq
import com.baidu.carlife.platform.request.CLRequest
import com.baidu.carlife.platform.response.CLGetSongDataResp
import com.baidu.carlife.platform.response.CLResponse
import iad1tya.echo.music.db.MusicDatabase
import iad1tya.echo.music.db.entities.LyricsEntity
import iad1tya.echo.music.extensions.currentMetadata
import iad1tya.echo.music.lyrics.LyricsEntry
import iad1tya.echo.music.lyrics.LyricsUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.nio.charset.StandardCharsets

/**
 * Manages CarLife SDK integration for displaying lyrics on car head units.
 *
 * CarLife protocol flow:
 * 1. Initialize CLPlatformManager with CLPlatformCallback
 * 2. When car requests lyrics via CLGetSongDataReq, respond with CLGetSongDataResp
 * 3. CLSongData.data contains the current lyric line as UTF-8 bytes
 * 4. Also proactively push lyric updates when the current line changes
 */
class CarLifeLyricsManager(
    private val context: Context,
    private val player: Player,
    private val database: MusicDatabase,
    private val scope: CoroutineScope,
) : CLPlatformCallback {

    private var parsedLyrics: List<LyricsEntry>? = null
    private var currentLyricsId: String? = null
    private var lyricsUpdateJob: Job? = null

    private val _currentLyricLine = MutableStateFlow("")
    val currentLyricLine: StateFlow<String> = _currentLyricLine.asStateFlow()

    private var isInitialized = false

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            scope.launch {
                onSongChanged()
            }
        }
    }

    companion object {
        private const val TAG = "CarLifeLyrics"
        private const val LYRIC_UPDATE_INTERVAL_MS = 200L
        private const val APP_KEY = "EchoMusic"
    }

    fun init() {
        if (isInitialized) return

        try {
            val manager = CLPlatformManager.getInstance()
            val carlifeIntent = Intent().apply {
                setPackage("com.baidu.carlife")
            }

            val installed = CLPlatformManager.isCarlifeInstalled(context, carlifeIntent)
            Timber.tag(TAG).d("CarLife installed: $installed")

            val success = manager.init(context, APP_KEY, this, carlifeIntent)
            Timber.tag(TAG).d("CarLife init success: $success")
            isInitialized = success

            if (success) {
                player.addListener(playerListener)
                // Load lyrics for current song if any
                scope.launch { onSongChanged() }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initialize CarLife SDK")
        }
    }

    fun destroy() {
        lyricsUpdateJob?.cancel()
        lyricsUpdateJob = null
        parsedLyrics = null
        currentLyricsId = null

        try {
            player.removeListener(playerListener)
        } catch (_: Exception) {}

        if (isInitialized) {
            try {
                CLPlatformManager.getInstance().destroy()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to destroy CarLife SDK")
            }
            isInitialized = false
        }
    }

    private suspend fun onSongChanged() {
        lyricsUpdateJob?.cancel()
        lyricsUpdateJob = null
        parsedLyrics = null
        currentLyricsId = null
        _currentLyricLine.value = ""

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

            // Start tracking current lyric line
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
            Timber.tag(TAG).d("Lyric changed: $lineText")
            // Push lyric update to CarLife
            pushLyricUpdate(lineText)
        }
    }

    private fun pushLyricUpdate(lyricText: String) {
        if (!isInitialized) return

        try {
            val songData = CLSongData().apply {
                songId = currentLyricsId ?: ""
                tag = CLSongData.TAG_CONTENT
                data = lyricText.toByteArray(StandardCharsets.UTF_8)
                len = data?.size ?: 0
                offset = 0
                totalSize = len.toLong()
            }

            val resp = CLGetSongDataResp().apply {
                errorNo = CLResponse.ERROR_NONE
                this.songData = songData
                requestId = 0
            }

            val result = CLPlatformManager.getInstance().sendResp(resp)
            Timber.tag(TAG).d("Pushed lyric update, result: $result")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to push lyric update")
        }
    }

    // region CLPlatformCallback

    override fun onConnected() {
        Timber.tag(TAG).d("CarLife connected")
    }

    override fun onCarlifeRequest(request: CLRequest?) {
        if (request == null) return

        try {
            when (request.getRequestType()) {
                CLRequest.REQUEST_GET_SONG_DATA -> {
                    val req = request as? CLGetSongDataReq
                    Timber.tag(TAG).d("Received CLGetSongDataReq for songId: ${req?.songId}")
                    handleSongDataRequest(req)
                }
                else -> {
                    Timber.tag(TAG).d("Received unsupported request type: ${request.getRequestType()}")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error handling CarLife request")
        }
    }

    private fun handleSongDataRequest(req: CLGetSongDataReq?) {
        val currentLine = _currentLyricLine.value
        val songId = currentLyricsId ?: req?.songId ?: ""

        try {
            val songData = CLSongData().apply {
                this.songId = songId
                tag = CLSongData.TAG_CONTENT
                data = currentLine.toByteArray(StandardCharsets.UTF_8)
                len = data?.size ?: 0
                offset = 0
                totalSize = len.toLong()
            }

            val resp = CLGetSongDataResp().apply {
                errorNo = CLResponse.ERROR_NONE
                this.songData = songData
                requestId = req?.requestId ?: 0
            }

            val result = CLPlatformManager.getInstance().sendResp(resp)
            Timber.tag(TAG).d("Sent CLGetSongDataResp, result: $result, lyric: $currentLine")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to send song data response")
        }
    }

    override fun onCarlifeResponse(response: CLResponse?) {
        Timber.tag(TAG).d("Received CarLife response: ${response?.getResponseType()}")
    }

    override fun onCarlifeError(errorNo: Int, errorMsg: String?) {
        Timber.tag(TAG).e("CarLife error: $errorNo - $errorMsg")
    }

    // endregion
}
