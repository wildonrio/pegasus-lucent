package com.thorium.preview;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicBoolean;

/** Starts Pegasus, the Lucent theme, and every companion feature as one app. */
public final class LucentApplication
        extends org.qtproject.qt5.android.bindings.QtApplication {
    private static final String PEGASUS_ACTIVITY =
            "org.pegasus_frontend.android.MainActivity";
    private final AtomicBoolean completingFirstSetup = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeInstaller.installBundledNow(this);
        startLucentService();
        registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                completeFirstSetupIfNeeded(activity);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private void startLucentService() {
        Intent service = new Intent(this, PreviewService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
    }

    private void completeFirstSetupIfNeeded(Activity activity) {
        if (!PEGASUS_ACTIVITY.equals(activity.getClass().getName()) ||
                !ThemeInstaller.hasStorageAccess(this) ||
                !ThemeInstaller.installedVersion().isEmpty() ||
                !completingFirstSetup.compareAndSet(false, true)) return;

        Thread worker = new Thread(() -> {
            ThemeInstaller.installBundledNow(this);
            startLucentService();
            activity.runOnUiThread(() -> {
                Intent restart = new Intent(Intent.ACTION_MAIN)
                        .setClassName(getPackageName(), PEGASUS_ACTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_CLEAR_TASK);
                activity.startActivity(restart);
                activity.finishAffinity();
            });
        }, "lucent-first-setup");
        worker.setDaemon(true);
        worker.start();
    }
}
