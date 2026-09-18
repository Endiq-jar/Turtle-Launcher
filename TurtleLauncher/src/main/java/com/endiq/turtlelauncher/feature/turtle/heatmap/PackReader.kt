package com.endiq.turtlelauncher.feature.turtle.heatmap

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.zip.ZipFile

internal abstract class PackReader : Closeable {
    abstract fun forEachEntry(action: (name: String, openStream: () -> InputStream) -> Unit)

    companion object {
        fun open(file: File): PackReader? {
            return when {
                file.isDirectory -> DirReader(file)
                file.isFile && file.name.endsWith(".zip", true) ->
                    runCatching { ZipReader(file) }.getOrNull()
                else -> null
            }
        }
    }

    private class DirReader(private val root: File) : PackReader() {
        override fun forEachEntry(action: (name: String, openStream: () -> InputStream) -> Unit) {
            root.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val relPath = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    action(relPath) { f.inputStream() }
                }
            }
        }
        override fun close() {}
    }

    private class ZipReader(file: File) : PackReader() {
        private val zip = ZipFile(file)
        override fun forEachEntry(action: (name: String, openStream: () -> InputStream) -> Unit) {
            val entries = Collections.list(zip.entries())
            for (entry in entries) {
                if (!entry.isDirectory) {
                    action(entry.name) { zip.getInputStream(entry) }
                }
            }
        }
        override fun close() { zip.close() }
    }
}
