package com.thorium.preview;

import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounds-only audit of every Pegasus box-front reference. */
final class ArtworkAudit {
    private ArtworkAudit() {}

    private static final class Record {
        String system, title, path;
        int width, height;
        double ratio;
    }

    static JSONObject run(File root) throws Exception {
        File[] metadata = root.listFiles((directory, name) -> name.endsWith(".metadata.pegasus.txt"));
        if (metadata == null) throw new IllegalArgumentException("unreadable metadata directory");
        List<Record> records = new ArrayList<>();
        Map<String, Integer> games = new LinkedHashMap<>();
        Map<String, Integer> noReference = new LinkedHashMap<>();
        Map<String, Integer> missingFile = new LinkedHashMap<>();
        Map<String, Integer> unreadable = new LinkedHashMap<>();
        for (File file : metadata) parse(file, records, games, noReference, missingFile, unreadable);

        Map<String, List<Double>> ratios = new LinkedHashMap<>();
        for (Record record : records)
            ratios.computeIfAbsent(record.system, unused -> new ArrayList<>()).add(record.ratio);

        JSONObject result = new JSONObject();
        JSONObject systems = new JSONObject();
        JSONArray outliers = new JSONArray();
        int totalGames = 0, totalNoReference = 0, totalMissingFile = 0;
        int totalUnreadable = 0, totalOutliers = 0;
        for (Map.Entry<String, Integer> entry : games.entrySet()) {
            String system = entry.getKey();
            List<Double> sorted = new ArrayList<>(ratios.getOrDefault(system, Collections.emptyList()));
            Collections.sort(sorted);
            double median = sorted.isEmpty() ? 0.0 : sorted.get(sorted.size() / 2);
            int systemOutliers = 0;
            if (median > 0) {
                for (Record record : records) {
                    if (!system.equals(record.system) || Math.abs(record.ratio - median) / median <= 0.20)
                        continue;
                    systemOutliers++;
                    JSONObject item = new JSONObject();
                    item.put("system", system); item.put("title", record.title);
                    item.put("path", record.path); item.put("width", record.width);
                    item.put("height", record.height); item.put("ratio", round(record.ratio));
                    item.put("median", round(median)); outliers.put(item);
                }
            }
            int absent = noReference.getOrDefault(system, 0);
            int missing = missingFile.getOrDefault(system, 0);
            int broken = unreadable.getOrDefault(system, 0);
            JSONObject summary = new JSONObject();
            summary.put("games", entry.getValue()); summary.put("readable", sorted.size());
            summary.put("noReference", absent); summary.put("missingFile", missing);
            summary.put("unreadable", broken); summary.put("medianRatio", round(median));
            summary.put("outliers20", systemOutliers); systems.put(system, summary);
            totalGames += entry.getValue(); totalNoReference += absent; totalMissingFile += missing;
            totalUnreadable += broken; totalOutliers += systemOutliers;
        }
        result.put("games", totalGames); result.put("readable", records.size());
        result.put("noReference", totalNoReference); result.put("missingFile", totalMissingFile);
        result.put("unreadable", totalUnreadable); result.put("outliers20", totalOutliers);
        result.put("systems", systems); result.put("outliers", outliers);
        return result;
    }

    private static void parse(File metadata, List<Record> records, Map<String, Integer> games,
            Map<String, Integer> noReference, Map<String, Integer> missingFile,
            Map<String, Integer> unreadable) throws Exception {
        String system = metadata.getName();
        int dash = system.indexOf('-'), dot = system.indexOf('.', dash + 1);
        if (dash >= 0 && dot > dash) system = system.substring(dash + 1, dot);
        String title = null, art = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(metadata))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("game: ")) {
                    if (title != null) audit(system, title, art, records, noReference, missingFile, unreadable);
                    title = line.substring(6).trim(); art = null;
                    games.put(system, games.getOrDefault(system, 0) + 1);
                } else if (title != null && line.startsWith("assets.boxFront: ")) {
                    art = line.substring(17).trim();
                }
            }
        }
        if (title != null) audit(system, title, art, records, noReference, missingFile, unreadable);
    }

    private static void audit(String system, String title, String path, List<Record> records,
            Map<String, Integer> noReference, Map<String, Integer> missingFile,
            Map<String, Integer> unreadable) {
        if (path == null || path.isEmpty()) {
            noReference.put(system, noReference.getOrDefault(system, 0) + 1); return;
        }
        if (!new File(path).isFile()) {
            missingFile.put(system, missingFile.getOrDefault(system, 0) + 1); return;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true; BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            unreadable.put(system, unreadable.getOrDefault(system, 0) + 1); return;
        }
        Record record = new Record(); record.system = system; record.title = title; record.path = path;
        record.width = options.outWidth; record.height = options.outHeight;
        record.ratio = options.outWidth / (double) options.outHeight; records.add(record);
    }

    private static double round(double value) { return Math.round(value * 1000.0) / 1000.0; }
}
