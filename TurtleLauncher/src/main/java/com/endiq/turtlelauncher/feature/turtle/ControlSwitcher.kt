package com.endiq.turtlelauncher.feature.turtle

import android.app.Activity
import android.widget.Toast
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.customcontrols.ControlLayout
import java.io.File

/**
 * TurtleLauncher "Control Switcher": tap a button, get the next control layout.
 *
 * This is the same operation the in-game menu already offers under
 * Game Menu -> Control -> "Replace Control Layout" (MainActivity.replacementCustomControls(),
 * which opens SelectControlsDialog) - but with the dialog removed, for people who swap layouts
 * mid-game (e.g. a building layout and a PvP layout) and don't want to open the drawer for it.
 *
 * Layouts are the .json files in [PathManager.DIR_CTRLMAP_PATH], the same directory
 * ControlsListViewCreator lists, sorted by name so the cycle order is stable and predictable.
 * Cycling wraps: after the last layout it comes back to the first. The layout that is active
 * right now is read from [ControlLayout.mLayoutFileName], which loadLayout() sets, so the
 * switcher stays correct even if the layout was chosen through the menu or the editor instead.
 *
 * The button that calls this lives in activity_game.xml (control_switcher_button) and is
 * shown/hidden by AllSettings.controlSwitcherEnabled, toggled in the in-game menu as
 * "Control Switcher".
 */
object ControlSwitcher {

    private const val TAG = "ControlSwitcher"

    /**
     * Every layout the switcher can cycle through, in cycle order (alphabetical by file name).
     *
     * Creates the controlmap directory if it's missing, matching ControlsListViewCreator, so the
     * first call on a fresh install doesn't read a non-existent path.
     */
    @JvmStatic
    fun layouts(): List<File> {
        return runCatching {
            val dir = File(PathManager.DIR_CTRLMAP_PATH)
            if (!dir.exists()) FileTools.mkdirs(dir)
            dir.listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }.getOrElse {
            Logging.e(TAG, "Could not list control layouts", it)
            emptyList()
        }
    }

    /**
     * Loads the layout after the one currently showing and reports which one it switched to.
     *
     * @return the name of the newly loaded layout, or null if nothing was switched - either
     *   because there is no other layout to go to, or because loading the next one failed.
     *   Callers that keep other UI in step with the active layout (the game menu's visibility
     *   depends on whether the new layout has its own menu button) should only act on non-null.
     */
    @JvmStatic
    fun cycleToNext(activity: Activity, controlLayout: ControlLayout): String? {
        val files = layouts()
        if (files.size < 2) {
            // Nothing to cycle between: the active layout is the only one there is.
            toast(activity, activity.getString(R.string.control_switcher_no_other_layouts))
            return null
        }

        // mLayoutFileName is set by loadLayout() (null means the bundled default.json, which is
        // not a file in controlmap, so indexOfFirst simply misses and we start at the top).
        val currentName = controlLayout.mLayoutFileName ?: ""
        val index = files.indexOfFirst { it.nameWithoutExtension == currentName }
        val next = files[(index + 1) % files.size]

        return try {
            controlLayout.loadLayout(next.absolutePath)
            val name = next.nameWithoutExtension
            Logging.i(TAG, "Switched control layout to $name")
            toast(activity, activity.getString(R.string.control_switcher_switched, name))
            name
        } catch (t: Throwable) {
            // A broken/half-written layout JSON must not take the game down; the previous
            // layout stays loaded because loadLayout() only swaps views once parsing succeeded.
            Logging.e(TAG, "Could not switch to control layout ${next.name}", t)
            toast(activity, activity.getString(R.string.control_switcher_failed))
            null
        }
    }

    private fun toast(activity: Activity, message: String) {
        // Must run on the UI thread; the button click already does, but keep this safe for any
        // future caller that triggers a switch from a background task.
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
