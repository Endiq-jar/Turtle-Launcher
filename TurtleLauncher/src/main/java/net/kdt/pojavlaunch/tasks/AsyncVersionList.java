package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.utils.DownloadUtils.downloadString;

import androidx.annotation.Nullable;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.task.Task;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.path.UrlManager;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

/** Class getting the version list, and that's all really */
public class AsyncVersionList {

    public void getVersionList(@Nullable VersionDoneListener listener, boolean secondPass){
        Task.runTask(() -> {
            File versionFile = new File(PathManager.FILE_VERSION_LIST);
            JMinecraftVersionList versionList = null;
            try {
                // TurtleLauncher Fast Boot: skip the network refresh entirely and use
                // whatever cached version list we have on disk, however stale.
                boolean fastBoot = com.endiq.turtlelauncher.setting.AllSettings.getFastBoot().getValue();
                if (!versionFile.exists() || (!fastBoot && ZHTools.getCurrentTimeMillis() > versionFile.lastModified() + 86400000)) {
                    versionList = downloadVersionList(UrlManager.URL_MINECRAFT_VERSION_REPOS);
                }
            } catch (Exception e) {
                Logging.e("AsyncVersionList", "Refreshing version list failed :" + e);
                Logging.e("GetVersionList", Tools.printToString(e));
            }

            // Fallback when no network or not needed
            if (versionList == null) {
                try {
                    versionList = Tools.GLOBAL_GSON.fromJson(new JsonReader(new FileReader(versionFile)), JMinecraftVersionList.class);
                } catch (FileNotFoundException e) {
                    Logging.e("File Not Found", Tools.printToString(e));
                } catch (JsonIOException | JsonSyntaxException e) {
                    Logging.e("AsyncVersionList", Tools.printToString(e));
                    versionFile.delete();
                    if (!secondPass) getVersionList(listener, true);
                }
            }

            if (listener != null) listener.onVersionDone(versionList);
            return null;
        }).execute();
    }


    @SuppressWarnings("SameParameterValue")
    private JMinecraftVersionList downloadVersionList(String mirror) {
        JMinecraftVersionList list = null;
        try {
            Logging.i("ExtVL", "Syncing to external: " + mirror);
            String jsonString = downloadString(mirror);
            list = Tools.GLOBAL_GSON.fromJson(jsonString, JMinecraftVersionList.class);
            Logging.i("ExtVL", "Downloaded the version list, len=" + list.versions.length);

            // Then save the version list
            //TODO make it not save at times ?
            FileOutputStream fos = new FileOutputStream(PathManager.FILE_VERSION_LIST);
            fos.write(jsonString.getBytes());
            fos.close();
        } catch (IOException e) {
            Logging.e("AsyncVersionList", Tools.printToString(e));
        }
        return list;
    }

    /** Basic listener, acting as a callback */
    public interface VersionDoneListener{
        void onVersionDone(JMinecraftVersionList versions);
    }

}
