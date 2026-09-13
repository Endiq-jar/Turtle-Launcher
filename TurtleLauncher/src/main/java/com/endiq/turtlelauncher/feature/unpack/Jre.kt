package com.endiq.turtlelauncher.feature.unpack

import com.endiq.turtlelauncher.R

/**
 * TurtleLauncher: Java 8 is bundled as an APK asset so legacy Minecraft
 * (1.16.5 and below) works fully offline on first launch, instead of
 * needing a network round-trip to the Mojo CDN the way Java 17/21/25 do
 * via TurtleJREAutoInstaller.
 *
 * jreName MUST be "Internal-8" to match TurtleJREAutoInstaller's own
 * Java 8 tier — that way, if this bundled copy is present, the auto
 * installer's "already installed" fast path skips re-downloading it, but
 * will still silently fetch a newer build from the CDN once the normal
 * 3-day update-check interval is up. Best of both: instant offline
 * availability + still keeps itself current over the network.
 *
 * Required asset files under app/src/main/assets/components/jre8/:
 *   version            - plain text version string
 *   universal.tar.xz    - architecture-independent JRE files
 *   bin-<arch>.tar.xz   - one per supported ABI: arm64, arm, x86, x86_64
 *
 * These are the exact same files TurtleJREAutoInstaller downloads from
 * https://mojolauncher.github.io/jre-download/components/jre-legacy/ —
 * fetch them once and drop them in place; see INTEGRATION notes.
 *
 * If the asset folder is missing, UnpackJreTask.isCheckFailed() returns
 * true and SplashActivity silently skips this entry — the app still
 * builds and runs fine without the files, it just won't have an offline
 * bundled Java 8 until they're added.
 */
enum class Jre(val jreName: String, val jrePath: String, val summary: Int) {
    JAVA_8("Internal-8", "components/jre8", R.string.jre_8_summary)
}
