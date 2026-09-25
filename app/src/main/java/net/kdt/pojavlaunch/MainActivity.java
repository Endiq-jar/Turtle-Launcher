package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import androidx.core.content.FileProvider;

import java.io.File;

import kr.co.donghyun.flamelauncher.presentation.MinecraftActivity;

public class MainActivity {
    private static final String TAG = "PojavStubMainActivity";

    public static void openLink(String url) {
        Log.d(TAG, "openLink: " + url);
        try {
            Context ctx = MinecraftActivity.Companion.getCurrentInstance();
            if (ctx == null) return;

            // http/https 는 그냥 브라우저로
            if (url.startsWith("http://") || url.startsWith("https://")) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            }

            // 파일 시스템 경로 (마인크래프트는 file:// 도 보내고 raw path 도 보냄)
            String path = url.startsWith("file://") ? url.substring(7) : url;
            File target = new File(path);
            if (!target.exists()) {
                Log.w(TAG, "openLink: 대상 없음 " + path);
                return;
            }

            // 폴더면 디렉토리 트리 표시
            if (target.isDirectory()) {
                // ── 리소스팩 폴더면: 폴더 선택 대신 .zip 파일 피커로 분기 ──
                //   게임 "리소스팩 폴더 열기"는 .../instances/<inst>/resourcepacks/ 를 보낸다.
                //   안드로이드에선 그 폴더에 사용자가 직접 파일을 못 넣으므로(Scoped Storage),
                //   런처가 .zip 을 골라 그 폴더로 복사해 준다.
                String dirName = target.getName();
                if ("resourcepacks".equalsIgnoreCase(dirName)) {
                    MinecraftActivity act = MinecraftActivity.Companion.getCurrentInstance();
                    if (act != null) {
                        final File rpDir = target;
                        act.runOnUiThread(() -> act.openResourcePackPicker(rpDir));
                        return;
                    }
                    // Activity 가 없으면(예외적) 기존 폴더 열기로 폴백
                }
                openDirectory(ctx, target);
            } else {
                openFile(ctx, target);
            }
        } catch (Throwable t) {
            Log.w(TAG, "openLink failed", t);
        }
    }

    private static void openDirectory(Context ctx, File dir) {
        // 1순위: DocumentsUI 로 해당 경로 열기 (안드 11+ 에서도 동작)
        try {
            Uri uri = Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary:"
                            + dir.getAbsolutePath().replace("/storage/emulated/0/", "")
            );
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return;
        } catch (Throwable ignore) { }

        // 2순위: ACTION_OPEN_DOCUMENT_TREE 로 해당 폴더를 시작점으로
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(intent, "폴더 열기"));
        } catch (Throwable t) {
            Log.e(TAG, "openDirectory 전부 실패", t);
        }
    }

    private static void openFile(Context ctx, File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    ctx,
                    ctx.getPackageName() + ".fileprovider",
                    file
            );
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(intent, "파일 열기"));
        } catch (Throwable t) {
            Log.e(TAG, "openFile 실패", t);
        }
    }

    public static void querySystemClipboard() { /* 기존 그대로 */ }
    public static void putClipboardData(String data, String mime) { /* 기존 그대로 */ }
}