package app.sine.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.session.MediaButtonReceiver
import app.sine.MainActivity
import app.sine.R
import app.sine.core.color.Accents
import app.sine.graph
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The now-playing widget (§6.19): the cover, the title, and play, pause
 * and skip. It's simply Phase, a Float slab and a Sine play pill, lit in
 * the cover's colour as the app is (or the fixed colour, if one is set).
 * With nothing playing it shows what played last, and its play button picks
 * up from there, Sine running or not.
 */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, null, force = true)

    companion object {
        private data class Shown(val title: String?, val artist: String?, val art: String?, val playing: Boolean)

        private var shown: Shown? = null
        private var loading: Job? = null
        private var cover: Pair<String, Bitmap?>? = null

        /**
         * Shows what [player] plays; with no player, what played last. Only
         * redrawn when what it shows changed, since the player says
         * something many times a second.
         */
        fun update(context: Context, player: Player?, force: Boolean = false) {
            val ctx = context.applicationContext
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, NowPlayingWidget::class.java))
            if (ids.isEmpty()) return
            val item = player?.currentMediaItem
            val now = if (item != null) {
                Shown(item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString(), item.mediaMetadata.artworkUri?.toString(), player.isPlaying)
            } else {
                ctx.graph.player.last.value.let { Shown(it?.title, it?.artist, it?.art, false) }
            }
            if (!force && now == shown) return
            shown = now
            draw(ctx, manager, ids, now)
            // The cover comes after, off the main thread: from the phone, or the server.
            val art = now.art
            if (art != null && cover?.first != art) {
                loading?.cancel()
                loading = ctx.graph.scope.launch(Dispatchers.IO) {
                    val bitmap = runCatching { load(ctx, art) }.getOrNull()
                    cover = art to bitmap
                    shown?.let { if (it.art == art) draw(ctx, manager, ids, it) }
                }
            }
        }

        private fun draw(ctx: Context, manager: AppWidgetManager, ids: IntArray, s: Shown) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_now_playing)
            views.setTextViewText(R.id.widget_title, s.title ?: ctx.getString(R.string.widget_nothing))
            views.setTextViewText(R.id.widget_artist, s.artist.orEmpty())
            val bitmap = cover?.takeIf { it.first == s.art }?.second
            if (bitmap != null) views.setImageViewBitmap(R.id.widget_cover, bitmap)
            else views.setImageViewResource(R.id.widget_cover, android.R.color.transparent)
            views.setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.widget_pause else R.drawable.widget_play)
            views.setContentDescription(R.id.widget_play, ctx.getString(if (s.playing) R.string.widget_pause else R.string.widget_play))
            views.tintPill(R.id.widget_play, accent(ctx, bitmap))
            views.setOnClickPendingIntent(R.id.widget_play, button(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_previous, button(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_next, button(ctx, KeyEvent.KEYCODE_MEDIA_NEXT))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }

        /** A cover, small: one kept on the phone straight from its file, else through the image loader. */
        private suspend fun load(ctx: Context, art: String): Bitmap? {
            val uri = android.net.Uri.parse(art)
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= COVER_PX && bounds.outHeight / (sample * 2) >= COVER_PX) sample *= 2
                return android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
            val request = ImageRequest.Builder(ctx).data(art).size(COVER_PX).allowHardware(false).build()
            return SingletonImageLoader.get(ctx).execute(request).image?.toBitmap()
        }

        private const val COVER_PX = 256

        /** Phase's own blue, exact as the app has it (Ambient.Blue). */
        private const val BLUE = 0xFF53A4FF.toInt()

        /** The pill's colour: the fixed one when set, else the cover's, else Phase's blue. */
        private fun accent(ctx: Context, bitmap: Bitmap?): Int {
            val fixed = ctx.graph.settings.state.value.fixedColour
            val hues = Accents.fixed.firstOrNull { it.first == fixed }?.second
                ?: bitmap?.let { b ->
                    val small = Bitmap.createScaledBitmap(b, 32, 32, true)
                    IntArray(32 * 32).also { small.getPixels(it, 0, 32, 0, 0, 32, 32) }.let(Accents::hues)
                }
                ?: return BLUE
            return if (hues == Accents.fixed.first().second) BLUE else Accents.accent(hues).fill
        }

        /** Tints the pill, from Android 12; before, a widget can't be told to, and it stays white. */
        private fun RemoteViews.tintPill(id: Int, color: Int) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                setColorStateList(id, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(color))
            }
        }

        /** A media button, through Media3's receiver: it starts the player if it isn't running, and picks up what played last. */
        private fun button(ctx: Context, key: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(ctx, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, key))
            return PendingIntent.getBroadcast(ctx, key, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
