package com.gooseberrydevelopment.pinepods

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.gooseberrydevelopment.pinepods.audio.PinepodsMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class PinepodsWidgetProvider : AppWidgetProvider() {

    // What the widget shows, as last pushed by PinepodsMediaService.
    private class WidgetState(
        val title: String,
        val artworkUri: String?,
        val artworkData: ByteArray?,
        val isPlaying: Boolean
    ) {
        val artworkKey: String?
            get() = artworkData?.contentHashCode()?.toString() ?: artworkUri
    }

    companion object {
        private const val TAG = "PinepodsWidget"

        const val ACTION_PLAY_PAUSE = "com.gooseberrydevelopment.pinepods.WIDGET_PLAY_PAUSE"
        const val ACTION_REWIND     = "com.gooseberrydevelopment.pinepods.WIDGET_REWIND"
        const val ACTION_FASTFWD    = "com.gooseberrydevelopment.pinepods.WIDGET_FASTFWD"
        const val ACTION_OPEN_APP   = "com.gooseberrydevelopment.pinepods.WIDGET_OPEN_APP"

        // Everything below is only touched on the main thread. If the process dies the
        // media service dies with it, so a null state (idle widget) is then correct.
        private var state: WidgetState? = null
        private var artworkKey: String? = null
        private var artworkBitmap: Bitmap? = null
        private var pendingArtworkKey: String? = null
        private var artworkJob: Job? = null
        private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        // Called by PinepodsMediaService whenever play state or metadata changes.
        fun update(context: Context, player: Player) {
            val metadata = player.mediaMetadata
            state = if (player.mediaItemCount == 0) null else WidgetState(
                title       = metadata.title?.toString() ?: "No episode playing",
                artworkUri  = metadata.artworkUri?.toString(),
                artworkData = metadata.artworkData,
                isPlaying   = isPlayingOrBuffering(player)
            )
            renderAll(context.applicationContext)
        }

        // Called by PinepodsMediaService when it is destroyed.
        fun clear(context: Context) {
            state = null
            renderAll(context.applicationContext)
        }

        private fun isPlayingOrBuffering(player: Player): Boolean =
            player.playWhenReady &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED

        private fun renderAll(context: Context) {
            val awm = AppWidgetManager.getInstance(context)
            val ids = awm.getAppWidgetIds(ComponentName(context, PinepodsWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val current = state
            ids.forEach { render(context, awm, it, current) }
            if (current != null) loadArtworkAsync(context, current)
        }

        private fun render(context: Context, awm: AppWidgetManager, widgetId: Int, current: WidgetState?) {
            val views = RemoteViews(context.packageName, R.layout.widget_audio_player)
            views.setTextViewText(R.id.widget_episode_title, current?.title ?: "No episode playing")

            val artwork = if (current != null && current.artworkKey == artworkKey) artworkBitmap else null
            if (artwork != null) {
                views.setImageViewBitmap(R.id.widget_artwork, artwork)
            } else {
                views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
            }

            val playPauseIcon = if (current?.isPlaying == true) R.drawable.ic_action_pause else R.drawable.ic_action_play_arrow
            views.setImageViewResource(R.id.widget_btn_play_pause, playPauseIcon)

            if (current != null) {
                views.setOnClickPendingIntent(R.id.widget_btn_play_pause, buildActionIntent(context, ACTION_PLAY_PAUSE))
                views.setOnClickPendingIntent(R.id.widget_btn_rewind,      buildActionIntent(context, ACTION_REWIND))
                views.setOnClickPendingIntent(R.id.widget_btn_fastforward, buildActionIntent(context, ACTION_FASTFWD))
            }
            views.setOnClickPendingIntent(R.id.widget_artwork,       buildActionIntent(context, ACTION_OPEN_APP))
            views.setOnClickPendingIntent(R.id.widget_episode_title, buildActionIntent(context, ACTION_OPEN_APP))

            applySquareArtwork(views, awm, widgetId)
            awm.updateAppWidget(widgetId, views)
        }

        // Loads the artwork once per episode and re-renders; later state changes
        // (play/pause) reuse the cached bitmap instead of downloading it again.
        private fun loadArtworkAsync(context: Context, current: WidgetState) {
            val key = current.artworkKey ?: return
            if (key == artworkKey || key == pendingArtworkKey) return

            artworkJob?.cancel()
            pendingArtworkKey = key
            artworkJob = coroutineScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) { decodeArtwork(current) } ?: return@launch
                    val targetPx = (40 * context.resources.displayMetrics.density).toInt()
                    artworkBitmap = Bitmap.createScaledBitmap(bitmap, targetPx, targetPx, true)
                    artworkKey = key
                    renderAll(context)
                } catch (e: Exception) {
                    // Keep the placeholder; do not crash
                    Log.w(TAG, "Failed to load widget artwork", e)
                } finally {
                    if (pendingArtworkKey == key) pendingArtworkKey = null
                }
            }
        }

        private fun decodeArtwork(current: WidgetState): Bitmap? {
            current.artworkData?.let { return BitmapFactory.decodeByteArray(it, 0, it.size) }
            val uri = Uri.parse(current.artworkUri ?: return null)
            return when (uri.scheme) {
                // Downloaded episodes may point at a local file.
                null, "file" -> BitmapFactory.decodeFile(uri.path)
                else -> {
                    val connection = URL(uri.toString()).openConnection()
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 10_000
                    connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                }
            }
        }

        // Sets the artwork ImageView width equal to the widget's actual portrait height.
        // MAX_HEIGHT is the portrait (taller) dimension; MIN_HEIGHT is landscape (shorter).
        // coerceAtLeast(60) guards against 0 when options haven't been populated yet.
        @Suppress("NewApi")
        private fun applySquareArtwork(views: RemoteViews, awm: AppWidgetManager, id: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val opts = awm.getAppWidgetOptions(id)
                val heightDp = maxOf(
                    opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
                    opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                ).coerceAtLeast(60)
                views.setViewLayoutWidth(R.id.widget_artwork, heightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            }
        }

        private fun buildActionIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, PinepodsWidgetProvider::class.java).apply { this.action = action }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAll(context.applicationContext)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appContext = context.applicationContext

        when (intent.action) {
            ACTION_PLAY_PAUSE -> sendCommand(appContext) { player ->
                if (isPlayingOrBuffering(player)) {
                    player.pause()
                } else {
                    // After an error or a stop the player is idle and play() alone is a no-op.
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            }
            // seekBack/seekForward go through the session's player, which applies the
            // user's configured skip intervals.
            ACTION_REWIND  -> sendCommand(appContext) { it.seekBack() }
            ACTION_FASTFWD -> sendCommand(appContext) { it.seekForward() }
            ACTION_OPEN_APP -> {
                val launchIntent = appContext.packageManager
                    .getLaunchIntentForPackage(appContext.packageName)
                    ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
                launchIntent?.let { appContext.startActivity(it) }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        render(context.applicationContext, appWidgetManager, appWidgetId, state)
    }

    override fun onDisabled(context: Context) {
        artworkJob?.cancel()
        artworkBitmap = null
        artworkKey = null
        super.onDisabled(context)
    }

    // Connects a short-lived MediaController to PinepodsMediaService's session, runs
    // the command and disconnects. goAsync() keeps the receiver alive while the
    // controller connects. Commands are delivered in order, so releasing straight
    // after issuing one does not drop it.
    private fun sendCommand(appContext: Context, command: (Player) -> Unit) {
        val pendingResult = goAsync()
        val token = SessionToken(appContext, ComponentName(appContext, PinepodsMediaService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                command(controller)
                controller.release()
            } catch (e: Exception) {
                Log.w(TAG, "Widget command failed", e)
            } finally {
                pendingResult.finish()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }
}
