package org.francasio.sing.hooks

import android.content.Context
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import io.github.libxposed.api.XposedInterface
import org.francasio.sing.util.ReflectionUtils
import java.lang.reflect.Executable
import java.util.WeakHashMap

/**
 * Injects an iOS-style inline volume slider into Apple Music's Now Playing screen,
 * below the transport controls (play/skip) and above the lyrics/queue/AirPlay row.
 *
 * Apple Music's Android app has no such slider; this builds one out of Apple's own
 * `MinimalSeekBar` widget (reused for visual consistency) and wires it to the real
 * STREAM_MUSIC volume via AudioManager.
 */
class VolumeSliderHooks(
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit
) {
    companion object {
        private const val TARGET_PACKAGE = "com.apple.android.music"
        private const val PLAYER_CONTROLS_ID_NAME = "player_controls"
        private const val EXTRA_HEIGHT_PERCENT = 0.08f
    }

    // Tracks containers we've already injected into, so repeated seek-bar attaches
    // (track changes, view recycling) don't duplicate the row.
    private val injectedContainers: MutableMap<ViewGroup, Boolean> =
        java.util.Collections.synchronizedMap(WeakHashMap())

    fun install(classLoader: ClassLoader, registerHook: (Executable, (XposedInterface.Chain) -> Any?) -> Unit) {
        try {
            val seekBarClass = ReflectionUtils.findClass(
                "com.apple.android.music.common.MinimalSeekBar",
                classLoader
            ) ?: run {
                logInfo("VolumeSliderHooks: MinimalSeekBar class not found, skipping")
                return
            }

            val onAttached = ReflectionUtils.findMethod(seekBarClass, "onAttachedToWindow") ?: run {
                logInfo("VolumeSliderHooks: onAttachedToWindow not found on MinimalSeekBar, skipping")
                return
            }

            registerHook(onAttached) { chain ->
                val result = chain.proceed()
                try {
                    (chain.thisObject as? View)?.let { maybeInject(it) }
                } catch (t: Throwable) {
                    logError("Failed to inject Now Playing volume slider", t)
                }
                result
            }
            logInfo("Hooked MinimalSeekBar to inject Now Playing volume slider")
        } catch (t: Throwable) {
            logError("Failed to install volume slider hooks", t)
        }
    }

    private fun maybeInject(seekBar: View) {
        val container = findPlayerControlsContainer(seekBar) ?: return
        if (injectedContainers.put(container, true) == true) return

        val context = container.context
        val res = context.resources

        fun idOf(name: String): Int = res.getIdentifier(name, "id", TARGET_PACKAGE)
        fun drawableOf(name: String): Int = res.getIdentifier(name, "drawable", TARGET_PACKAGE)

        val playPauseId = idOf("play_pause")
        val previousId = idOf("previous_rewind")
        val nextId = idOf("next_fast_forward")
        val lyricsId = idOf("player_lyrics")

        if (playPauseId == 0 || lyricsId == 0) {
            logInfo("VolumeSliderHooks: couldn't resolve player_controls view ids, skipping injection")
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: (maxVolume * 2 / 3)

        val rowId = View.generateViewId()
        val iconSizePx = dpToPx(context, 18)
        val sidePaddingPx = dpToPx(context, 20)

        val row = LinearLayout(context).apply {
            id = rowId
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val lowIcon = ImageView(context).apply {
            drawableOf("ic_video_player_volume_low").takeIf { it != 0 }?.let(::setImageResource)
            alpha = 0.55f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val highIcon = ImageView(context).apply {
            drawableOf("ic_video_player_volume_full").takeIf { it != 0 }?.let(::setImageResource)
            alpha = 0.55f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val slider = instantiateMinimalSeekBar(context) ?: SeekBar(context)
        slider.max = maxVolume.coerceAtLeast(1)
        slider.progress = currentVolume.coerceIn(0, slider.max)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                try {
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                } catch (t: Throwable) {
                    logError("Failed to set STREAM_MUSIC volume", t)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        row.addView(lowIcon, LinearLayout.LayoutParams(iconSizePx, iconSizePx))
        row.addView(
            slider,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(context, 10)
                marginEnd = dpToPx(context, 10)
            }
        )
        row.addView(highIcon, LinearLayout.LayoutParams(iconSizePx, iconSizePx))

        container.addView(
            row,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val set = ConstraintSet()
        set.clone(container)
        set.connect(rowId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, sidePaddingPx)
        set.connect(rowId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, sidePaddingPx)
        set.connect(rowId, ConstraintSet.TOP, playPauseId, ConstraintSet.BOTTOM, 0)
        set.connect(rowId, ConstraintSet.BOTTOM, lyricsId, ConstraintSet.TOP, 0)
        set.setVerticalBias(rowId, 0.5f)

        // The transport buttons previously anchored their bottom directly above the
        // lyrics/queue row; re-point them above our new row instead, pushing them up.
        for (buttonId in intArrayOf(playPauseId, previousId, nextId)) {
            if (buttonId != 0) set.connect(buttonId, ConstraintSet.BOTTOM, rowId, ConstraintSet.TOP, 0)
        }
        set.applyTo(container)

        growPlayerControlsHeight(container)

        logInfo("Injected Now Playing volume slider (max=$maxVolume, current=$currentVolume)")
    }

    /**
     * `player_controls` is sized as a fixed percentage of the full Now Playing screen
     * height. Carve out a bit more of that percentage so the new row doesn't cramp the
     * existing transport controls, pushing the artwork/title area up slightly instead.
     */
    private fun growPlayerControlsHeight(container: ViewGroup) {
        try {
            val params = container.layoutParams as? ConstraintLayout.LayoutParams ?: return
            if (params.matchConstraintPercentHeight in 0f..1f) {
                params.matchConstraintPercentHeight =
                    (params.matchConstraintPercentHeight + EXTRA_HEIGHT_PERCENT).coerceAtMost(1f)
                container.layoutParams = params
            }
        } catch (t: Throwable) {
            logError("Failed to grow player_controls height for volume slider", t)
        }
    }

    private fun findPlayerControlsContainer(start: View): ConstraintLayout? {
        var current: View? = start
        var depth = 0
        while (current != null && depth < 8) {
            if (current is ConstraintLayout && current.id != View.NO_ID) {
                val name = try {
                    current.resources.getResourceEntryName(current.id)
                } catch (_: Throwable) {
                    null
                }
                if (name == PLAYER_CONTROLS_ID_NAME) return current
            }
            current = current.parent as? View
            depth++
        }
        return null
    }

    private fun instantiateMinimalSeekBar(context: Context): SeekBar? {
        return try {
            val cls = ReflectionUtils.findClass("com.apple.android.music.common.MinimalSeekBar", context.classLoader)
                ?: return null
            val ctor = cls.getDeclaredConstructor(Context::class.java)
            ctor.isAccessible = true
            ctor.newInstance(context) as? SeekBar
        } catch (_: Throwable) {
            null
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
