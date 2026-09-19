package net.kdt.pojavlaunch.value;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.endiq.turtlelauncher.feature.accounts.AccountsManager;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.skin.SkinFileDownloader;
import com.endiq.turtlelauncher.utils.stringutils.StringUtilsKt;

import com.google.gson.JsonSyntaxException;

import net.kdt.pojavlaunch.Tools;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Merged account model: the Turtle Launcher account system (offline/local/other-login
 * account types with unique-UUID storage, Ely.by/Battly other-login fields, offline
 * UUID generation and the Turtle skin downloader) on top of the fields the Amethyst
 * core expects (selectedVersion, isMicrosoft, expiresAt, skin face cache).
 */
public class MinecraftAccount {
    public String username = "Steve";
    public String accessToken = "0";
    public String clientToken = "0";
    public String profileId = "00000000-0000-0000-0000-000000000000";
    public String msaRefreshToken = "0";
    public String skinFaceBase64;

    // Amethyst core fields
    public String selectedVersion = "1.7.10";
    public boolean isMicrosoft = false;
    public long expiresAt;

    // Local/offline and Other Login accounts never receive a real xuid - default
    // it like every other token field so launch-time map puts (which require
    // non-null values) can't NPE on it. Microsoft login overwrites it anyway.
    public String xuid = "0";
    public String otherBaseUrl;
    public String otherAccount;
    public String otherPassword;
    public String accountType;
    private final String uniqueUUID = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);

    public static java.util.UUID generateOfflineUUID(String username) {
        return java.util.UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    public boolean isLocal(){
        return accessToken.equals("0") && !username.startsWith("Demo.");
    }

    public boolean isDemo(){
        return username.startsWith("Demo.");
    }

    public void updateMicrosoftSkin() {
        updateSkin("https://sessionserver.mojang.com");
    }

    public void updateOtherSkin() {
        updateSkin(StringUtilsKt.removeSuffix(otherBaseUrl, "/") + "/sessionserver/");
    }

    private void updateSkin(String url) {
        File skinFile = new File(PathManager.DIR_USER_SKIN, uniqueUUID + ".png");
        if (skinFile.exists()) FileUtils.deleteQuietly(skinFile); // clear any cached skin file
        try {
            new SkinFileDownloader().yggdrasil(url, skinFile, profileId);
            Logging.i("SkinLoader", "Update skin success");
        } catch (Exception e) {
            Logging.i("SkinLoader", "Could not update skin\n" + Tools.printToString(e));
        }
    }

    /** Amethyst-style head refresh, used by the Amethyst-side UI pieces. */
    void updateSkinFace(String uuid) {
        try {
            File skinFile = getSkinFaceFile(username);
            Tools.downloadFile("https://mc-heads.net/head/" + uuid + "/100", skinFile.getAbsolutePath());
            Log.i("SkinLoader", "Update skin face success");
        } catch (IOException e) {
            // Skin refresh limit, no internet connection, etc...
            Log.w("SkinLoader", "Could not update skin face", e);
        }
    }

    public void updateSkinFace() {
        updateSkinFace(profileId);
    }

    public void saveToDiskSafe() {
        try {
            save();
        } catch (IOException e) {
            Logging.e(MinecraftAccount.class.getName(), "Failed to save the profile", e);
        }
    }

    public void save() throws IOException {
        Tools.write(PathManager.DIR_ACCOUNT_NEW + "/" + uniqueUUID, Tools.GLOBAL_GSON.toJson(this));
    }

    /** Amethyst compat: username-keyed save used by core flows. */
    public String save(String outPath) throws IOException {
        Tools.write(outPath, Tools.GLOBAL_GSON.toJson(this));
        return username;
    }

    public static MinecraftAccount parse(String content) {
        MinecraftAccount acc = Tools.GLOBAL_GSON.fromJson(content, MinecraftAccount.class);
        // Explicit nulls in the JSON overwrite the field defaults above (Gson sets
        // them literally), so normalize here at the single choke point every load
        // path goes through. Missing/null tokens used to NPE later at launch
        // (auth_xuid map put, profileId.replace).
        if (acc == null) return null;
        if (acc.accessToken == null) acc.accessToken = "0";
        if (acc.clientToken == null) acc.clientToken = "0";
        if (acc.profileId == null) acc.profileId = "00000000-0000-0000-0000-000000000000";
        if (acc.username == null) acc.username = "Steve";
        if (acc.msaRefreshToken == null) acc.msaRefreshToken = "0";
        if (acc.xuid == null) acc.xuid = "0";
        if (acc.accountType == null) acc.accountType = "microsoft";
        return acc;
    }

    public static MinecraftAccount loadFromProfileID(String profileID) {
        for (MinecraftAccount account : AccountsManager.INSTANCE.getAllAccounts()) {
            if (Objects.equals(account.profileId, profileID)) return account;
        }
        return null;
    }

    public static MinecraftAccount loadFromUniqueUUID(String uniqueUUID) {
        if(!accountExists(uniqueUUID)) return null;
        try {
            MinecraftAccount acc = parse(Tools.read(PathManager.DIR_ACCOUNT_NEW + "/" + uniqueUUID));
            // A file containing literal "null" parses to a null account - every
            // field access below would NPE (and NPE isn't in the catch clause).
            if (acc == null) return null;
            if (acc.selectedVersion == null) {
                acc.selectedVersion = "1.7.10";
            }
            return acc;
        } catch(IOException | JsonSyntaxException e) {
            Logging.e(MinecraftAccount.class.getName(), "Caught an exception while loading the profile",e);
            return null;
        }
    }

    /**
     * Amethyst compat: the core stores/loads accounts by name. The Turtle account
     * store keys files by uniqueUUID, so resolve by UUID first, then by username
     * across the known accounts.
     */
    @Nullable
    public static MinecraftAccount load(String name) {
        MinecraftAccount byUuid = loadFromUniqueUUID(name);
        if (byUuid != null) return byUuid;
        for (MinecraftAccount account : AccountsManager.INSTANCE.getAllAccounts()) {
            if (Objects.equals(account.username, name)) return account;
        }
        return null;
    }

    @Nullable
    public Bitmap getSkinFace(){
        if (isLocal()) return null;

        File skinFaceFile = getSkinFaceFile(username);
        if (!skinFaceFile.exists()) {
            // Legacy version, storing the head inside the json as base 64
            if (skinFaceBase64 == null) return null;
            byte[] faceIconBytes = Base64.decode(skinFaceBase64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(faceIconBytes, 0, faceIconBytes.length);
        }

        return BitmapFactory.decodeFile(skinFaceFile.getAbsolutePath());
    }

    @Nullable
    public static Bitmap getSkinFace(String username) {
        return BitmapFactory.decodeFile(getSkinFaceFile(username).getAbsolutePath());
    }

    private static File getSkinFaceFile(String username) {
        return new File(PathManager.DIR_CACHE, username + ".png");
    }

    private static boolean accountExists(String uniqueUUID) {
        return uniqueUUID != null && !uniqueUUID.isEmpty()
                && new File(PathManager.DIR_ACCOUNT_NEW + "/" + uniqueUUID).exists();
    }

    public String getUniqueUUID() {
        return this.uniqueUUID;
    }

    @NonNull
    @Override
    public String toString() {
        return "MinecraftAccount{" +
                "username='" + username + '\'' +
                ", accountType=" + accountType +
                '}';
    }
}
