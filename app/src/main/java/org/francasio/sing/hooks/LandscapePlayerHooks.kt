package org.francasio.sing.hooks

import android.content.res.Configuration
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import io.github.libxposed.api.XposedInterface
import org.francasio.sing.util.ReflectionUtils
import java.lang.reflect.Executable
import java.util.WeakHashMap

/**
 * Apple Music's Now Playing screen (`music_player.xml`) has no landscape layout variant.
 * MainActivity isn't orientation-locked and recreates on rotation (no configChanges flag),
 * so it just reinflates the portrait layout stretched wide/short. This reflows it into a
 * two-column layout (artwork left, metadata + controls right) whenever the screen is
 * actually laid out in landscape, leaving the portrait layout untouched otherwise.
 */
class LandscapePlayerHooks(
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit
) {
    companion object {
        private const val TARGET_PACKAGE = "com.apple.android.music"
        private const val PLAYER_CONTAINER_ID_NAME = "player_container"
        private const val PLAYER_CONTROLS_ID_NAME = "player_controls"

        private const val ARTWORK_GUIDE_START = 0.05f
        private const val ARTWORK_GUIDE_END = 0.46f
        private const val METADATA_GUIDE_START = 0.50f
        private const val METADATA_GUIDE_END = 0.95f
    }

    // Keyed by the music_player.xml root; a rotation fully recreates this (no
    // configChanges on MainActivity), so a stale entry never outlives its screen.
    private val handledContainers: MutableMap<ConstraintLayout, Boolean> =
        java.util.Collections.synchronizedMap(WeakHashMap())

    fun install(classLoader: ClassLoader, registerHook: (Executable, (XposedInterface.Chain) -> Any?) -> Unit) {
        try {
            val seekBarClass = ReflectionUtils.findClass(
                "com.apple.android.music.common.MinimalSeekBar",
                classLoader
            ) ?: run {
                logInfo("LandscapePlayerHooks: MinimalSeekBar class not found, skipping")
                return
            }

            val onAttached = ReflectionUtils.findMethod(seekBarClass, "onAttachedToWindow") ?: run {
                logInfo("LandscapePlayerHooks: onAttachedToWindow not found on MinimalSeekBar, skipping")
                return
            }

            registerHook(onAttached) { chain ->
                val result = chain.proceed()
                try {
                    (chain.thisObject as? View)?.let { maybeReflow(it) }
                } catch (t: Throwable) {
                    logError("Failed to apply landscape Now Playing layout", t)
                }
                result
            }
            logInfo("Hooked MinimalSeekBar to apply landscape Now Playing layout")
        } catch (t: Throwable) {
            logError("Failed to install landscape player hooks", t)
        }
    }

    private fun maybeReflow(seekBar: View) {
        val context = seekBar.context
        if (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return

        val container = findAncestorByIdName(seekBar, PLAYER_CONTAINER_ID_NAME) ?: return
        if (handledContainers.put(container, true) == true) return

        val res = container.resources
        fun idOf(name: String): Int = res.getIdentifier(name, "id", TARGET_PACKAGE)

        val artworkContainerId = idOf("artwork_container")
        val artworkGuideStartId = idOf("artwork_guideline_start")
        val artworkGuideEndId = idOf("artwork_guideline_end")
        val metadataGuideStartId = idOf("metadata_guideline_start")
        val metadataGuideEndId = idOf("metadata_guideline_end")
        val playerControlsId = idOf(PLAYER_CONTROLS_ID_NAME)

        if (artworkContainerId == 0 || artworkGuideStartId == 0 || artworkGuideEndId == 0 ||
            metadataGuideStartId == 0 || metadataGuideEndId == 0 || playerControlsId == 0
        ) {
            logInfo("Landscape Now Playing: couldn't resolve required view ids, skipping reflow")
            return
        }

        try {
            val set = ConstraintSet()
            set.clone(container)

            // Split the screen into an artwork column and a metadata/controls column.
            set.setGuidelinePercent(artworkGuideStartId, ARTWORK_GUIDE_START)
            set.setGuidelinePercent(artworkGuideEndId, ARTWORK_GUIDE_END)
            set.setGuidelinePercent(metadataGuideStartId, METADATA_GUIDE_START)
            set.setGuidelinePercent(metadataGuideEndId, METADATA_GUIDE_END)

            // Portrait anchors the artwork's bottom to a barrier above the title text
            // (which sat below it). In landscape the title sits beside it instead, so
            // let the artwork fill the space above the controls band and stay centered
            // there via its 1:1 ratio.
            set.connect(artworkContainerId, ConstraintSet.BOTTOM, playerControlsId, ConstraintSet.TOP, 0)

            set.applyTo(container)
            logInfo("Applied landscape two-column layout to Now Playing screen")
        } catch (t: Throwable) {
            logError("Failed to apply landscape ConstraintSet", t)
        }
    }

    private fun findAncestorByIdName(start: View, idName: String): ConstraintLayout? {
        var current: View? = start
        var depth = 0
        while (current != null && depth < 10) {
            if (current is ConstraintLayout && current.id != View.NO_ID) {
                val name = try {
                    current.resources.getResourceEntryName(current.id)
                } catch (_: Throwable) {
                    null
                }
                if (name == idName) return current
            }
            current = current.parent as? View
            depth++
        }
        return null
    }
}
