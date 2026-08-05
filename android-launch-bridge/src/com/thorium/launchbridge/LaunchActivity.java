package com.thorium.launchbridge;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.DocumentsContract;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Warms Citron's game-directory filesystem bridge before forwarding the ROM.
 * Citron's exported VIEW activity cannot cold-start document URIs on its own.
 */
public final class LaunchActivity extends Activity {
    private static final String ACTION_LAUNCH_FILE = "com.thorium.launchbridge.LAUNCH_FILE";
    private static final String ACTION_SETUP_ACCESS = "com.thorium.launchbridge.SETUP_ACCESS";
    private static final int REQUEST_GAMES_TREE = 70;
    private static final String ROM_AUTHORITY = "com.thorium.launchbridge.roms";
    private static final String CITRON_PACKAGE = "org.citron.citron_emu";
    private static final String DOLPHIN_PACKAGE = "org.dolphinemu.dolphinemu";
    private static final String PREVIEW_PACKAGE = "com.thorium.preview";
    private static final String PREVIEW_BLANK = "com.thorium.preview.BLANK";
    private static final String PREVIEW_HIDE = "com.thorium.preview.HIDE";
    private static final ComponentName CITRON_MAIN = new ComponentName(
            CITRON_PACKAGE,
            "org.citron.citron_emu.ui.main.MainActivity");
    private static final ComponentName CITRON_EMULATION = new ComponentName(
            CITRON_PACKAGE,
            "org.citron.citron_emu.activities.EmulationActivity");
    private static final ComponentName DOLPHIN_APP_LINK = new ComponentName(
            DOLPHIN_PACKAGE,
            "org.dolphinemu.dolphinemu.activities.AppLinkActivity");
    private static final ComponentName PREVIEW_ACTIVITY = new ComponentName(
            PREVIEW_PACKAGE,
            "com.thorium.preview.PreviewActivity");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ACTION_SETUP_ACCESS.equals(getIntent().getAction())) {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents", "primary:Games"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(picker, REQUEST_GAMES_TREE);
            return;
        }

        if (ACTION_LAUNCH_FILE.equals(getIntent().getAction())) {
            launchLocalFile(getIntent());
            return;
        }

        final Uri gameUri = getIntent().getData();
        if (gameUri == null) {
            finish();
            return;
        }

        warmCitron(gameUri);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_GAMES_TREE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(data.getData(), flags);
        }
        finish();
    }

    private void warmCitron(final Uri gameUri) {
        grantUriPermission(CITRON_PACKAGE, gameUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent warm = new Intent(Intent.ACTION_MAIN)
                .setComponent(CITRON_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startOnUpperDisplay(warm);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                String filename = gameUri.getLastPathSegment();
                if (filename == null) filename = "Switch Game";
                String title = filename.replaceFirst("\\.[^.]+$", "")
                        .replaceAll("\\s*\\[[^]]+\\]", "")
                        .trim();
                try {
                    Class<?> gameClass = Class.forName("org.citron.citron_emu.model.Game");
                    Method programIdMethod = gameClass.getMethod(
                            "programIdFromFilename", String.class);
                    String programId = (String) programIdMethod.invoke(null, filename);
                    Constructor<?> constructor = gameClass.getConstructor(
                            String.class, String.class, String.class, String.class,
                            String.class, boolean.class);
                    Parcelable game = (Parcelable) constructor.newInstance(
                            title, gameUri.toString(), programId, "", "", false);

                    Intent launch = new Intent(Intent.ACTION_VIEW)
                            .setComponent(CITRON_EMULATION)
                            .setData(gameUri)
                            .putExtra("game", game)
                            .putExtra("custom", false)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    launch.setClipData(ClipData.newRawUri("game", gameUri));
                    startOnUpperDisplay(launch);
                } catch (ReflectiveOperationException ignored) {
                    // Citron changed its private game model; leave its main UI open safely.
                }
                finish();
            }
        }, 5000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_LAUNCH_FILE.equals(intent.getAction())) {
            launchLocalFile(intent);
        }
    }

    private void launchLocalFile(Intent request) {
        String path = request.getStringExtra("path");
        String targetPackage = request.getStringExtra("target_package");
        String targetActivity = request.getStringExtra("target_activity");
        String targetAction = request.getStringExtra("target_action");
        String mime = request.getStringExtra("mime");
        if (path == null) {
            finish();
            return;
        }

        // This is a second line of defense behind the Pegasus theme. It also
        // covers bridge launches issued by shortcuts or other frontends.
        boolean usesBottomScreen = request.getBooleanExtra("dual_screen", false)
                || "info.cemu.cemu".equals(targetPackage);
        sendBroadcast(new Intent(usesBottomScreen ? PREVIEW_HIDE : PREVIEW_BLANK)
                .setPackage(PREVIEW_PACKAGE));
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";

        String uriExtension = request.getStringExtra("uri_extension");
        Uri uri = request.getBooleanExtra("external_uri", false)
                ? externalStorageDocumentUri(path)
                : contentUriForPath(path, uriExtension);
        if (request.getBooleanExtra("switch_mode", false)) {
            warmCitron(externalStorageDocumentUri(path));
            return;
        }

        if (targetPackage == null || targetActivity == null) {
            finish();
            return;
        }
        if (targetActivity.startsWith("."))
            targetActivity = targetPackage + targetActivity;

        // Dolphin's exported MainActivity does not reliably consume a second
        // file VIEW intent after its menu has already been created.  Route
        // indexed games through Dolphin's own app-link activity instead.  That
        // activity resolves the disc ID in Dolphin's library and starts the
        // non-exported EmulationActivity from inside Dolphin's process.
        String dolphinGameId = request.getStringExtra("dolphin_game_id");
        if (DOLPHIN_PACKAGE.equals(targetPackage)
                && dolphinGameId != null && !dolphinGameId.trim().isEmpty()) {
            // AppLinkActivity can be cold-started, but Dolphin 2606 checks its
            // in-memory game array before the array has been populated and
            // reports a perfectly valid disc ID as "Invalid Game".  Starting
            // MainActivity first initializes that array from Dolphin's cached
            // library.  Keep this warm-up even when Dolphin has an existing
            // task: Android may reclaim the process (and therefore the array)
            // while retaining the task's visual state.
            Intent dolphinWarm = new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(
                            DOLPHIN_PACKAGE,
                            "org.dolphinemu.dolphinemu.ui.main.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // The Thor has a dedicated lower display. Initialize Dolphin there
            // so its menu never replaces Pegasus on the upper screen.
            startOnDisplay(dolphinWarm, 4);

            final Intent dolphinLaunch = new Intent(Intent.ACTION_VIEW)
                    .setComponent(DOLPHIN_APP_LINK)
                    .setData(Uri.parse("dolphinemu://app/play/0/"
                            + Uri.encode(dolphinGameId.trim())))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // Reclaim the lower display with an already-black preview before
            // the game takes over the upper display. Empty extras prevent the
            // activity from briefly restoring stale preview media.
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    Intent preview = new Intent(Intent.ACTION_MAIN)
                            .setComponent(PREVIEW_ACTIVITY)
                            .putExtra("video", "")
                            .putExtra("art", "")
                            .putExtra("title", "")
                            .putExtra("system", "")
                            .putExtra("score", "")
                            .putExtra("sequence", 0L)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startOnDisplay(preview, 4);
                    sendBroadcast(new Intent(PREVIEW_BLANK)
                            .setPackage(PREVIEW_PACKAGE));
                }
            }, 900L);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        startOnUpperDisplay(dolphinLaunch);
                    } finally {
                        finish();
                    }
                }
            }, 1200L);
            return;
        }

        int readGrant = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        grantUriPermission(targetPackage, uri, readGrant);

        if (targetAction == null || targetAction.isEmpty())
            targetAction = Intent.ACTION_VIEW;

        int activityFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP | readGrant;
        if (DOLPHIN_PACKAGE.equals(targetPackage)) {
            // Dolphin only consumes a file VIEW intent during MainActivity's
            // clean creation. If its empty library/menu task already exists,
            // onNewIntent leaves that menu visible and silently drops the ROM.
            // Always replace Dolphin's stale task so a controller launch has
            // exactly the same behavior whether Dolphin is cold or already open.
            activityFlags |= Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        }

        Intent launch = new Intent(targetAction)
                .setComponent(new ComponentName(targetPackage, targetActivity))
                .addFlags(activityFlags);
        launch.setClipData(ClipData.newRawUri("rom", uri));
        if (request.getBooleanExtra("data_only", false))
            launch.setData(uri);
        else
            launch.setDataAndType(uri, mime);
        if (DOLPHIN_PACKAGE.equals(targetPackage)) {
            // Clearing Dolphin's task is insufficient: its process caches the
            // initialized MainActivity state and ignores the next file intent.
            // Dolphin is backgrounded behind this bridge, so terminate that
            // cached process and deliver the launch to a genuinely cold app.
            ActivityManager activityManager =
                    (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (activityManager != null)
                activityManager.killBackgroundProcesses(DOLPHIN_PACKAGE);
            final Intent dolphinLaunch = launch;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        startOnUpperDisplay(dolphinLaunch);
                    } finally {
                        finish();
                    }
                }
            }, 350L);
            return;
        }

        try {
            startOnUpperDisplay(launch);
        } finally {
            finish();
        }
    }

    private void startOnUpperDisplay(Intent intent) {
        startOnDisplay(intent, 0);
    }

    private void startOnDisplay(Intent intent, int displayId) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        startActivity(intent, options.toBundle());
    }

    private Uri contentUriForPath(String path) {
        return contentUriForPath(path, null);
    }

    private Uri contentUriForPath(String path, String replacementExtension) {
        String filename = new File(path).getName();
        int dot = filename.lastIndexOf('.');
        if (replacementExtension != null && !replacementExtension.isEmpty()) {
            if (!replacementExtension.startsWith(".")) replacementExtension = "." + replacementExtension;
            filename = (dot >= 0 ? filename.substring(0, dot) : filename) + replacementExtension;
        } else if (dot >= 0) {
            filename = filename.substring(0, dot) + filename.substring(dot).toLowerCase();
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(ROM_AUTHORITY)
                .appendPath("rom")
                // Native emulators often determine format from the URI path, not DISPLAY_NAME.
                .appendPath(filename)
                .appendQueryParameter("path", path)
                .build();
    }

    private Uri externalStorageDocumentUri(String path) {
        String prefix = "/storage/emulated/0/";
        if (!path.startsWith(prefix)) return contentUriForPath(path);
        String documentId = "primary:" + path.substring(prefix.length());
        Uri gamesTree = DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents", "primary:Games");
        return DocumentsContract.buildDocumentUriUsingTree(gamesTree, documentId);
    }
}
