package com.endiq.turtlelauncher.launch

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File

/**
 * TurtleLauncher CDS (Class Data Sharing) support.
 *
 * A cold Minecraft JVM start spends real, measurable time re-parsing, re-verifying
 * and re-linking the same few thousand classes every single launch - worse on
 * Android than on desktop, since storage is slower and cores are weaker. CDS lets
 * the JVM dump a pre-parsed/pre-verified snapshot of the classes it loaded and
 * mmap that back in on the next run instead of redoing the work from scratch.
 * This was previously entirely unused by the launcher.
 *
 * Two strategies depending on what the runtime supports:
 *  - Java 19+: a single self-maintaining flag pair, -XX:+AutoCreateSharedArchive,
 *    which creates the archive on the first run and silently refreshes it whenever
 *    it's missing or stale. No manual bookkeeping needed.
 *  - Java 17 (still required for MC 1.18-1.20.4): AutoCreateSharedArchive doesn't
 *    exist yet, so the two-phase dance is managed by hand here -
 *    ArchiveClassesAtExit on the run that has no archive, SharedArchiveFile on
 *    every run after.
 *  - Java 8: Dynamic CDS Archives don't exist at all (added in JDK 13); skipped.
 *
 * One archive per (runtime, Minecraft version) pair, since the class set loaded
 * differs per version and mod loader. If the JVM can't use a given archive for any
 * reason (missing, corrupt, wrong base archive) it just logs a warning internally
 * and launches without it - this is purely an optimization, never a launch
 * blocker, by design of the underlying JVM flags themselves.
 */
object CdsArchiveManager {
    private const val TAG = "CdsArchiveManager"

    /** Dynamic CDS archives were introduced in JDK 13; AutoCreateSharedArchive needs JDK 19+. */
    private const val MIN_JAVA_VERSION_FOR_CDS = 17
    private const val MIN_JAVA_VERSION_FOR_AUTO_ARCHIVE = 19

    private fun archiveDir(runtimeName: String): File =
        File(PathManager.DIR_CACHE, "cds/$runtimeName").apply { if (!exists()) mkdirs() }

    private fun archiveFile(runtimeName: String, versionId: String): File =
        File(archiveDir(runtimeName), "$versionId.jsa")

    /**
     * @param javaVersion     the JRE major version the game will run on (8/17/21/25)
     * @param runtimeName     the runtime's identifying name - archives aren't portable across JREs
     * @param versionId       the Minecraft version id being launched
     * @param versionJsonFile the version's json descriptor file; its mtime is the staleness marker
     *                        (a newer json than the archive means the version was reinstalled/
     *                        updated since we last archived its classes)
     * @return JVM args to append; empty list when CDS isn't applicable for this runtime
     */
    @JvmStatic
    fun resolveArgs(javaVersion: Int, runtimeName: String, versionId: String, versionJsonFile: File): List<String> {
        if (javaVersion < MIN_JAVA_VERSION_FOR_CDS) return emptyList()

        val archive = archiveFile(runtimeName, versionId)

        if (archive.exists() && versionJsonFile.exists() && versionJsonFile.lastModified() > archive.lastModified()) {
            Logging.i(TAG, "CDS archive stale for $versionId (version json newer than archive), deleting for regeneration")
            archive.delete()
        }

        return if (javaVersion >= MIN_JAVA_VERSION_FOR_AUTO_ARCHIVE) {
            listOf(
                "-XX:+AutoCreateSharedArchive",
                "-XX:SharedArchiveFile=${archive.absolutePath}"
            )
        } else if (archive.exists()) {
            Logging.i(TAG, "Using existing CDS archive for $versionId")
            listOf("-XX:SharedArchiveFile=${archive.absolutePath}")
        } else {
            Logging.i(TAG, "No CDS archive yet for $versionId on Java $javaVersion, will record one at exit")
            listOf("-XX:ArchiveClassesAtExit=${archive.absolutePath}")
        }
    }
}
