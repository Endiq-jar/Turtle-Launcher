package com.endiq.turtlelauncher.feature.mod.parser

/**
 * Mod info data cache.
 *
 * [cacheKey] used to be a full SHA-256 of the jar's contents, which meant every
 * mod scan re-read every mod jar byte-for-byte just to find out most of them
 * hadn't changed - the expensive part for a large modpack. It's now a cheap
 * name+size+lastModified fingerprint (see ModParser.fingerprintOf), which is
 * enough to skip re-parsing an unchanged mod without touching its file content
 * at all. Trade-off: a mod replaced with a same-size file inside the same
 * lastModified-resolution window won't invalidate - the same trade-off most
 * incremental build/parse caches make, and unlikely enough for mod jars that
 * it's worth the I/O this avoids on every mods-folder scan.
 */
data class ModInfoCache(val cacheKey: String, val modInfo: ModInfo)
