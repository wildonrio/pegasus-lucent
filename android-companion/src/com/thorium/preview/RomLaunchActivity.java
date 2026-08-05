package com.thorium.preview;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.util.Locale;

/** Converts a filesystem ROM path from Pegasus into a one-time content URI. */
public final class RomLaunchActivity extends Activity {
    public static final String ACTION_LAUNCH_FILE = "com.thorium.preview.LAUNCH_FILE";
    private static final String AUTHORITY = "com.thorium.preview.roms";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launch(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launch(intent);
    }

    private void launch(Intent request) {
        String path = request.getStringExtra("path");
        String targetPackage = request.getStringExtra("target_package");
        String targetActivity = request.getStringExtra("target_activity");
        String targetAction = request.getStringExtra("target_action");
        if (path == null || targetPackage == null || targetActivity == null) {
            finish();
            return;
        }

        boolean eden = "dev.legacy.eden_emulator".equals(targetPackage) &&
                "org.yuzu.yuzu_emu.activities.EmulationActivity".equals(targetActivity);
        boolean cemu = "info.cemu.cemu".equals(targetPackage) &&
                "info.cemu.cemu.emulation.EmulationActivity".equals(targetActivity);
        String lower = path.toLowerCase(Locale.US);
        boolean switchRom = lower.endsWith(".xci") || lower.endsWith(".nsp") ||
                lower.endsWith(".nca") || lower.endsWith(".nsz");
        boolean wiiURom = lower.endsWith(".wux") || lower.endsWith(".wud") ||
                lower.endsWith(".rpx");
        if (!(eden && switchRom) && !(cemu && wiiURom)) {
            finish();
            return;
        }

        String filename = new File(path).getName();
        Uri uri = new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("rom").appendPath(filename)
                .appendQueryParameter("path", path).build();
        int grant = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        grantUriPermission(targetPackage, uri, grant);
        if (targetActivity.startsWith(".")) targetActivity = targetPackage + targetActivity;
        if (targetAction == null || targetAction.isEmpty()) targetAction = Intent.ACTION_VIEW;

        Intent launch = new Intent(targetAction)
                .setComponent(new ComponentName(targetPackage, targetActivity))
                .setDataAndType(uri, "application/octet-stream")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP | grant);
        launch.setClipData(ClipData.newRawUri("rom", uri));
        try {
            startActivity(launch);
        } finally {
            finish();
        }
    }
}
