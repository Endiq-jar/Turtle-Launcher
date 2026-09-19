package net.kdt.pojavlaunch.modloaders;

import net.kdt.pojavlaunch.utils.DownloadUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Cleanroom (https://github.com/CleanroomMC/Cleanroom) is a modernized Forge fork -
 * unlike Forge/NeoForge/Fabric, it targets ONLY Minecraft 1.12.2, nothing else. There
 * is no Maven repo for it (unlike ForgeUtils), so versions come from GitHub's own
 * Releases API instead.
 *
 * IMPORTANT CAVEAT (from Cleanroom's own README, not guessed): "Only MultiMC-based
 * launchers are officially supported... because of the limit on removing vanilla
 * libraries in other launchers." TurtleLauncher is not MultiMC-based. This class
 * downloads the real installer jar Cleanroom publishes, but whether that installer
 * actually runs correctly through this launcher's install pipeline is UNVERIFIED -
 * Cleanroom's own docs describe this installer-jar path as a fallback that's mainly
 * tested against the vanilla Mojang launcher, not launchers like this one.
 */
public class CleanroomUtils {
    public static final String CLEANROOM_ONLY_MC_VERSION = "1.12.2";

    private static final String CLEANROOM_RELEASES_API = "https://api.github.com/repos/CleanroomMC/Cleanroom/releases";
    private static final String CLEANROOM_INSTALLER_URL =
        "https://github.com/CleanroomMC/Cleanroom/releases/download/%1$s/cleanroom-%1$s-installer.jar";

    /** Cleanroom only ever targets one MC version - this is a cheap check before even fetching anything. */
    public static boolean isCompatible(String mcVersion) {
        return CLEANROOM_ONLY_MC_VERSION.equals(mcVersion);
    }

    public static List<String> downloadCleanroomVersions(String mcVersion, boolean force) throws Exception {
        if (!isCompatible(mcVersion)) return new ArrayList<>();

        String response = DownloadUtils.downloadStringCached(CLEANROOM_RELEASES_API, "cleanroom_versions", force, input -> input);
        JSONArray releases = new JSONArray(response);
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            versions.add(release.getString("tag_name"));
        }
        return versions;
    }

    public static String getInstallerUrl(String version) {
        return String.format(CLEANROOM_INSTALLER_URL, version);
    }
}
