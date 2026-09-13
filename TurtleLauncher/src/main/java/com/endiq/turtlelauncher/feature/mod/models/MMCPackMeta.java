package com.endiq.turtlelauncher.feature.mod.models;

/**
 * POJO for a MultiMC/PrismLauncher instance export's mmc-pack.json. Real, external,
 * documented format (meta.multimc.org / meta.prismlauncher.org both publish components
 * under these exact uids) - not invented for this launcher. Unlike CurseForge/Modrinth's
 * manifests, this carries no Mojang-format version.json and no list of mods to download -
 * a MultiMC export already has the actual .minecraft-equivalent content (mods/saves/
 * configs/resourcepacks/etc) sitting in the zip, so importing means: extract that content,
 * then hand the version+loader found here to TurtleLauncher's normal installer pipeline
 * the same way every other modpack type in this package does.
 */
public class MMCPackMeta {
    public int formatVersion;
    public Component[] components;

    public static class Component {
        /** e.g. "net.minecraft", "net.fabricmc.fabric-loader", "net.minecraftforge",
         *  "org.quiltmc.quilt-loader", "net.neoforged" */
        public String uid;
        public String version;
        public String cachedName;
    }
}
