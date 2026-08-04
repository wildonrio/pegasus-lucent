package com.thorium.preview.tools;

import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Audits every assets.boxFront path in a Pegasus metadata directory. */
public final class ImageArtworkAudit {
    private ImageArtworkAudit() {}

    private static final class Record {
        String system;
        String title;
        String path;
        int width;
        int height;
        double ratio;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            System.err.println("usage: ImageArtworkAudit PEGASUS_METADATA_DIRECTORY");
            System.exit(2);
        }
        File root = new File(arguments[0]);
        File[] metadata = root.listFiles((directory, name) ->
                name.endsWith(".metadata.pegasus.txt"));
        if (metadata == null) throw new IllegalArgumentException("unreadable directory: " + root);

        List<Record> records = new ArrayList<>();
        Map<String, Integer> games = new LinkedHashMap<>();
        Map<String, Integer> missingReference = new LinkedHashMap<>();
        Map<String, Integer> missingFile = new LinkedHashMap<>();
        Map<String, Integer> unreadable = new LinkedHashMap<>();
        for (File file : metadata) parse(file, records, games, missingReference, missingFile, unreadable);

        Map<String, List<Double>> ratios = new LinkedHashMap<>();
        for (Record record : records)
            ratios.computeIfAbsent(record.system, unused -> new ArrayList<>()).add(record.ratio);

        int totalGames = 0;
        int totalMissingReference = 0;
        int totalMissingFile = 0;
        int totalUnreadable = 0;
        int totalOutliers = 0;
        System.out.println("SYSTEM\tGAMES\tREADABLE\tNO_REFERENCE\tMISSING_FILE\tUNREADABLE\tMEDIAN_RATIO\tOUTLIERS_20_PERCENT");
        for (Map.Entry<String, Integer> entry : games.entrySet()) {
            String system = entry.getKey();
            List<Double> values = ratios.getOrDefault(system, Collections.emptyList());
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            double median = sorted.isEmpty() ? 0.0 : sorted.get(sorted.size() / 2);
            int outliers = 0;
            if (median > 0) {
                for (double ratio : values)
                    if (Math.abs(ratio - median) / median > 0.20) outliers++;
            }
            int noReference = missingReference.getOrDefault(system, 0);
            int missing = missingFile.getOrDefault(system, 0);
            int broken = unreadable.getOrDefault(system, 0);
            totalGames += entry.getValue();
            totalMissingReference += noReference;
            totalMissingFile += missing;
            totalUnreadable += broken;
            totalOutliers += outliers;
            System.out.println(system + "\t" + entry.getValue() + "\t" + values.size()
                    + "\t" + noReference + "\t" + missing + "\t" + broken + "\t"
                    + String.format(Locale.US, "%.3f", median) + "\t" + outliers);
        }
        System.out.println("SUMMARY\tgames=" + totalGames + "\treadable=" + records.size()
                + "\tnoReference=" + totalMissingReference + "\tmissingFile=" + totalMissingFile
                + "\tunreadable=" + totalUnreadable + "\toutliers20=" + totalOutliers);

        if (totalOutliers > 0) {
            System.out.println("OUTLIERS\tSYSTEM\tTITLE\tWIDTH\tHEIGHT\tRATIO\tPATH");
            for (Record record : records) {
                List<Double> sorted = new ArrayList<>(ratios.get(record.system));
                Collections.sort(sorted);
                double median = sorted.get(sorted.size() / 2);
                if (Math.abs(record.ratio - median) / median > 0.20)
                    System.out.println("OUTLIER\t" + record.system + "\t" + record.title + "\t"
                            + record.width + "\t" + record.height + "\t"
                            + String.format(Locale.US, "%.3f", record.ratio) + "\t" + record.path);
            }
        }
    }

    private static void parse(File metadata, List<Record> records,
            Map<String, Integer> games, Map<String, Integer> missingReference,
            Map<String, Integer> missingFile, Map<String, Integer> unreadable) throws Exception {
        String system = metadata.getName();
        int dash = system.indexOf('-');
        int dot = system.indexOf('.', dash + 1);
        if (dash >= 0 && dot > dash) system = system.substring(dash + 1, dot);
        String title = null;
        String art = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(metadata))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("game: ")) {
                    if (title != null) audit(system, title, art, records, missingReference, missingFile, unreadable);
                    title = line.substring(6).trim();
                    art = null;
                    games.put(system, games.getOrDefault(system, 0) + 1);
                } else if (title != null && line.startsWith("assets.boxFront: ")) {
                    art = line.substring(17).trim();
                }
            }
        }
        if (title != null) audit(system, title, art, records, missingReference, missingFile, unreadable);
    }

    private static void audit(String system, String title, String path, List<Record> records,
            Map<String, Integer> missingReference, Map<String, Integer> missingFile,
            Map<String, Integer> unreadable) {
        if (path == null || path.isEmpty()) {
            missingReference.put(system, missingReference.getOrDefault(system, 0) + 1);
            return;
        }
        File file = new File(path);
        if (!file.isFile()) {
            missingFile.put(system, missingFile.getOrDefault(system, 0) + 1);
            return;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            unreadable.put(system, unreadable.getOrDefault(system, 0) + 1);
            return;
        }
        Record record = new Record();
        record.system = system;
        record.title = title;
        record.path = path;
        record.width = options.outWidth;
        record.height = options.outHeight;
        record.ratio = options.outWidth / (double) options.outHeight;
        records.add(record);
    }
}
