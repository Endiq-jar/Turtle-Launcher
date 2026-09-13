package com.endiq.turtlelauncher.event.value

/**
 * Events used by the download pages.
 */
class DownloadPageEvent {
    /**
     * When switching download pages, this event tells the Fragment to play its animation.
     * @param index category index of the Fragment
     * @param classify animation direction (IN: enter, OUT: exit)
     */
    class PageSwapEvent(val index: Int, val classify: Int) {
        companion object {
            const val IN = 0
            const val OUT = 1
        }
    }

    /**
     * Event raised when the download page is destroyed.
     */
    class PageDestroyEvent

    /**
     * Whether the RecyclerView is disabled.
     */
    class RecyclerEnableEvent(val enable: Boolean)
}