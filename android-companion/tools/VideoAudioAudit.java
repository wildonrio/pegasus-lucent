package com.thorium.preview.tools;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** Audits Android-readable media files listed one absolute path per line. */
public final class VideoAudioAudit {
    private VideoAudioAudit() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            System.err.println("usage: VideoAudioAudit PATH_LIST");
            System.exit(2);
        }

        int total = 0;
        int withAudio = 0;
        int withoutAudio = 0;
        int errors = 0;
        long bytes = 0L;

        System.out.println("PATH\tBYTES\tWIDTH\tHEIGHT\tBITRATE\tFPS\tDURATION_MS\tAUDIO\tSTATUS");

        try (BufferedReader reader = new BufferedReader(new FileReader(arguments[0]))) {
            String path;
            while ((path = reader.readLine()) != null) {
                path = path.trim();
                if (path.isEmpty()) continue;
                total++;
                File file = new File(path);
                bytes += file.length();
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(path);
                    boolean audio = false;
                    int width = 0;
                    int height = 0;
                    int bitrate = 0;
                    int frameRate = 0;
                    long durationUs = 0L;
                    for (int track = 0; track < extractor.getTrackCount(); ++track) {
                        MediaFormat format = extractor.getTrackFormat(track);
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) {
                            audio = true;
                        } else if (mime != null && mime.startsWith("video/")) {
                            if (format.containsKey(MediaFormat.KEY_WIDTH))
                                width = format.getInteger(MediaFormat.KEY_WIDTH);
                            if (format.containsKey(MediaFormat.KEY_HEIGHT))
                                height = format.getInteger(MediaFormat.KEY_HEIGHT);
                            if (format.containsKey(MediaFormat.KEY_BIT_RATE))
                                bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE);
                            if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                                frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                            if (format.containsKey(MediaFormat.KEY_DURATION))
                                durationUs = format.getLong(MediaFormat.KEY_DURATION);
                        }
                    }
                    if (audio) {
                        withAudio++;
                    } else {
                        withoutAudio++;
                    }
                    System.out.println(path + "\t" + file.length() + "\t" + width + "\t"
                            + height + "\t" + bitrate + "\t" + frameRate + "\t"
                            + (durationUs / 1000L) + "\t" + (audio ? "yes" : "no")
                            + "\tok");
                } catch (Exception error) {
                    errors++;
                    System.out.println(path + "\t" + file.length()
                            + "\t0\t0\t0\t0\t0\tunknown\terror:"
                            + error.getClass().getSimpleName() + ":" + clean(error.getMessage()));
                } finally {
                    extractor.release();
                }
            }
        }

        System.out.println("SUMMARY\ttotal=" + total
                + "\twithAudio=" + withAudio
                + "\twithoutAudio=" + withoutAudio
                + "\terrors=" + errors
                + "\tbytes=" + bytes);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ');
    }
}
