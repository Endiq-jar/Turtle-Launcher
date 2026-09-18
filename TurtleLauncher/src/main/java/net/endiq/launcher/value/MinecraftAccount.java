package net.endiq.launcher.value;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.google.gson.JsonSyntaxException;
import com.endiq.turtlelauncher.feature.accounts.AccountsManager;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.skin.SkinFileDownloader;
import com.endiq.turtlelauncher.utils.stringutils.StringUtilsKt;

import net.endiq.launcher.Tools;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Keep
public class MinecraftAccount {
    public String accessToken = "0"; // access token
    public String clientToken = "0"; // clientID: refresh and invalidate
    public String profileId = "00000000-0000-0000-0000-000000000000"; // profile UUID, for obtaining skin
    public String username = "Steve";
    public String msaRefreshToken = "0";
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

    public void save() throws IOException {
        Tools.write(PathManager.DIR_ACCOUNT_NEW + "/" + uniqueUUID, Tools.GLOBAL_GSON.toJson(this));
    }
    
    public static MinecraftAccount parse(String content) throws JsonSyntaxException {
        MinecraftAccount acc = Tools.GLOBAL_GSON.fromJson(content, MinecraftAccount.class);
        // Explicit nulls in the JSON overwrite the field defaults above (Gson sets
        // them literally), and reloadInternal() adds parsed accounts to the live
        // list WITHOUT loadFromUniqueUUID()'s defaulting - so normalize here at
        // the single choke point every load path goes through. Missing/null
        // tokens used to NPE later at launch (auth_xuid map put, profileId.replace).
        if (acc == null) return null;
        if (acc.accessToken == null) acc.accessToken = "0";
        if (acc.clientToken == null) acc.clientToken = "0";
        if (acc.profileId == null) acc.profileId = "00000000-0000-0000-0000-000000000000";
        if (acc.username == null) acc.username = "Steve";
        if (acc.msaRefreshToken == null) acc.msaRefreshToken = "0";
        if (acc.xuid == null) acc.xuid = "0";
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
            if (acc.accessToken == null) {
                acc.accessToken = "0";
            }
            if (acc.clientToken == null) {
                acc.clientToken = "0";
            }
            if (acc.profileId == null) {
                acc.profileId = "00000000-0000-0000-0000-000000000000";
            }
            if (acc.username == null) {
                acc.username = "0";
            }
            if (acc.msaRefreshToken == null) {
                acc.msaRefreshToken = "0";
            }
            return acc;
        } catch(IOException | JsonSyntaxException e) {
            Logging.e(MinecraftAccount.class.getName(), "Caught an exception while loading the profile",e);
            return null;
        }
    }

    private static boolean accountExists(String uniqueUUID) {
        return uniqueUUID != null && !uniqueUUID.isEmpty() && new File(PathManager.DIR_ACCOUNT_NEW + "/" + uniqueUUID).exists();
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
