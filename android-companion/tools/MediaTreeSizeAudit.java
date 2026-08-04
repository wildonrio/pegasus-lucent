package com.thorium.preview.tools;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Locale;

/** Categorizes all files below a media root by extension. */
public final class MediaTreeSizeAudit {
    private MediaTreeSizeAudit() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("usage: MediaTreeSizeAudit ROOT");
            System.exit(2);
        }
        long videoBytes = 0L;
        long graphicBytes = 0L;
        long otherBytes = 0L;
        int videoCount = 0;
        int graphicCount = 0;
        int otherCount = 0;
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(new File(arguments[0]));
        while (!pending.isEmpty()) {
            File entry = pending.removeFirst();
            if (entry.isDirectory()) {
                File[] children = entry.listFiles();
                if (children != null) {
                    for (File child : children) pending.addLast(child);
                }
                continue;
            }
            if (!entry.isFile()) continue;
            long bytes = entry.length();
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (hasExtension(name, ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v")) {
                videoCount++;
                videoBytes += bytes;
            } else if (hasExtension(name, ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif")) {
                graphicCount++;
                graphicBytes += bytes;
            } else {
                otherCount++;
                otherBytes += bytes;
            }
        }
        System.out.println("SUMMARY\tvideoCount=" + videoCount
                + "\tvideoBytes=" + videoBytes
                + "\tgraphicCount=" + graphicCount
                + "\tgraphicBytes=" + graphicBytes
                + "\totherCount=" + otherCount
                + "\totherBytes=" + otherBytes);
    }

    private static boolean hasExtension(String name, String... extensions) {
        for (String extension : extensions) {
            if (name.endsWith(extension)) return true;
        }
        return false;
    }
}
