package com.thorium.preview;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs the bundled or downloaded Pegasus Lucent theme without touching ROMs. */
final class ThemeInstaller {
    private static final String TAG = "LucentThemeInstaller";
    private static final String ASSET_ZIP = "pegasus-lucent-theme.zip";
    private static final String ASSET_VERSION = "pegasus-lucent-version.txt";
    private static final File PEGASUS = new File(Environment.getExternalStorageDirectory(),
            "pegasus-frontend");
    private static final File THEME = new File(PEGASUS, "themes/lucent");
    private static final File VERSION = new File(THEME, ".lucent-version");

    private ThemeInstaller() {}

    static void installBundledIfNeeded(Context context) {
        Thread worker = new Thread(() -> {
            try {
                String bundled = readAssetText(context, ASSET_VERSION).trim();
                if (bundled.isEmpty() || bundled.equals(readText(VERSION).trim())) return;
                try (InputStream input = context.getAssets().open(ASSET_ZIP)) {
                    installZip(input, bundled);
                }
            } catch (java.io.FileNotFoundException missingOptionalAsset) {
                // Development builds may intentionally omit the large theme bundle.
            } catch (Exception error) {
                Log.e(TAG, "Unable to install bundled theme", error);
            }
        }, "lucent-theme-install");
        worker.setDaemon(true);
        worker.start();
    }

    static synchronized void installZip(File zip, String version) throws Exception {
        try (InputStream input = new FileInputStream(zip)) {
            installZip(input, version);
        }
    }

    static synchronized void installZip(InputStream source, String version) throws Exception {
        PEGASUS.mkdirs();
        File themes = new File(PEGASUS, "themes");
        themes.mkdirs();
        File staging = new File(themes, ".lucent-installing");
        deleteTree(staging);
        staging.mkdirs();
        String stagingPath = staging.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            byte[] buffer = new byte[128 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                String relative = entry.getName();
                if (relative.startsWith("lucent/")) relative = relative.substring(7);
                if (relative.isEmpty()) continue;
                File output = new File(staging, relative);
                String canonical = output.getCanonicalPath();
                if (!canonical.startsWith(stagingPath))
                    throw new java.io.IOException("Unsafe theme archive path");
                if (entry.isDirectory()) {
                    output.mkdirs();
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null) parent.mkdirs();
                try (BufferedOutputStream out = new BufferedOutputStream(
                        new FileOutputStream(output))) {
                    int count;
                    long total = 0;
                    while ((count = zip.read(buffer)) >= 0) {
                        total += count;
                        if (total > 512L * 1024L * 1024L)
                            throw new java.io.IOException("Theme entry is unexpectedly large");
                        out.write(buffer, 0, count);
                    }
                }
            }
        }
        if (!new File(staging, "theme.qml").isFile() ||
                !new File(staging, "theme.cfg").isFile()) {
            deleteTree(staging);
            throw new java.io.IOException("Theme archive is incomplete");
        }
        deleteTree(THEME);
        if (!staging.renameTo(THEME)) {
            copyTree(staging, THEME);
            deleteTree(staging);
        }
        writeText(VERSION, version == null ? "unknown" : version.trim());
        selectTheme();
    }

    static String installedVersion() {
        return readText(VERSION).trim();
    }

    private static void selectTheme() throws Exception {
        File settings = new File(PEGASUS, "settings.txt");
        List<String> lines = new ArrayList<>();
        boolean replaced = false;
        if (settings.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(settings))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("general.theme:")) {
                        if (!replaced) {
                            lines.add("general.theme: " + THEME.getAbsolutePath() + "/");
                            replaced = true;
                        }
                    } else lines.add(line);
                }
            }
        }
        if (!replaced) lines.add(0, "general.theme: " + THEME.getAbsolutePath() + "/");
        File temporary = new File(settings.getParentFile(), ".settings.lucent.tmp");
        try (FileWriter writer = new FileWriter(temporary, false)) {
            for (String line : lines) writer.write(line + "\n");
        }
        if (settings.isFile() && !settings.delete())
            throw new java.io.IOException("Unable to replace Pegasus settings");
        if (!temporary.renameTo(settings))
            throw new java.io.IOException("Unable to commit Pegasus settings");
    }

    private static String readAssetText(Context context, String name) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String readText(File file) {
        if (!file.isFile()) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        } catch (Exception ignored) {}
        return out.toString();
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(text == null ? "" : text);
        }
    }

    private static void copyTree(File source, File target) throws Exception {
        if (source.isDirectory()) {
            target.mkdirs();
            File[] children = source.listFiles();
            if (children != null)
                for (File child : children) copyTree(child, new File(target, child.getName()));
            return;
        }
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
