package net.kdt.pojavlaunch.mirrors;

import android.app.Activity;
import android.content.Context;
import android.text.Html;

import androidx.appcompat.app.AlertDialog;

import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.setting.AllSettings;

import net.kdt.pojavlaunch.ShowErrorActivity;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;

public class MirrorTamperedException extends Exception implements ContextExecutorTask {
    // Do not change. Android really hates when this value changes for some reason.
    private static final long serialVersionUID = -7482301619612640658L;
    @Override
    public void executeWithActivity(Activity activity) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.CustomAlertDialogTheme);
        builder.setTitle(R.string.dl_tampered_manifest_title);
        builder.setMessage(Html.fromHtml(activity.getString(R.string.dl_tampered_manifest)));
        addButtons(builder);
        ShowErrorActivity.installRemoteDialogHandling(activity, builder);
        builder.show();
    }

    private void addButtons(AlertDialog.Builder builder) {
        builder.setPositiveButton(R.string.dl_switch_to_official_site, (d,w) -> AllSettings.getDownloadSource().reset());
        builder.setNegativeButton(R.string.dl_turn_off_manifest_checks, (d,w) -> AllSettings.getVerifyManifest().put(false).save());
        builder.setNeutralButton(android.R.string.cancel, (d,w) -> {});
    }

    @Override
    public void executeWithApplication(Context context) {}
}
