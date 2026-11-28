package io.github.artcraftandroid.capcraft.ui.editor

import android.net.Uri
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.RowKind
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.holdScrubWrites
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

/**
 * Playing a video straight from its file on the phone's own video decoder, which shows 1080p at 60 frames a second
 * on a phone where the engine - copying every frame to the processor to draw it - cannot. Only for a timeline of
 * plain video: the clips of the main track, trimmed and in order, with sound beside them if it likes. Anything the
 * engine has to draw (an effect, a title, a picture over the video, a change of speed, a crop, a move or a
 * transition) keeps the engine's own preview, which shows it all but reads a heavy video slowly. The engine still
 * plays the sound and keeps the clock; this follows that clock.
 */
fun directClips(timeline: TimelineView): List<ClipView>? {
    var main: List<ClipView>? = null
    for (row in timeline.rows) {
        when (row.kind) {
            RowKind.MAIN -> main = row.clips
            RowKind.AUDIO -> Unit
            else -> if (row.clips.isNotEmpty()) return null
        }
    }
    val clips = main?.takeIf { it.isNotEmpty() } ?: return null
    return clips.takeIf { list -> list.all(::plainVideo) }
}

/** A video clip the engine would draw as the file is: fitted, as it is, at its own speed. */
private fun plainVideo(clip: ClipView): Boolean {
    if (clip.kind != "video" || clip.path.isEmpty() || clip.speed != 1.0 || clip.curved) return false
    if (clip.keysUs.isNotEmpty() || clip.transitionId.isNotEmpty() || clip.effectId.isNotEmpty()) return false
    val json = runCatching { JSONObject(clip.json) }.getOrNull() ?: return false
    fun near(key: String, want: Double) = !json.has(key) || abs(json.optDouble(key, want) - want) < 1e-6
    if (!(near("scale", 1.0) && near("offsetX", 0.0) && near("offsetY", 0.0) && near("rotation", 0.0))) return false
    if (!(near("opacity", 1.0) && near("stretchX", 1.0) && near("stretchY", 1.0))) return false
    if (json.optBoolean("flipH", false) || json.optBoolean("flipV", false)) return false
    val blend = json.optString("blend", "")
    if (blend.isNotEmpty() && blend != "normal") return false
    for (key in listOf("crop", "cutout", "mask", "stabilize", "enhanced", "background", "transitionIn", "speedCurve")) {
        if (json.has(key) && !json.isNull(key)) return false
    }
    if ((json.optJSONArray("videoEffects")?.length() ?: 0) > 0) return false
    return true
}

private fun mediaItem(clip: ClipView): MediaItem =
    MediaItem.Builder()
        .setUri(Uri.fromFile(File(clip.path)))
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.sourceStartUs / 1000)
                .setEndPositionMs((clip.sourceStartUs + clip.durationUs) / 1000)
                .build(),
        )
        .build()

/**
 * The picture of [clips] while playing, drawn by the phone's video decoder into a view the size of the frame it
 * fits. Silent: the engine plays the sound. It follows the engine's clock, and when it has drifted more than
 * 0.3 s seeks to where the clock is. If the decoder cannot play a file, [failed] is set and the engine's own
 * pictures take over.
 */
@Composable
fun DirectVideo(vm: EditorViewModel, clips: List<ClipView>, failed: MutableState<Boolean>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
    }
    // the picture's own shape, once the decoder has told it
    var aspect by remember { mutableFloatStateOf(0f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("CapCraft", "direct playing: the decoder could not play it: $error")
                failed.value = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            runCatching { holdScrubWrites(false) }
        }
    }
    LaunchedEffect(vm.playing, clips) {
        if (!vm.playing) {
            player.pause()
            runCatching { holdScrubWrites(false) }
            return@LaunchedEffect
        }
        fun indexAt(us: Long) = clips.indexOfLast { it.startUs <= us }.coerceAtLeast(0)
        val from = vm.playhead
        val first = indexAt(from)
        Log.i("CapCraft", "direct playing: ${clips.size} clips from ${from / 1000} ms, straight from the file")
        player.setMediaItems(clips.map(::mediaItem), first, ((from - clips[first].startUs) / 1000).coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
        // the phone has one video decoder to give: a copy of this file being written would take it from the player
        // (the first test of this had the picture a second behind the clock, seeking again and again, while a copy was written)
        runCatching { holdScrubWrites(true) }
        var drifts = 0
        var ticks = 0
        try {
            while (isActive) {
                delay(250)
                ticks++
                if (ticks % 8 == 0) {
                    val at = player.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
                    Log.i(
                        "CapCraft",
                        "direct playing: picture at %d ms, clock at %d ms, state %d".format(
                            (clips[at].startUs + player.currentPosition * 1000) / 1000, vm.playhead / 1000, player.playbackState,
                        ),
                    )
                }
                if (player.playbackState != Player.STATE_READY) continue
                val index = player.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
                val shown = clips[index].startUs + player.currentPosition * 1000
                val clock = vm.playhead
                if (abs(shown - clock) > 300_000L) {
                    // aim a little ahead: the seek itself takes a moment
                    val to = clock + 150_000L
                    val at = indexAt(to)
                    drifts++
                    if (drifts <= 5) {
                        Log.i("CapCraft", "direct playing: %.0f ms off the clock, seeking".format((shown - clock) / 1000.0))
                    }
                    player.seekTo(at, ((to - clips[at].startUs) / 1000).coerceAtLeast(0L))
                }
            }
        } finally {
            player.pause()
            runCatching { holdScrubWrites(false) }
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (vm.playing) {
            AndroidView(
                factory = { TextureView(it) },
                update = { player.setVideoTextureView(it) },
                onRelease = { player.clearVideoTextureView(it) },
                // invisible until the shape of the picture is known, so a stretched first frame is not seen
                modifier = if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize().alpha(0f),
            )
        }
    }
}
