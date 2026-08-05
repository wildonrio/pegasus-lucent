package com.thorium.preview;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Human-facing setup and status screen. PreviewActivity is display-only. */
public final class MainActivity extends Activity {
    private static final int STORAGE_REQUEST = 41;
    private static final String PEGASUS_PACKAGE = "org.pegasus_frontend.android";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView serviceStatus;
    private TextView themeStatus;
    private TextView permissionStatus;
    private TextView activityStatus;
    private boolean polling;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            refreshStatus();
            handler.postDelayed(this, 1500L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(5, 7, 11));
        getWindow().setNavigationBarColor(Color.rgb(5, 7, 11));
        buildUi();
        startLucentService();
        requestStorageIfNeeded(false);
    }

    @Override protected void onResume() {
        super.onResume();
        polling = true;
        handler.removeCallbacks(poller);
        handler.post(poller);
    }

    @Override protected void onPause() {
        polling = false;
        handler.removeCallbacks(poller);
        super.onPause();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(background(new int[]{Color.rgb(6, 9, 14), Color.rgb(12, 18, 27)}, 0));
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, fill());

        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER_HORIZONTAL);
        stage.setPadding(dp(24), dp(34), dp(24), dp(34));
        scroll.addView(stage, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int screen = getResources().getDisplayMetrics().widthPixels;
        int contentWidth = Math.min(screen - dp(48), dp(780));
        stage.addView(content, new LinearLayout.LayoutParams(contentWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView mark = label("LUCENT  /  ANDROID COMPANION", 12, Color.rgb(113, 230, 176));
        mark.setLetterSpacing(0.18f);
        content.addView(mark, matchWrap(0));

        TextView title = label("Pegasus Lucent", 38, Color.WHITE);
        title.setTypeface(Typeface.create("sans", Typeface.BOLD));
        content.addView(title, matchWrap(dp(8)));

        TextView intro = label("Your library automation, media previews, and Lucent theme are managed here.",
                17, Color.rgb(183, 193, 207));
        intro.setLineSpacing(0, 1.18f);
        content.addView(intro, matchWrap(dp(8)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(18));
        card.setBackground(cardBackground());
        LinearLayout.LayoutParams cardParams = matchWrap(dp(28));
        cardParams.bottomMargin = dp(16);
        content.addView(card, cardParams);

        TextView ready = label("SYSTEM STATUS", 12, Color.rgb(113, 230, 176));
        ready.setLetterSpacing(0.15f);
        card.addView(ready, matchWrap(0));
        serviceStatus = label("Starting Lucent service…", 18, Color.WHITE);
        serviceStatus.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(serviceStatus, matchWrap(dp(12)));
        themeStatus = label("Checking theme installation…", 15, Color.rgb(182, 192, 205));
        card.addView(themeStatus, matchWrap(dp(8)));
        permissionStatus = label("Checking library access…", 15, Color.rgb(182, 192, 205));
        card.addView(permissionStatus, matchWrap(dp(6)));

        content.addView(action("OPEN PEGASUS", true, view -> openPegasus()), buttonParams(0));
        content.addView(action("INSTALL / REPAIR LUCENT THEME", false,
                view -> repairTheme()), buttonParams(dp(10)));
        content.addView(action("SCAN DOWNLOADS & GAME LIBRARY", false,
                view -> callService("/import/scan", "Library scan started")), buttonParams(dp(10)));
        content.addView(action("CHECK FOR UPDATES", false,
                view -> callService("/update/check", "Checking GitHub for updates")), buttonParams(dp(10)));
        content.addView(action("GRANT LIBRARY ACCESS", false,
                view -> requestStorageIfNeeded(true)), buttonParams(dp(10)));

        activityStatus = label("Lucent checks for new games and updates whenever its service starts.",
                14, Color.rgb(146, 158, 174));
        activityStatus.setGravity(Gravity.CENTER);
        activityStatus.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams activityParams = matchWrap(dp(20));
        activityParams.bottomMargin = dp(16);
        content.addView(activityStatus, activityParams);

        TextView foot = label("The lower-screen player opens automatically only when Pegasus sends a preview. " +
                "Opening Lucent itself will always return to this dashboard.",
                13, Color.rgb(105, 118, 135));
        foot.setGravity(Gravity.CENTER);
        foot.setLineSpacing(0, 1.15f);
        content.addView(foot, matchWrap(0));
    }

    private void startLucentService() {
        Intent service = new Intent(this, PreviewService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
    }

    private void repairTheme() {
        activityStatus.setText("Installing the bundled Lucent theme…");
        ThemeInstaller.forceInstallBundled(this, () -> runOnUiThread(() -> {
            activityStatus.setText("Lucent theme installed. Open Pegasus to use it.");
            refreshStatus();
        }));
    }

    private void openPegasus() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(PEGASUS_PACKAGE);
        if (launch == null) {
            activityStatus.setText("Pegasus Frontend is not installed. Install Pegasus first, then return here.");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://pegasus-frontend.org/")));
            } catch (RuntimeException ignored) {}
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(launch);
    }

    private void requestStorageIfNeeded(boolean userInitiated) {
        if (hasLibraryAccess()) {
            if (permissionStatus != null) permissionStatus.setText("Library access granted");
            if (userInitiated) activityStatus.setText("Library access is already granted.");
            return;
        }
        if (!userInitiated) {
            permissionStatus.setText("Library access needs approval");
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (RuntimeException ignored) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
    }

    private boolean hasLibraryAccess() {
        return ThemeInstaller.hasStorageAccess(this);
    }

    private void refreshStatus() {
        serviceStatus.setText("Lucent service active");
        File theme = new File(Environment.getExternalStorageDirectory(),
                "pegasus-frontend/themes/lucent/theme.qml");
        String version = ThemeInstaller.installedVersion();
        themeStatus.setText(theme.isFile()
                ? "Theme installed" + (version.isEmpty() ? "" : "  •  version " + version)
                : "Theme installation pending");
        permissionStatus.setText(hasLibraryAccess()
                ? "Library access granted" : "Library access needs approval");
        readServiceStatus("/update/status");
    }

    private void callService(String path, String immediate) {
        activityStatus.setText(immediate + "…");
        Thread thread = new Thread(() -> {
            String response = http(path);
            runOnUiThread(() -> activityStatus.setText(message(response, immediate)));
        }, "lucent-dashboard-action");
        thread.setDaemon(true);
        thread.start();
    }

    private void readServiceStatus(String path) {
        Thread thread = new Thread(() -> {
            String response = http(path);
            if (response.isEmpty()) return;
            String message = message(response, "Lucent is ready");
            try {
                JSONObject value = new JSONObject(response);
                if (!value.optBoolean("running", false) &&
                        !"available".equals(value.optString("state"))) return;
            } catch (Exception ignored) { return; }
            runOnUiThread(() -> activityStatus.setText(message));
        }, "lucent-dashboard-status");
        thread.setDaemon(true);
        thread.start();
    }

    private String http(String path) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + PreviewService.PORT + path).openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(2500);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) out.append(line);
                return out.toString();
            }
        } catch (Exception ignored) {
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String message(String json, String fallback) {
        if (json == null || json.isEmpty()) return fallback;
        try {
            String value = new JSONObject(json).optString("message", fallback);
            return value.isEmpty() ? fallback : value;
        } catch (Exception ignored) { return fallback; }
    }

    private TextView action(String text, boolean primary, View.OnClickListener listener) {
        TextView button = label(text, 14, primary ? Color.rgb(4, 17, 12) : Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setLetterSpacing(0.10f);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(buttonBackground(primary));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private GradientDrawable background(int[] colors, float radius) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        shape.setCornerRadius(dp((int) radius));
        return shape;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable card = background(new int[]{Color.rgb(20, 28, 39),
                Color.rgb(13, 19, 28)}, 18);
        card.setStroke(dp(1), Color.rgb(48, 61, 76));
        return card;
    }

    private GradientDrawable buttonBackground(boolean primary) {
        GradientDrawable button = new GradientDrawable();
        button.setCornerRadius(dp(14));
        if (primary) button.setColor(Color.rgb(113, 230, 176));
        else {
            button.setColor(Color.rgb(22, 30, 41));
            button.setStroke(dp(1), Color.rgb(55, 69, 86));
        }
        return button;
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        return params;
    }

    private LinearLayout.LayoutParams buttonParams(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        params.topMargin = top;
        return params;
    }

    private FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
