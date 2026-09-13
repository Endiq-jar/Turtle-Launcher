package com.endiq.turtlelauncher.feature.mod.models;

/**
 * POJO for a CurseForge modpack's manifest.json (schema is unversioned but has been stable
 * for years - same shape every CurseForge-export-compatible tool, including CurseForge's
 * own app, produces). Unlike Modrinth's modrinth.index.json, this only carries
 * project/file *IDs* for each mod, not a direct download URL - actually resolving those
 * requires CurseForge's keyed v1 API (see CurseForgeApi.kt).
 */
public class CurseForgeManifest {
    public String manifestType;
    public int manifestVersion;
    public String name;
    public String version;
    public String author;
    /** Folder inside the zip (almost always "overrides") holding configs/resourcepacks/etc,
     *  extracted as-is into the new instance - same convention as Modrinth's overrides/. */
    public String overrides;

    public MinecraftInfo minecraft;
    public ManifestFile[] files;

    public static class MinecraftInfo {
        public String version;
        public ModLoaderEntry[] modLoaders;
    }

    public static class ModLoaderEntry {
        /** e.g. "forge-47.2.0", "fabric-0.15.11", "quilt-0.20.2", "neoforge-20.4.80" */
        public String id;
        public boolean primary;
    }

    public static class ManifestFile {
        public long projectID;
        public long fileID;
        public boolean required;
    }
}
