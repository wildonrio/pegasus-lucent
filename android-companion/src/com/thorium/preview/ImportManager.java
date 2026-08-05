package com.thorium.preview;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Conservative, non-blocking Downloads importer for the THOR library.
 *
 * A file is moved only after its payload validates as a supported ROM. Ambiguous
 * disc/archive formats are deliberately left in Downloads. Metadata and the
 * registry are written atomically so a killed service cannot corrupt the library.
 */
final class ImportManager {
    private static final String TAG = "ThorImporter";
    private static final String USER_AGENT = "THOR-Pegasus-Importer/1.0";
    private static final File DOWNLOADS = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS);
    private static final File GAMES = new File(Environment.getExternalStorageDirectory(), "Games");
    private static final File PEGASUS = new File(Environment.getExternalStorageDirectory(),
            "pegasus-frontend");
    private static final File REGISTRY = new File(PEGASUS, "thorium-imports.json");
    private static final File AUTO_METADATA = new File(PEGASUS,
            "99-thorium-auto-import.metadata.pegasus.txt");
    private static final long RESCAN_THROTTLE_MS = 45_000L;
    private static final long MISS_RETRY_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+\\.(?:png|jpg|jpeg))\"",
            Pattern.CASE_INSENSITIVE);
    private static final byte[] GB_LOGO = new byte[]{
            (byte)0xCE,(byte)0xED,0x66,0x66,(byte)0xCC,0x0D,0x00,0x0B,0x03,0x73,0x00,(byte)0x83,
            0x00,0x0C,0x00,0x0D,0x00,0x08,0x11,0x1F,(byte)0x88,(byte)0x89,0x00,0x0E,(byte)0xDC,
            (byte)0xCC,0x6E,(byte)0xE6,(byte)0xDD,(byte)0xDD,(byte)0xD9,(byte)0x99,(byte)0xBB,
            (byte)0xBB,0x67,0x63,0x6E,0x0E,(byte)0xEC,(byte)0xCC,(byte)0xDD,(byte)0xDC,(byte)0x99,
            (byte)0x9F,(byte)0xBB,(byte)0xB9,0x33,0x3E
    };

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object statusLock = new Object();
    private volatile long lastScanStarted;
    private volatile String lastDownloadFingerprint = "";
    private JSONObject status = idleStatus();
    private volatile Map<String, GameRankingsRecord> gamerankingsIndex;
    private volatile Map<String, GameRankingsRecord> gamerankingsAliasIndex;
    private volatile Map<String, MobyGamesRecord> mobygamesIndex;
    private volatile Map<String, MobyGamesRecord> mobygamesAliasIndex;

    ImportManager(Context context) {
        this.context = context.getApplicationContext();
    }

    void startScan() {
        startScan(false);
    }

    void startInitialScan() {
        if (!ThemeInstaller.hasStorageAccess(context)) {
            setStatus("permission", 0.0,
                    "Grant library access to begin the automatic first scan",
                    Collections.emptyList(), 0, false);
            return;
        }
        boolean alreadyDiscovered = context.getSharedPreferences("lucent-library", 0)
                .getBoolean("fullDiscoveryV2", false);
        startScan(!alreadyDiscovered);
    }

    private void startScan(boolean fullDiscovery) {
        long now = SystemClock.elapsedRealtime();
        String fingerprint = downloadFingerprint();
        if (running.get() || (now - lastScanStarted < RESCAN_THROTTLE_MS &&
                fingerprint.equals(lastDownloadFingerprint))) return;
        if (!running.compareAndSet(false, true)) return;
        lastScanStarted = now;
        lastDownloadFingerprint = fingerprint;
        setStatus("scanning", 0.01, "Scanning internal and SD Downloads for verified games…",
                Collections.emptyList(), 0, false);
        Thread worker = new Thread(() -> {
            try {
                runScan(fullDiscovery);
                if (fullDiscovery)
                    context.getSharedPreferences("lucent-library", 0).edit()
                            .putBoolean("fullDiscoveryV2", true).apply();
            } catch (Throwable error) {
                Log.e(TAG, "Import failed", error);
                setStatus("error", 1.0, "Import stopped safely: " + shortError(error),
                        Collections.emptyList(), 0, false);
            } finally {
                running.set(false);
            }
        }, "thor-library-import");
        worker.setDaemon(true);
        worker.start();
    }

    String statusJson() {
        synchronized (statusLock) {
            return status.toString();
        }
    }

    String archiveJson() {
        JSONObject response = new JSONObject();
        JSONArray archived = new JSONArray();
        JSONArray registry = readRegistry();
        try {
            for (int i = 0; i < registry.length(); i++) {
                JSONObject row = registry.optJSONObject(i);
                if (row == null || !row.optBoolean("archived", false) ||
                        row.optBoolean("forceInclude", false)) continue;
                JSONObject item = new JSONObject();
                item.put("id", row.optString("sourceIdentity"));
                item.put("title", row.optString("title"));
                GameSystems.SystemDef system = GameSystems.byFolder(row.optString("system"));
                item.put("system", system == null ? row.optString("system") : system.collection);
                item.put("reason", "Missing exact box art");
                archived.put(item);
            }
            response.put("count", archived.length());
            response.put("games", archived);
        } catch (Exception ignored) {}
        return response.toString();
    }

    synchronized boolean includeArchived(String identity) {
        if (identity == null || identity.isEmpty()) return false;
        JSONArray registry = readRegistry();
        boolean changed = false;
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row == null || !identity.equals(row.optString("sourceIdentity"))) continue;
            try {
                row.put("forceInclude", true);
                changed = true;
            } catch (Exception ignored) {}
            break;
        }
        if (!changed) return false;
        try {
            writeJsonAtomic(REGISTRY, registry);
            writeMetadata(registry);
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Unable to include archived game", error);
            return false;
        }
    }

    private void runScan(boolean fullDiscovery) throws Exception {
        PEGASUS.mkdirs();
        GAMES.mkdirs();
        File mediaRoot = mediaRoot();
        File cacheRoot = new File(PEGASUS, ".thorium-import-cache");
        cacheRoot.mkdirs();

        setStatus("scanning", 0.03, "Scanning internal and SD Downloads for verified games…",
                Collections.emptyList(), 0, false);
        List<Candidate> candidates = discoverCandidates(cacheRoot);
        if (fullDiscovery) {
            setStatus("discovering", 0.06,
                    "Finding existing game libraries on internal and removable storage…",
                    Collections.emptyList(), 0, false);
            candidates.addAll(discoverExistingCandidates());
        }
        List<String> titles = new ArrayList<>();
        for (Candidate candidate : candidates) titles.add(candidate.title);

        if (!candidates.isEmpty()) {
            setStatus("identified", 0.12,
                    candidates.size() == 1 ? "1 new game identified" :
                            candidates.size() + " new games identified",
                    titles, 0, false);
        }

        JSONArray registry = readRegistry();
        Set<String> registeredSources = registeredSources(registry);
        List<ImportedGame> imported = new ArrayList<>();
        Map<File, Integer> archiveTotals = new HashMap<>();
        Map<File, Integer> archiveSuccesses = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.zipEntry != null)
                archiveTotals.put(candidate.source, archiveTotals.getOrDefault(candidate.source, 0) + 1);
        }
        int index = 0;
        for (Candidate candidate : candidates) {
            index++;
            double base = 0.12 + (0.38 * (index - 1) / Math.max(1, candidates.size()));
            setStatus("transferring", base, "Adding " + candidate.title + "…",
                    titles, imported.size(), false);
            if (registeredSources.contains(candidate.identity)) {
                if (candidate.zipEntry != null)
                    archiveSuccesses.put(candidate.source,
                            archiveSuccesses.getOrDefault(candidate.source, 0) + 1);
                continue;
            }
            ImportedGame game = importCandidate(candidate, mediaRoot, cacheRoot);
            if (game != null) {
                imported.add(game);
                registry.put(game.toJson());
                writeJsonAtomic(REGISTRY, registry);
                if (candidate.zipEntry != null)
                    archiveSuccesses.put(candidate.source,
                            archiveSuccesses.getOrDefault(candidate.source, 0) + 1);
            }
        }
        for (Map.Entry<File, Integer> archive : archiveTotals.entrySet()) {
            if (archive.getValue().equals(archiveSuccesses.get(archive.getKey())))
                archive.getKey().delete();
        }

        if (!imported.isEmpty()) {
            setStatus("artwork", 0.55, "Downloading exact box art…", titles,
                    imported.size(), false);
            for (ImportedGame game : imported) enrichBoxArt(game, cacheRoot, mediaRoot);

            setStatus("video", 0.68, "Finding video previews…", titles,
                    imported.size(), false);
            for (ImportedGame game : imported) enrichVideo(game, cacheRoot, mediaRoot);

            setStatus("scores", 0.80,
                    "Matching ratings, releases, developers, and publishers…", titles,
                    imported.size(), false);
            for (ImportedGame game : imported) {
                enrichMetacritic(game, cacheRoot);
                enrichGameRankings(game);
                enrichMobyGames(game);
                calculateCriticComposite(game);
                game.archived = game.boxArt.isEmpty();
            }

            // Replace provisional registry rows with enriched records.
            registry = mergeRegistry(readRegistry(), imported);
            writeJsonAtomic(REGISTRY, registry);
            setStatus("writing", 0.90, "Updating Pegasus metadata…", titles,
                    imported.size(), false);
            writeMetadata(registry);
        }

        setStatus("artwork", 0.93, "Checking the full library for missing artwork…",
                titles, imported.size(), !imported.isEmpty());
        int repaired = repairMissingArtwork(cacheRoot, mediaRoot);

        setStatus("scores", 0.97, "Filling historical GameRankings critic scores…",
                titles, imported.size(), !imported.isEmpty() || repaired > 0);
        int scoreRepairs = repairMissingScores();

        String message;
        boolean reload = !imported.isEmpty() || repaired > 0 || scoreRepairs > 0;
        if (!imported.isEmpty()) {
            message = imported.size() + (imported.size() == 1 ? " game added" : " games added");
            if (repaired > 0) message += " • " + repaired + " covers repaired";
            if (scoreRepairs > 0) message += " • " + scoreRepairs + " historical scores added";
            message += " • reload Pegasus when convenient";
        } else if (repaired > 0 || scoreRepairs > 0) {
            List<String> updates = new ArrayList<>();
            if (repaired > 0) updates.add(repaired + (repaired == 1 ? " cover repaired" : " covers repaired"));
            if (scoreRepairs > 0) updates.add(scoreRepairs + " historical scores added");
            message = join(updates, " • ") + " • reload Pegasus when convenient";
        } else {
            message = "Library scan complete • no new games";
        }
        setStatus("complete", 1.0, message, titles, imported.size(), reload);
    }

    private List<Candidate> discoverCandidates(File cacheRoot) throws Exception {
        List<Candidate> found = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File downloadRoot : downloadRoots()) {
            Log.i(TAG, "Scanning download root " + downloadRoot.getAbsolutePath());
            File[] entries = downloadRoot.listFiles();
            if (entries != null) Collections.addAll(files, entries);
        }
        files.sort(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (!file.isFile() || file.getName().startsWith(".") || isPartial(file.getName())) continue;
            String extension = extension(file.getName());
            if ("zip".equals(extension)) {
                found.addAll(inspectZip(file, cacheRoot));
                continue;
            }
            GameSystems.SystemDef system = identify(file, extension);
            if (system == null) continue;
            String title = cleanTitle(stem(file.getName()));
            found.add(new Candidate(file, null, system, title,
                    canonical(file.getAbsolutePath()) + ":" + file.length()));
        }
        return found;
    }

    private List<Candidate> discoverExistingCandidates() {
        List<Candidate> found = new ArrayList<>();
        Set<String> referenced = existingMetadataPaths();
        Set<String> visited = new HashSet<>();
        List<File> roots = libraryRoots();
        int[] inspected = new int[]{0};
        for (File root : roots)
            scanLibraryRoot(root, 0, found, referenced, visited, inspected);
        return found;
    }

    private void scanLibraryRoot(File directory, int depth, List<Candidate> found,
                                 Set<String> referenced, Set<String> visited, int[] inspected) {
        if (directory == null || depth > 12 || inspected[0] > 250000) return;
        String canonical = canonical(directory.getAbsolutePath());
        if (!visited.add(canonical) || shouldSkipLibraryDirectory(directory)) return;
        File[] entries = directory.listFiles();
        if (entries == null) return;
        Arrays.sort(entries, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File entry : entries) {
            if (inspected[0]++ > 250000) return;
            if (entry.isDirectory()) {
                scanLibraryRoot(entry, depth + 1, found, referenced, visited, inspected);
                continue;
            }
            if (!entry.isFile() || entry.getName().startsWith(".") || isPartial(entry.getName()))
                continue;
            String path = canonical(entry.getAbsolutePath());
            if (referenced.contains(path)) continue;
            String ext = extension(entry.getName());
            GameSystems.SystemDef system = GameSystems.byPath(entry);
            if (system == null) system = identify(entry, ext);
            if (system == null) system = GameSystems.unambiguousByExtension(ext);
            if (system == null) continue;
            String title = cleanTitle(stem(entry.getName()));
            found.add(new Candidate(entry, null, system, title,
                    path + ":" + entry.length(), true));
        }
    }

    private static List<File> libraryRoots() {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        List<File> volumes = new ArrayList<>();
        volumes.add(Environment.getExternalStorageDirectory());
        File[] storage = new File("/storage").listFiles();
        if (storage != null) {
            for (File candidate : storage) {
                String name = candidate.getName();
                if (candidate.isDirectory() && !"emulated".equals(name) && !"self".equals(name))
                    volumes.add(candidate);
            }
        }
        for (File volume : volumes) {
            // First-run discovery must not depend on user folder naming. Scan
            // each readable storage volume itself; the recursive scanner only
            // accepts verified ROM formats and skips Android/app/media trees.
            if (volume.isDirectory() && volume.canRead())
                roots.put(canonical(volume.getAbsolutePath()), volume);
            for (String common : new String[]{"Games", "games", "ROMs", "Roms", "roms",
                    "Emulation", "emulation", "RetroArch", "retropie", "recalbox", "batocera"}) {
                File root = new File(volume, common);
                if (root.isDirectory() && root.canRead()) roots.put(canonical(root.getAbsolutePath()), root);
            }
            File[] children = volume.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && child.canRead() && GameSystems.isKnownSystemDirectory(child))
                        roots.put(canonical(child.getAbsolutePath()), child);
                }
            }
        }
        return new ArrayList<>(roots.values());
    }

    private static boolean shouldSkipLibraryDirectory(File directory) {
        String name = directory.getName().toLowerCase(Locale.US);
        return name.startsWith(".") || "android".equals(name) || "download".equals(name) ||
                "downloads".equals(name) || "pegasusmedia".equals(name) ||
                "pegasus-frontend".equals(name) || "dcim".equals(name) ||
                "pictures".equals(name) || "movies".equals(name) || "music".equals(name) ||
                "alarms".equals(name) || "audiobooks".equals(name) ||
                "notifications".equals(name) || "podcasts".equals(name) ||
                "recordings".equals(name) || "ringtones".equals(name) ||
                "pegasusbackups".equals(name) || "pegasusquarantine".equals(name) ||
                "thorbackups".equals(name) || "dolphinforhandheld".equals(name) ||
                "retroarch".equals(name) || "winlator".equals(name) ||
                "citra-emu".equals(name) || "azahar".equals(name);
    }

    private static Set<String> existingMetadataPaths() {
        Set<String> paths = new HashSet<>();
        File[] metadata = PEGASUS.listFiles((dir, name) ->
                name.endsWith(".metadata.pegasus.txt") || name.equals("metadata.pegasus.txt"));
        if (metadata == null) return paths;
        for (File file : metadata) {
            try {
                for (String stanza : splitStanzas(readText(file))) {
                    String path = field(stanza, "file");
                    if (!path.isEmpty()) paths.add(canonical(path));
                }
            } catch (Exception ignored) {}
        }
        return paths;
    }

    private List<Candidate> inspectZip(File archive, File cacheRoot) {
        List<Candidate> found = new ArrayList<>();
        File staging = new File(cacheRoot, "zip-inspect");
        staging.mkdirs();
        try (ZipFile zip = new ZipFile(archive)) {
            zip.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> {
                String ext = extension(entry.getName());
                if (GameSystems.unambiguousByExtension(ext) == null &&
                        !isPotentialAmbiguous(ext)) return;
                File temporary = new File(staging, sha1(archive.getAbsolutePath() + "!" + entry.getName()) +
                        "." + ext);
                try {
                    extract(zip, entry, temporary);
                    GameSystems.SystemDef system = identify(temporary, ext);
                    if (system != null) {
                        String title = cleanTitle(stem(new File(entry.getName()).getName()));
                        found.add(new Candidate(archive, entry.getName(), system, title,
                                canonical(archive.getAbsolutePath()) + "!" + entry.getName() +
                                        ":" + entry.getCrc()));
                    }
                } catch (Exception ignored) {
                    // Malformed and encrypted archives are ignored without touching Downloads.
                } finally {
                    temporary.delete();
                }
            });
        } catch (Exception ignored) {
        }
        return found;
    }

    private ImportedGame importCandidate(Candidate candidate, File mediaRoot, File cacheRoot)
            throws Exception {
        String extension = candidate.zipEntry == null ? extension(candidate.source.getName()) :
                extension(candidate.zipEntry);
        String canonicalTitle = canonicalTitle(candidate.system, candidate.title, cacheRoot);
        if (!canonicalTitle.isEmpty()) candidate.title = canonicalTitle;
        if (candidate.inPlace) return new ImportedGame(candidate, candidate.source);
        File systemFolder = new File(GAMES, candidate.system.folder);
        systemFolder.mkdirs();
        File target = new File(systemFolder, safeFilename(candidate.title) + "." + extension);

        if (target.isFile()) {
            if (candidate.zipEntry == null && sameContent(candidate.source, target)) {
                candidate.source.delete();
                return null;
            }
            // Never overwrite an existing game or silently rename a different payload.
            return null;
        }

        File partial = new File(systemFolder, "." + target.getName() + ".importing");
        if (candidate.zipEntry == null) {
            copy(candidate.source, partial);
        } else {
            try (ZipFile zip = new ZipFile(candidate.source)) {
                ZipEntry entry = zip.getEntry(candidate.zipEntry);
                if (entry == null) return null;
                extract(zip, entry, partial);
            }
        }
        GameSystems.SystemDef verified = identify(partial, extension);
        if (verified == null || !verified.folder.equals(candidate.system.folder)) {
            partial.delete();
            return null;
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            return null;
        }
        if (candidate.zipEntry == null) {
            candidate.source.delete();
        }
        return new ImportedGame(candidate, target);
    }

    private void enrichBoxArt(ImportedGame game, File cacheRoot, File mediaRoot) {
        try {
            CatalogMatch match = boxArtMatch(game.system, game.title, cacheRoot);
            if (match == null) return;
            File folder = new File(mediaRoot, "boxfront/" + game.system.folder);
            folder.mkdirs();
            String ext = extension(match.url);
            File target = new File(folder, sha1(game.rom.getAbsolutePath()) + "." +
                    (ext.isEmpty() ? "png" : ext));
            if (download(match.url, target, 64L * 1024L * 1024L)) game.boxArt = target.getAbsolutePath();
        } catch (Exception error) {
            Log.w(TAG, "Box art unavailable for " + game.title, error);
        }
    }

    private void enrichVideo(ImportedGame game, File cacheRoot, File mediaRoot) {
        try {
            File existing = existingVideo(mediaRoot, game.system, game.title);
            if (existing != null) {
                game.video = existing.getAbsolutePath();
                return;
            }
            if (game.system.videoArchive.isEmpty()) return;
            VideoMatch match = videoMatch(game.system, game.title, cacheRoot);
            if (match == null) return;
            File folder = new File(mediaRoot, "internet-archive/" + game.system.folder + "/videos");
            folder.mkdirs();
            File target = new File(folder, safeFilename(game.title) + ".mp4");
            if (download(match.url, target, 768L * 1024L * 1024L)) game.video = target.getAbsolutePath();
        } catch (Exception error) {
            Log.w(TAG, "Video unavailable for " + game.title, error);
        }
    }

    private static File existingVideo(File mediaRoot, GameSystems.SystemDef system, String title) {
        String key = normalize(title);
        File[] folders = new File[]{
                new File(mediaRoot, "upscaled-1080p/" + system.folder + "/videos"),
                new File(mediaRoot, "screenscraper/" + system.folder + "/videos"),
                new File(mediaRoot, "internet-archive/" + system.folder + "/videos")
        };
        for (File folder : folders) {
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".mp4"));
            if (files == null) continue;
            for (File file : files)
                if (normalize(stem(file.getName())).equals(key) && file.length() > 512) return file;
        }
        return null;
    }

    private void enrichMetacritic(ImportedGame game, File cacheRoot) {
        if (game.system.metacriticPlatform.isEmpty()) return;
        try {
            String encoded = URLEncoder.encode(game.title, "UTF-8").replace("+", "%20");
            String query = "?offset=0&limit=30&mcoTypeId=13&componentName=search" +
                    "&componentDisplayName=Search&componentType=SearchResults";
            JSONObject root = new JSONObject(new String(fetchBytes(
                    "https://backend.metacritic.com/finder/metacritic/search/" + encoded +
                            "/web" + query, 12L * 1024L * 1024L), StandardCharsets.UTF_8));
            JSONArray items = root.optJSONObject("data") == null ? null :
                    root.optJSONObject("data").optJSONArray("items");
            if (items == null) return;
            String expected = normalize(game.title);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !normalize(item.optString("title")).equals(expected)) continue;
                if (!hasPlatform(item.optJSONArray("platforms"), game.system.metacriticPlatform)) continue;
                JSONObject critic = item.optJSONObject("criticScoreSummary");
                JSONObject user = item.optJSONObject("userScore");
                if (critic != null && critic.optDouble("score", 0) > 0) {
                    game.metacriticCritic = critic.optInt("score");
                    game.metacriticReviews = critic.optInt("reviewCount", 0);
                }
                if (user != null && user.optDouble("score", 0) > 0)
                    game.user = user.optDouble("score");
                game.release = item.optString("releaseDate", "");
                game.metacriticSlug = item.optString("slug", "");
                enrichMetacriticDetails(game);
                return;
            }
        } catch (Exception error) {
            Log.w(TAG, "Metacritic unavailable for " + game.title, error);
        }
    }

    private void enrichMetacriticDetails(ImportedGame game) {
        if (game.metacriticSlug.isEmpty()) return;
        try {
            String slug = encodePath(game.metacriticSlug);
            String productQuery = "?componentName=product&componentDisplayName=Product" +
                    "&componentType=Product";
            JSONObject productRoot = new JSONObject(new String(fetchBytes(
                    "https://backend.metacritic.com/games/metacritic/" + slug +
                            "/web" + productQuery, 12L * 1024L * 1024L), StandardCharsets.UTF_8));
            JSONObject product = productRoot.optJSONObject("data") == null ? null :
                    productRoot.optJSONObject("data").optJSONObject("item");
            if (product != null) {
                JSONObject production = product.optJSONObject("production");
                JSONArray companies = production == null ? null : production.optJSONArray("companies");
                if (companies != null) {
                    for (int i = 0; i < companies.length(); i++) {
                        JSONObject company = companies.optJSONObject(i);
                        if (company == null || company.optString("name").trim().isEmpty()) continue;
                        if ("Developer".equalsIgnoreCase(company.optString("typeName")))
                            addUnique(game.developers, company.optString("name"));
                        else if ("Publisher".equalsIgnoreCase(company.optString("typeName")))
                            addUnique(game.publishers, company.optString("name"));
                    }
                }
                JSONArray platforms = product.optJSONArray("platforms");
                if (platforms != null) {
                    for (int i = 0; i < platforms.length(); i++) {
                        JSONObject platform = platforms.optJSONObject(i);
                        if (platform == null || !platform.optString("name").equalsIgnoreCase(
                                game.system.metacriticPlatform)) continue;
                        JSONObject critic = platform.optJSONObject("criticScoreSummary");
                        if (critic != null && critic.optDouble("score", 0) > 0) {
                            game.metacriticCritic = critic.optInt("score");
                            game.metacriticReviews = critic.optInt("reviewCount", 0);
                        }
                        String release = platform.optString("releaseDate", "");
                        if (!release.isEmpty()) game.release = release;
                        break;
                    }
                }
            }
            String userQuery = "?platform=" + encodePath(game.system.metacriticSlug) +
                    "&componentName=user-score-summary&componentDisplayName=User%20Score%20Summary" +
                    "&componentType=MetaScoreSummary";
            JSONObject userRoot = new JSONObject(new String(fetchBytes(
                    "https://backend.metacritic.com/reviews/metacritic/user/games/" + slug +
                            "/stats/web" + userQuery, 8L * 1024L * 1024L), StandardCharsets.UTF_8));
            JSONObject user = userRoot.optJSONObject("data") == null ? null :
                    userRoot.optJSONObject("data").optJSONObject("item");
            if (user != null && user.optDouble("score", 0) > 0)
                game.user = user.optDouble("score");
        } catch (Exception error) {
            // Search-level data is retained if a detail endpoint is unavailable.
            Log.w(TAG, "Metacritic detail unavailable for " + game.title, error);
        }
    }

    private void enrichGameRankings(ImportedGame game) {
        GameRankingsRecord record = gameRankingsRecord(game.system.folder, game.title);
        if (record == null) return;
        game.gamerankingsScore = record.score;
        game.gamerankingsReviews = record.reviews;
        game.gamerankingsUrl = record.url;
        if (game.release.isEmpty() && !record.year.isEmpty())
            game.release = record.year + "-01-01";
    }

    private void enrichMobyGames(ImportedGame game) {
        MobyGamesRecord record = mobygamesRecord(game.system.folder, game.title);
        if (record == null) return;
        game.mobygamesScore = record.score;
        game.mobygamesUrl = record.url;
        if (game.release.isEmpty() && !record.year.isEmpty())
            game.release = record.year + "-01-01";
        addUnique(game.developers, record.developer);
    }

    private static void calculateCriticComposite(ImportedGame game) {
        double weighted = 0;
        int weight = 0;
        if (game.metacriticCritic > 0) {
            int reviews = Math.max(1, game.metacriticReviews);
            weighted += game.metacriticCritic * reviews;
            weight += reviews;
        }
        if (game.gamerankingsScore > 0) {
            int reviews = Math.max(1, game.gamerankingsReviews);
            weighted += game.gamerankingsScore * reviews;
            weight += reviews;
        }
        if (game.mobygamesScore > 0) {
            // The public MobyGames browser guarantees at least five critic
            // ratings for every packaged record. Use that documented lower
            // bound rather than inventing a review count.
            weighted += game.mobygamesScore * 5;
            weight += 5;
        }
        game.critic = weight == 0 ? 0 : (int)Math.round(weighted / weight);
        List<String> sources = new ArrayList<>();
        if (game.metacriticCritic > 0) sources.add("Metacritic");
        if (game.gamerankingsScore > 0) sources.add("GameRankings");
        if (game.mobygamesScore > 0) sources.add("MobyGames");
        game.scoreSource = join(sources, " + ");
    }

    private int repairMissingScores() {
        File[] files = PEGASUS.listFiles((dir, name) -> name.endsWith(".metadata.pegasus.txt") &&
                !name.equals(AUTO_METADATA.getName()));
        if (files == null) return 0;
        int repaired = 0;
        for (File metadata : files) {
            try {
                List<String> stanzas = splitStanzas(readText(metadata));
                boolean changed = false;
                for (int i = 0; i < stanzas.size(); i++) {
                    String stanza = stanzas.get(i);
                    if (!stanza.startsWith("game:") || !field(stanza, "x-critic").isEmpty() ||
                            !field(stanza, "x-metacritic-critic").isEmpty()) continue;
                    String title = field(stanza, "game");
                    GameSystems.SystemDef system = systemFromRomPath(field(stanza, "file"));
                    if (system == null) continue;
                    GameRankingsRecord record = gameRankingsRecord(system.folder, title);
                    if (record == null) continue;
                    StringBuilder scored = new StringBuilder(removeField(stanza, "rating").trim());
                    scored.append("\nrating: ").append(String.format(Locale.US, "%.4f", record.score / 100.0));
                    scored.append("\nx-critic: ").append(format(record.score / 10.0));
                    scored.append("\nx-critic-composite: ").append(format(record.score / 10.0));
                    scored.append("\nx-gamerankings-score: ").append(formatScore(record.score));
                    scored.append("\nx-gamerankings-reviews: ").append(record.reviews);
                    if (!record.url.isEmpty())
                        scored.append("\nx-gamerankings-url: ").append(metadataSafe(record.url));
                    scored.append("\nx-gamerankings-snapshot: 2019-12-08");
                    scored.append("\nx-score-source: GameRankings");
                    scored.append("\nx-critic-sources: GameRankings=")
                            .append(format(record.score / 10.0));
                    stanzas.set(i, scored.toString());
                    changed = true;
                    repaired++;
                }
                if (changed) writeTextAtomic(metadata, joinStanzas(stanzas));
            } catch (Exception error) {
                Log.w(TAG, "Historical score audit skipped " + metadata, error);
            }
        }
        return repaired;
    }

    private GameRankingsRecord gameRankingsRecord(String folder, String title) {
        Map<String, GameRankingsRecord> index = gameRankingsIndex();
        String normalized = normalize(title);
        GameRankingsRecord exact = index.get(folder + "\t" + normalized);
        if (exact != null) return exact;
        return gamerankingsAliasIndex.get(folder + "\t" + scoreAlias(normalized));
    }

    private synchronized Map<String, GameRankingsRecord> gameRankingsIndex() {
        if (gamerankingsIndex != null) return gamerankingsIndex;
        Map<String, GameRankingsRecord> index = new HashMap<>();
        Map<String, GameRankingsRecord> aliases = new HashMap<>();
        Set<String> ambiguousAliases = new HashSet<>();
        int resourceId = context.getResources().getIdentifier(
                "gamerankings_scores", "raw", context.getPackageName());
        if (resourceId == 0) {
            gamerankingsAliasIndex = aliases;
            gamerankingsIndex = index;
            return index;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(resourceId), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] fields = line.split("\t", -1);
                if (fields.length < 8) continue;
                double score;
                int reviews;
                try {
                    score = Double.parseDouble(fields[3]);
                    reviews = Integer.parseInt(fields[4]);
                } catch (NumberFormatException ignored) { continue; }
                GameRankingsRecord record = new GameRankingsRecord(
                        score, reviews, fields[5], fields[6], fields[7]);
                index.put(fields[0] + "\t" + fields[1], record);

                // The archived catalog and ROM sets often differ only in logo
                // spacing (Mega Man/Megaman), Roman numerals, or a filename
                // truncated near 40 characters. Add only aliases that resolve
                // to exactly one game on the same platform; ambiguous aliases
                // are removed rather than guessed.
                putUniqueAlias(aliases, ambiguousAliases,
                        fields[0] + "\t" + scoreAlias(fields[1]), record);
                if (fields[1].length() > 35) {
                    int firstPrefix = Math.max(35, fields[1].length() - 6);
                    for (int length = firstPrefix; length < fields[1].length(); length++)
                        putUniqueAlias(aliases, ambiguousAliases,
                                fields[0] + "\t" + scoreAlias(fields[1].substring(0, length)), record);
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to load packaged GameRankings index", error);
        }
        for (String key : ambiguousAliases) aliases.remove(key);
        gamerankingsAliasIndex = aliases;
        gamerankingsIndex = index;
        return index;
    }

    private MobyGamesRecord mobygamesRecord(String folder, String title) {
        String normalized = normalize(title);
        MobyGamesRecord exact = mobygamesIndex().get(folder + "\t" + normalized);
        if (exact != null) return exact;
        Set<MobyGamesRecord> candidates = new HashSet<>();
        for (String alias : historicalAliases(normalized)) {
            MobyGamesRecord record = mobygamesAliasIndex.get(folder + "\t" + alias);
            if (record != null) candidates.add(record);
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private synchronized Map<String, MobyGamesRecord> mobygamesIndex() {
        if (mobygamesIndex != null) return mobygamesIndex;
        Map<String, MobyGamesRecord> index = new HashMap<>();
        Map<String, MobyGamesRecord> aliases = new HashMap<>();
        Set<String> ambiguousAliases = new HashSet<>();
        int resourceId = context.getResources().getIdentifier(
                "mobygames_historical", "raw", context.getPackageName());
        if (resourceId == 0) {
            mobygamesAliasIndex = aliases;
            mobygamesIndex = index;
            return index;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(resourceId), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] fields = line.split("\t", -1);
                if (fields.length < 7) continue;
                double score;
                try { score = Double.parseDouble(fields[3]); }
                catch (NumberFormatException ignored) { continue; }
                MobyGamesRecord record = new MobyGamesRecord(
                        score, fields[4], fields[5], fields[6]);
                index.put(fields[0] + "\t" + fields[1], record);
                for (String alias : historicalAliases(fields[1]))
                    putUniqueMobyAlias(aliases, ambiguousAliases,
                            fields[0] + "\t" + alias, record);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to load packaged MobyGames historical index", error);
        }
        for (String key : ambiguousAliases) aliases.remove(key);
        mobygamesAliasIndex = aliases;
        mobygamesIndex = index;
        return index;
    }

    private static void putUniqueMobyAlias(Map<String, MobyGamesRecord> aliases,
            Set<String> ambiguous, String key, MobyGamesRecord record) {
        if (key.endsWith("\t") || ambiguous.contains(key)) return;
        MobyGamesRecord previous = aliases.putIfAbsent(key, record);
        if (previous != null && previous != record) {
            aliases.remove(key);
            ambiguous.add(key);
        }
    }

    private static Set<String> historicalAliases(String normalized) {
        Set<String> expanded = new HashSet<>();
        expanded.add(normalized);
        String[] words = normalized.split(" ");
        if (words.length > 1 && ("the".equals(words[0]) || "a".equals(words[0]) || "an".equals(words[0])))
            expanded.add(joinWords(words, 1, words.length));
        if (words.length > 1 && ("the".equals(words[words.length - 1]) ||
                "a".equals(words[words.length - 1]) || "an".equals(words[words.length - 1]))) {
            expanded.add(joinWords(words, 0, words.length - 1));
            expanded.add(words[words.length - 1] + " " + joinWords(words, 0, words.length - 1));
        }
        for (String value : new ArrayList<>(expanded))
            expanded.add(value.replaceAll("(?:^|\\s)and(?:\\s|$)", " ").trim().replaceAll("\\s+", " "));
        Set<String> result = new HashSet<>();
        for (String value : expanded) {
            result.add(value);
            result.add(scoreAlias(value));
        }
        result.remove("");
        return result;
    }

    private static String joinWords(String[] words, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (result.length() > 0) result.append(' ');
            result.append(words[i]);
        }
        return result.toString();
    }

    private static void putUniqueAlias(Map<String, GameRankingsRecord> aliases,
            Set<String> ambiguous, String key, GameRankingsRecord record) {
        if (key.endsWith("\t") || ambiguous.contains(key)) return;
        GameRankingsRecord previous = aliases.putIfAbsent(key, record);
        if (previous != null && previous != record) {
            aliases.remove(key);
            ambiguous.add(key);
        }
    }

    private static String scoreAlias(String normalized) {
        String[] tokens = normalized.split(" ");
        StringBuilder compact = new StringBuilder();
        for (String token : tokens) {
            switch (token) {
                case "ii": compact.append('2'); break;
                case "iii": compact.append('3'); break;
                case "iv": compact.append('4'); break;
                case "v": compact.append('5'); break;
                case "vi": compact.append('6'); break;
                case "vii": compact.append('7'); break;
                case "viii": compact.append('8'); break;
                case "ix": compact.append('9'); break;
                case "x": compact.append("10"); break;
                default: compact.append(token);
            }
        }
        return compact.toString();
    }

    private int repairMissingArtwork(File cacheRoot, File mediaRoot) {
        File[] files = PEGASUS.listFiles((dir, name) -> name.endsWith(".metadata.pegasus.txt") &&
                !name.equals(AUTO_METADATA.getName()));
        if (files == null) return 0;
        int repaired = 0;
        for (File metadata : files) {
            try {
                List<String> stanzas = splitStanzas(readText(metadata));
                boolean changed = false;
                for (int i = 0; i < stanzas.size(); i++) {
                    String stanza = stanzas.get(i);
                    if (!stanza.startsWith("game:")) continue;
                    String title = field(stanza, "game");
                    String romPath = field(stanza, "file");
                    String artPath = field(stanza, "assets.boxFront");
                    if (!artPath.isEmpty() && new File(artPath).isFile()) continue;
                    GameSystems.SystemDef system = systemFromRomPath(romPath);
                    if (system == null || recentlyMissed(cacheRoot, system, title)) continue;
                    CatalogMatch match = boxArtMatch(system, title, cacheRoot);
                    if (match == null) {
                        recordMiss(cacheRoot, system, title);
                        continue;
                    }
                    File folder = new File(mediaRoot, "boxfront/" + system.folder);
                    folder.mkdirs();
                    String ext = extension(match.url);
                    File target = new File(folder, sha1(romPath) + "." +
                            (ext.isEmpty() ? "png" : ext));
                    if (!download(match.url, target, 64L * 1024L * 1024L)) continue;
                    String cleaned = removeField(stanza, "assets.boxFront");
                    stanzas.set(i, cleaned.trim() + "\nassets.boxFront: " + target.getAbsolutePath());
                    changed = true;
                    repaired++;
                }
                if (changed) writeTextAtomic(metadata, joinStanzas(stanzas));
            } catch (Exception error) {
                Log.w(TAG, "Artwork audit skipped " + metadata, error);
            }
        }
        return repaired;
    }

    private CatalogMatch boxArtMatch(GameSystems.SystemDef system, String title, File cacheRoot)
            throws Exception {
        if (system.libretro.isEmpty()) return null;
        File cache = new File(cacheRoot, "libretro-" + system.folder + ".html");
        String html = cachedText(cache,
                "https://thumbnails.libretro.com/" + encodePath(system.libretro) + "/Named_Boxarts/",
                7L * 24L * 60L * 60L * 1000L, 32L * 1024L * 1024L);
        String key = normalize(title);
        List<String> exact = new ArrayList<>();
        Matcher matcher = HREF.matcher(html);
        while (matcher.find()) {
            String href = matcher.group(1);
            if (normalize(stem(decode(href))).equals(key)) exact.add(href);
        }
        if (exact.isEmpty()) return null;
        exact.sort((left, right) -> Integer.compare(regionRank(left), regionRank(right)));
        String href = exact.get(0);
        String base = "https://thumbnails.libretro.com/" + encodePath(system.libretro) +
                "/Named_Boxarts/";
        return new CatalogMatch(base + encodeHref(href), cleanTitle(stem(decode(href))));
    }

    private String canonicalTitle(GameSystems.SystemDef system, String title, File cacheRoot) {
        try {
            CatalogMatch match = boxArtMatch(system, title, cacheRoot);
            return match == null ? "" : match.title;
        } catch (Exception ignored) {
            return "";
        }
    }

    private VideoMatch videoMatch(GameSystems.SystemDef system, String title, File cacheRoot)
            throws Exception {
        File cache = new File(cacheRoot, "archive-" + system.folder + ".json");
        String json = cachedText(cache, "https://archive.org/metadata/" + system.videoArchive,
                30L * 24L * 60L * 60L * 1000L, 96L * 1024L * 1024L);
        JSONObject root = new JSONObject(json);
        JSONArray files = root.optJSONArray("files");
        if (files == null) return null;
        String key = normalize(title);
        String selected = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            String name = item == null ? "" : item.optString("name", "");
            if (!name.toLowerCase(Locale.US).endsWith(".mp4") ||
                    name.toLowerCase(Locale.US).endsWith(".ia.mp4")) continue;
            if (normalize(stem(name)).equals(key)) {
                if (selected == null || regionRank(name) < regionRank(selected)) selected = name;
            }
        }
        if (selected == null) return null;
        String base;
        if (!root.optString("d1").isEmpty() && !root.optString("dir").isEmpty())
            base = "https://" + root.optString("d1") + root.optString("dir") + "/";
        else base = "https://archive.org/download/" + system.videoArchive + "/";
        return new VideoMatch(base + encodeHref(selected));
    }

    private void writeMetadata(JSONArray registry) throws Exception {
        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row == null || !new File(row.optString("file")).isFile()) continue;
            if (row.optBoolean("archived", false) &&
                    !row.optBoolean("forceInclude", false)) continue;
            String folder = row.optString("system");
            groups.computeIfAbsent(folder, key -> new ArrayList<>()).add(row);
        }
        StringBuilder out = new StringBuilder("# Generated atomically by THOR Library Importer.\n");
        for (Map.Entry<String, List<JSONObject>> group : groups.entrySet()) {
            GameSystems.SystemDef system = GameSystems.byFolder(group.getKey());
            if (system == null) continue;
            out.append("\ncollection: ").append(system.collection).append('\n');
            out.append("shortname: ").append(system.folder).append('\n');
            String launch = launchCommand(system.folder);
            if (!launch.isEmpty()) out.append("launch: ").append(launch).append('\n');
            out.append("files:\n");
            for (JSONObject row : group.getValue())
                out.append("  ").append(metadataSafe(row.optString("file"))).append('\n');
            for (JSONObject row : group.getValue()) {
                out.append("\ngame: ").append(metadataSafe(row.optString("title"))).append('\n');
                double userScore = row.optDouble("user", 0);
                int userKey = userScore > 0 ? Math.max(0, 10000 - (int)Math.round(userScore * 1000)) : 99999;
                out.append("sort-by: ").append(String.format(Locale.US, "%05d", userKey))
                        .append(' ').append(metadataSafe(row.optString("title").toLowerCase(Locale.US)))
                        .append('\n');
                int criticScore = row.optInt("critic", 0);
                if (criticScore > 0)
                    out.append("rating: ").append(String.format(Locale.US, "%.4f", criticScore / 100.0))
                            .append('\n');
                else
                    out.append("rating: 0%\n");
                out.append("file: ").append(metadataSafe(row.optString("file"))).append('\n');
                appendMetadataList(out, "developer", row.optJSONArray("developers"));
                appendMetadataList(out, "publisher", row.optJSONArray("publishers"));
                if (userScore > 0) {
                    out.append("x-user-score: ").append(format(userScore)).append('\n');
                    out.append("x-user-composite: ").append(format(userScore)).append('\n');
                    out.append("x-metacritic-user: ").append(format(userScore)).append('\n');
                    out.append("x-user-sources: Metacritic users=").append(format(userScore)).append('\n');
                }
                if (criticScore > 0) {
                    double normalizedCritic = criticScore / 10.0;
                    out.append("x-critic: ").append(format(normalizedCritic)).append('\n');
                    out.append("x-critic-composite: ").append(format(normalizedCritic)).append('\n');
                    List<String> criticSources = new ArrayList<>();
                    int metacriticCritic = row.optInt("metacriticCritic", 0);
                    if (metacriticCritic > 0) {
                        int reviews = row.optInt("metacriticReviews", 0);
                        out.append("x-metacritic-critic: ").append(metacriticCritic).append('\n');
                        if (reviews > 0)
                            out.append("x-metacritic-reviews: ").append(reviews).append('\n');
                        criticSources.add("Metacritic=" + format(metacriticCritic / 10.0) +
                                (reviews > 0 ? " (n=" + reviews + ")" : ""));
                    }
                    if (row.optDouble("gamerankingsScore", 0) > 0) {
                        out.append("x-gamerankings-score: ")
                                .append(formatScore(row.optDouble("gamerankingsScore"))).append('\n');
                        out.append("x-gamerankings-reviews: ")
                                .append(row.optInt("gamerankingsReviews")).append('\n');
                        if (!row.optString("gamerankingsUrl").isEmpty())
                            out.append("x-gamerankings-url: ")
                                    .append(metadataSafe(row.optString("gamerankingsUrl"))).append('\n');
                        out.append("x-gamerankings-snapshot: 2019-12-08\n");
                        criticSources.add("GameRankings=" +
                                format(row.optDouble("gamerankingsScore") / 10.0) +
                                " (n=" + row.optInt("gamerankingsReviews") + ")");
                    }
                    if (row.optDouble("mobygamesScore", 0) > 0) {
                        out.append("x-mobygames-critic: ")
                                .append(format(row.optDouble("mobygamesScore") / 10.0)).append('\n');
                        out.append("x-mobygames-critic-count-min: 5\n");
                        if (!row.optString("mobygamesUrl").isEmpty())
                            out.append("x-mobygames-url: ")
                                    .append(metadataSafe(row.optString("mobygamesUrl"))).append('\n');
                        criticSources.add("MobyGames=" +
                                format(row.optDouble("mobygamesScore") / 10.0) + " (n=5 min)");
                    }
                    out.append("x-score-source: weighted composite\n");
                    out.append("x-critic-sources: ").append(join(criticSources, " | ")).append('\n');
                }
                if (!row.optString("release").isEmpty())
                    out.append("release: ").append(metadataSafe(row.optString("release"))).append('\n');
                if (!row.optString("metacriticSlug").isEmpty())
                    out.append("x-metacritic-url: https://www.metacritic.com/game/")
                            .append(row.optString("metacriticSlug")).append("/\n");
                if (!row.optString("boxArt").isEmpty())
                    out.append("assets.boxFront: ").append(metadataSafe(row.optString("boxArt"))).append('\n');
                if (!row.optString("video").isEmpty())
                    out.append("assets.video: ").append(metadataSafe(row.optString("video"))).append('\n');
            }
        }
        writeTextAtomic(AUTO_METADATA, out.toString());
    }

    private String launchCommand(String system) {
        if (("gc".equals(system) || "wii".equals(system)) && installed("org.dolphinemu.dolphinemu"))
            return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                    "-n org.dolphinemu.dolphinemu/.ui.main.MainActivity --activity-clear-top";
        if ("ps2".equals(system)) {
            if (installed("com.armsx2"))
                return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                        "-n com.armsx2/.BootSplashActivity --activity-clear-top";
            if (installed("xyz.aethersx2.android"))
                return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                        "-n xyz.aethersx2.android/.MainActivity --activity-clear-top";
        }
        if ("switch".equals(system) && installed("dev.legacy.eden_emulator"))
            return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                    "-n dev.legacy.eden_emulator/org.yuzu.yuzu_emu.ui.main.MainActivity --activity-clear-top";
        if ("wiiu".equals(system) && installed("info.cemu.cemu"))
            return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                    "-n info.cemu.cemu/.MainActivity --activity-clear-top";
        if ("psx".equals(system) && installed("com.github.stenzek.duckstation"))
            return "am start --user 0 -n com.github.stenzek.duckstation/.EmulationActivity " +
                    "--es bootPath \"{file.path}\" --activity-clear-top";
        if ("psp".equals(system) && installed("org.ppsspp.ppsspp"))
            return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                    "-n org.ppsspp.ppsspp/.PpssppActivity --activity-clear-top";
        if ("n64".equals(system) && installed("org.mupen64plusae.v3.fzurita.pro"))
            return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                    "-n org.mupen64plusae.v3.fzurita.pro/paulscode.android.mupen64plusae.SplashActivity --activity-clear-top";
        if ("n64".equals(system) && installed("org.mupen64plusae.v3.fzurita"))
            return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                    "-n org.mupen64plusae.v3.fzurita/paulscode.android.mupen64plusae.SplashActivity --activity-clear-top";
        if ("n3ds".equals(system) && installed("org.azahar_emu.azahar"))
            return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                    "-n org.azahar_emu.azahar/org.citra.citra_emu.ui.main.MainActivity --activity-clear-top";
        if ("n3ds".equals(system) && installed("org.citra.citra_emu"))
            return "am start --user 0 -a android.intent.action.VIEW -d \"{file.path}\" " +
                    "-n org.citra.citra_emu/.ui.main.MainActivity --activity-clear-top";
        return "";
    }

    private boolean installed(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void appendMetadataList(StringBuilder out, String field, JSONArray values) {
        if (values == null) return;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i, "").trim();
            String identity = normalizeCompany(value);
            if (value.isEmpty() || identity.isEmpty() || !seen.add(identity)) continue;
            // Pegasus explicitly permits developer/publisher fields to appear
            // multiple times. Keep one company per field so legal commas in
            // names such as "Tecmo Co., Ltd." are never split incorrectly.
            out.append(field).append(": ").append(metadataSafe(value)).append('\n');
        }
    }

    private static GameSystems.SystemDef identify(File file, String extension) {
        try {
            String ext = extension.toLowerCase(Locale.US);
            GameSystems.SystemDef pathMatch = GameSystems.byPath(file);
            if (pathMatch != null) return pathMatch;
            long size = file.length();
            if (size < 32) return null;
            byte[] head = readRange(file, 0, 0x200);
            if ("nes".equals(ext) && starts(head, new byte[]{0x4e,0x45,0x53,0x1a})) return GameSystems.byFolder("nes");
            if (("unf".equals(ext) || "unif".equals(ext)) && starts(head, "UNIF".getBytes())) return GameSystems.byFolder("nes");
            if ("fds".equals(ext) && (starts(head, new byte[]{0x46,0x44,0x53,0x1a}) || size % 65500 == 0)) return GameSystems.byFolder("nes");
            if (("gb".equals(ext) || "gbc".equals(ext)) && matchesAt(head, 0x104, GB_LOGO)) {
                int cgb = head.length > 0x143 ? head[0x143] & 0xff : 0;
                return ("gbc".equals(ext) || cgb == 0x80 || cgb == 0xc0) ?
                        GameSystems.byFolder("gbc") : GameSystems.byFolder("gb");
            }
            if ("gba".equals(ext) && matchesAt(head, 0x04,
                    new byte[]{0x24,(byte)0xff,(byte)0xae,0x51,0x69,(byte)0x9a,(byte)0xa2,0x21}))
                return GameSystems.byFolder("gba");
            if ("nds".equals(ext) && size >= 0x4000 && printable(head, 0x0c, 4)) return GameSystems.byFolder("nds");
            if (("z64".equals(ext) || "n64".equals(ext) || "v64".equals(ext)) && n64Magic(head)) return GameSystems.byFolder("n64");
            if (("gen".equals(ext) || "md".equals(ext) || "bin".equals(ext)) && containsAt(head, 0x100, "SEGA")) return GameSystems.byFolder("megadrive");
            if ("gg".equals(ext) && gameGearMagic(file)) return GameSystems.byFolder("gamegear");
            if (("sfc".equals(ext) || "smc".equals(ext) || "fig".equals(ext)) && validSnes(file)) return GameSystems.byFolder("snes");
            if ("xci".equals(ext) && containsAt(readRange(file, 0x100, 0x20), 0, "HEAD")) return GameSystems.byFolder("switch");
            if ("nsp".equals(ext) && starts(head, "PFS0".getBytes())) return GameSystems.byFolder("switch");
            if (("3ds".equals(ext) || "cci".equals(ext)) && containsAt(head, 0x100, "NCSD")) return GameSystems.byFolder("n3ds");
            if ("3dsx".equals(ext) && starts(head, "3DSX".getBytes())) return GameSystems.byFolder("n3ds");
            if ("cia".equals(ext) && little32(head, 0) >= 0x2020 && little32(head, 0) < 0x10000) return GameSystems.byFolder("n3ds");
            if ("vpk".equals(ext) && validVitaPackage(file)) return GameSystems.byFolder("psvita");
            if ("pbp".equals(ext) && starts(head, new byte[]{0x00,0x50,0x42,0x50})) return GameSystems.byFolder("psp");
            if ("cso".equals(ext) && starts(head, "CISO".getBytes())) return GameSystems.byFolder("psp");
            if ("pkg".equals(ext) && (starts(head, new byte[]{0x7f,0x50,0x4b,0x47}) || starts(head, "PKG".getBytes()))) return GameSystems.byFolder("ps3");
            if ("wbfs".equals(ext) && starts(head, "WBFS".getBytes())) return GameSystems.byFolder("wii");
            if ("rvz".equals(ext) && starts(head, "RVZ".getBytes())) {
                byte[] id = readRange(file, 88, 6);
                if (id.length == 6 && id[0] == 'G') return GameSystems.byFolder("gc");
                if (id.length == 6 && (id[0] == 'R' || id[0] == 'S'))
                    return GameSystems.byFolder("wii");
            }
            if ("wux".equals(ext) && starts(head, "WUX0".getBytes())) return GameSystems.byFolder("wiiu");
            if ("wua".equals(ext) && (starts(head, "WUA".getBytes()) || starts(head, "ZSTD".getBytes()))) return GameSystems.byFolder("wiiu");
            if ("iso".equals(ext)) return identifyIso(file);
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static GameSystems.SystemDef identifyIso(File file) throws Exception {
        byte[] head = readRange(file, 0, 4 * 1024 * 1024);
        if (containsAt(head, 0x18, new byte[]{0x5d,0x1c,(byte)0x9e,(byte)0xa3})) return GameSystems.byFolder("wii");
        if (containsAt(head, 0x1c, new byte[]{(byte)0xc2,0x33,(byte)0x9f,0x3d})) return GameSystems.byFolder("gc");
        String text = new String(head, StandardCharsets.ISO_8859_1);
        if (text.contains("PS3_GAME")) return GameSystems.byFolder("ps3");
        if (text.contains("PSP_GAME")) return GameSystems.byFolder("psp");
        if (text.contains("BOOT2") && text.contains("SYSTEM.CNF")) return GameSystems.byFolder("ps2");
        return null;
    }

    private static boolean validVitaPackage(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry("sce_sys/param.sfo") != null;
        } catch (Exception ignored) { return false; }
    }

    private static boolean validSnes(File file) throws Exception {
        long size = file.length();
        if (size < 0x8000 || size > 16L * 1024L * 1024L) return false;
        int copier = size % 0x8000 == 512 ? 512 : 0;
        for (int offset : new int[]{0x7fc0 + copier, 0xffc0 + copier, 0x40ffc0 + copier}) {
            if (offset + 32 > size) continue;
            byte[] header = readRange(file, offset, 32);
            int complement = little16(header, 0x1c);
            int checksum = little16(header, 0x1e);
            if ((complement ^ checksum) == 0xffff && printableRatio(header, 0, 21) > 0.70) return true;
        }
        return false;
    }

    private static boolean gameGearMagic(File file) throws Exception {
        for (int offset : new int[]{0x1ff0, 0x3ff0, 0x7ff0}) {
            if (file.length() >= offset + 8 && containsAt(readRange(file, offset, 8), 0, "TMR SEGA")) return true;
        }
        return false;
    }

    private static File mediaRoot() {
        File storage = new File("/storage");
        File[] roots = storage.listFiles();
        if (roots != null) {
            for (File root : roots) {
                if (!root.isDirectory() || root.getName().equals("emulated") || root.getName().equals("self")) continue;
                File media = new File(root, "PegasusMedia");
                if ((media.isDirectory() || media.mkdirs()) && writable(media)) return media;
            }
        }
        File fallback = new File(Environment.getExternalStorageDirectory(), "PegasusMedia");
        fallback.mkdirs();
        return fallback;
    }

    private static JSONArray readRegistry() {
        try {
            if (!REGISTRY.isFile()) return new JSONArray();
            return new JSONArray(readText(REGISTRY));
        } catch (Exception ignored) { return new JSONArray(); }
    }

    private static Set<String> registeredSources(JSONArray registry) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row != null) result.add(row.optString("sourceIdentity"));
        }
        return result;
    }

    private static JSONArray mergeRegistry(JSONArray registry, List<ImportedGame> games) {
        Map<String, JSONObject> byIdentity = new LinkedHashMap<>();
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row != null) byIdentity.put(row.optString("sourceIdentity"), row);
        }
        for (ImportedGame game : games) byIdentity.put(game.sourceIdentity, game.toJson());
        JSONArray result = new JSONArray();
        for (JSONObject row : byIdentity.values()) result.put(row);
        return result;
    }

    private void setStatus(String state, double progress, String message, List<String> titles,
                           int added, boolean needsReload) {
        JSONObject next = new JSONObject();
        try {
            next.put("state", state);
            next.put("progress", Math.max(0, Math.min(1, progress)));
            next.put("message", message);
            next.put("titles", new JSONArray(titles));
            next.put("identified", titles.size());
            next.put("added", added);
            next.put("needsReload", needsReload);
            next.put("running", !"complete".equals(state) && !"error".equals(state) && !"idle".equals(state));
            next.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        synchronized (statusLock) { status = next; }
    }

    private static JSONObject idleStatus() {
        JSONObject value = new JSONObject();
        try {
            value.put("state", "idle"); value.put("progress", 0);
            value.put("message", "Library importer ready"); value.put("titles", new JSONArray());
            value.put("identified", 0); value.put("added", 0);
            value.put("needsReload", false); value.put("running", false);
        } catch (Exception ignored) {}
        return value;
    }

    private static final class Candidate {
        final File source;
        final String zipEntry;
        final GameSystems.SystemDef system;
        String title;
        final String identity;
        final boolean inPlace;
        Candidate(File source, String zipEntry, GameSystems.SystemDef system, String title, String identity) {
            this(source, zipEntry, system, title, identity, false);
        }
        Candidate(File source, String zipEntry, GameSystems.SystemDef system, String title,
                  String identity, boolean inPlace) {
            this.source = source; this.zipEntry = zipEntry; this.system = system;
            this.title = title; this.identity = identity; this.inPlace = inPlace;
        }
    }

    private static final class ImportedGame {
        final GameSystems.SystemDef system;
        final String sourceIdentity;
        final String title;
        final File rom;
        String boxArt = "";
        String video = "";
        String release = "";
        String metacriticSlug = "";
        String scoreSource = "";
        String gamerankingsUrl = "";
        String mobygamesUrl = "";
        final List<String> developers = new ArrayList<>();
        final List<String> publishers = new ArrayList<>();
        double user;
        double gamerankingsScore;
        double mobygamesScore;
        int critic;
        int metacriticCritic;
        int metacriticReviews;
        int gamerankingsReviews;
        boolean archived;
        ImportedGame(Candidate candidate, File rom) {
            this.system = candidate.system; this.sourceIdentity = candidate.identity;
            this.title = candidate.title; this.rom = rom;
        }
        JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("sourceIdentity", sourceIdentity); value.put("system", system.folder);
                value.put("title", title); value.put("file", rom.getAbsolutePath());
                value.put("boxArt", boxArt); value.put("video", video);
                value.put("user", user); value.put("critic", critic);
                value.put("release", release); value.put("metacriticSlug", metacriticSlug);
                value.put("scoreSource", scoreSource);
                value.put("metacriticCritic", metacriticCritic);
                value.put("metacriticReviews", metacriticReviews);
                value.put("gamerankingsScore", gamerankingsScore);
                value.put("gamerankingsReviews", gamerankingsReviews);
                value.put("gamerankingsUrl", gamerankingsUrl);
                value.put("mobygamesScore", mobygamesScore);
                value.put("mobygamesUrl", mobygamesUrl);
                value.put("archived", archived);
                value.put("archiveReason", archived ? "missing-box-art" : "");
                value.put("forceInclude", false);
                value.put("developers", new JSONArray(developers));
                value.put("publishers", new JSONArray(publishers));
            } catch (Exception ignored) {}
            return value;
        }
    }

    private static final class CatalogMatch {
        final String url; final String title;
        CatalogMatch(String url, String title) { this.url = url; this.title = title; }
    }
    private static final class VideoMatch {
        final String url;
        VideoMatch(String url) { this.url = url; }
    }
    private static final class GameRankingsRecord {
        final double score;
        final int reviews;
        final String year;
        final String id;
        final String url;
        GameRankingsRecord(double score, int reviews, String year, String id, String url) {
            this.score = score; this.reviews = reviews; this.year = year; this.id = id; this.url = url;
        }
    }
    private static final class MobyGamesRecord {
        final double score;
        final String year;
        final String developer;
        final String url;
        MobyGamesRecord(double score, String year, String developer, String url) {
            this.score = score; this.year = year; this.developer = developer; this.url = url;
        }
    }

    // ----- Small, defensive IO/string helpers -----

    private static boolean isPartial(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".part") || lower.endsWith(".partial") || lower.endsWith(".crdownload") ||
                lower.endsWith(".tmp") || lower.endsWith(".download");
    }
    private static String downloadFingerprint() {
        List<String> parts = new ArrayList<>();
        for (File downloadRoot : downloadRoots()) {
            File[] entries = downloadRoot.listFiles();
            if (entries == null) continue;
            for (File file : entries) {
                if (file.isFile())
                    parts.add(canonical(file.getAbsolutePath()) + ":" + file.length() +
                            ":" + file.lastModified());
            }
        }
        Collections.sort(parts);
        return sha1(parts.toString());
    }

    /** Returns the standard internal Download folder and every readable
     * removable-storage Download/Downloads folder, with canonical deduping. */
    private static List<File> downloadRoots() {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        addDownloadRoot(roots, DOWNLOADS);

        File[] storageRoots = new File("/storage").listFiles();
        if (storageRoots != null) {
            List<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, storageRoots);
            sorted.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File storageRoot : sorted) {
                String name = storageRoot.getName();
                if (!storageRoot.isDirectory() || "emulated".equals(name) || "self".equals(name))
                    continue;
                addDownloadRoot(roots, new File(storageRoot, "Download"));
                addDownloadRoot(roots, new File(storageRoot, "Downloads"));
            }
        }
        return new ArrayList<>(roots.values());
    }

    private static void addDownloadRoot(Map<String, File> roots, File directory) {
        if (directory == null || !directory.isDirectory() || !directory.canRead()) return;
        roots.put(canonical(directory.getAbsolutePath()), directory);
    }
    private static boolean isPotentialAmbiguous(String ext) {
        return "iso".equals(ext) || "bin".equals(ext) || "rvz".equals(ext);
    }
    private static String extension(String name) {
        int query = name.indexOf('?'); if (query >= 0) name = name.substring(0, query);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }
    private static String stem(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(File.separatorChar));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name;
    }
    private static String cleanTitle(String value) {
        String title = value.replace('_', ' ').trim();
        String previous;
        do {
            previous = title;
            title = title.replaceAll("\\s*[\\[(](?:USA|Europe|Japan|World|En(?:,[A-Za-z]+)*|Rev[^\\])]*|v?\\d+(?:\\.\\d+)*|!|b|h|t[^\\])]*)[\\])]\\s*$", "").trim();
        } while (!title.equals(previous));
        title = title.replaceAll("\\s+-\\s+", ": ").replaceAll("\\s+", " ").trim();
        Matcher article = Pattern.compile("^(.+), (The|A|An)$", Pattern.CASE_INSENSITIVE).matcher(title);
        if (article.matches()) title = article.group(2) + " " + article.group(1);
        return title.isEmpty() ? "Untitled Game" : title;
    }
    private static void addUnique(List<String> values, String value) {
        String clean = value == null ? "" : value.trim();
        String identity = normalizeCompany(clean);
        if (clean.isEmpty() || identity.isEmpty()) return;
        for (String existing : values)
            if (normalizeCompany(existing).equals(identity)) return;
        values.add(clean);
    }
    private static String normalizeCompany(String value) {
        String key = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.US)
                .replace("&", " and ").replaceAll("[^a-z0-9]+", " ").trim();
        for (int pass = 0; pass < 3; pass++)
            key = key.replaceAll("\\s+(incorporated|inc|corporation|corp|company|co|limited|ltd|pty|sa)$", "");
        return key;
    }
    private static String normalize(String value) {
        String title = decode(value);
        int slash = Math.max(title.lastIndexOf('/'), title.lastIndexOf(File.separatorChar));
        if (slash >= 0) title = title.substring(slash + 1);
        String lower = title.toLowerCase(Locale.US);
        String[] knownExtensions = new String[]{
                ".png", ".jpg", ".jpeg", ".webp", ".mp4", ".zip", ".7z",
                ".nes", ".unf", ".unif", ".fds", ".sfc", ".smc", ".fig",
                ".n64", ".z64", ".v64", ".gb", ".gbc", ".gba", ".nds",
                ".gg", ".gen", ".md", ".bin", ".cue", ".gdi", ".chd",
                ".iso", ".cso", ".rvz", ".wbfs", ".xci", ".nsp", ".wua",
                ".wux", ".3ds", ".3dsx", ".cia", ".cci", ".vpk", ".pbp"
        };
        for (String extension : knownExtensions) {
            if (lower.endsWith(extension)) {
                title = title.substring(0, title.length() - extension.length());
                break;
            }
        }
        String previous;
        do { previous = title; title = title.replaceAll("\\([^()]*\\)|\\[[^\\[\\]]*]", " "); }
        while (!title.equals(previous));
        title = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        title = title.toLowerCase(Locale.US).replace("&", " and ");
        return title.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }
    private static int regionRank(String value) {
        String lower = decode(value).toLowerCase(Locale.US);
        if (lower.contains("(usa") || lower.contains("(us)")) return 0;
        if (lower.contains("(world")) return 1;
        if (lower.contains("(europe")) return 2;
        if (lower.contains("(japan")) return 4;
        return 3;
    }
    private static String safeFilename(String value) {
        String clean = value.replaceAll("[\\x00-\\x1f/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        return clean.length() > 180 ? clean.substring(0, 180).trim() : clean;
    }
    private static String metadataSafe(String value) { return value.replace('\n', ' ').replace('\r', ' ').trim(); }
    private static String canonical(String path) { try { return new File(path).getCanonicalPath(); } catch (Exception e) { return path; } }
    private static String decode(String value) { try { return URLDecoder.decode(value, "UTF-8"); } catch (Exception e) { return value; } }
    private static String encodePath(String value) { try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); } catch (Exception e) { return value; } }
    private static String encodeHref(String value) {
        String[] parts = value.split("/", -1); StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) { if (i > 0) out.append('/'); out.append(encodePath(decode(parts[i]))); }
        return out.toString();
    }
    private static String sha1(String value) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-1"); byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format(Locale.US, "%02x", b)); return out.substring(0, 16);
        } catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }
    private static String format(double value) { return value == Math.rint(value) ? String.valueOf((long)value) : String.format(Locale.US, "%.1f", value); }
    private static String formatScore(double value) { String result=String.format(Locale.US,"%.2f",value); return result.replaceAll("0+$","").replaceAll("\\.$",""); }
    private static String join(List<String> values, String separator) { StringBuilder out=new StringBuilder(); for(String value:values){if(out.length()>0)out.append(separator);out.append(value);}return out.toString(); }
    private static String shortError(Throwable error) { String value = error.getMessage(); return value == null ? error.getClass().getSimpleName() : value; }

    private static byte[] readRange(File file, long offset, int length) throws Exception {
        byte[] data = new byte[length]; int total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            long skipped = 0; while (skipped < offset) { long value = input.skip(offset - skipped); if (value <= 0) break; skipped += value; }
            while (total < length) { int count = input.read(data, total, length - total); if (count < 0) break; total += count; }
        }
        if (total == data.length) return data;
        byte[] shortData = new byte[total]; System.arraycopy(data, 0, shortData, 0, total); return shortData;
    }
    private static boolean starts(byte[] data, byte[] expected) { return matchesAt(data, 0, expected); }
    private static boolean containsAt(byte[] data, int offset, String value) { return matchesAt(data, offset, value.getBytes(StandardCharsets.ISO_8859_1)); }
    private static boolean containsAt(byte[] data, int offset, byte[] value) { return matchesAt(data, offset, value); }
    private static boolean matchesAt(byte[] data, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > data.length) return false;
        for (int i = 0; i < expected.length; i++) if (data[offset + i] != expected[i]) return false;
        return true;
    }
    private static boolean n64Magic(byte[] h) { return starts(h,new byte[]{(byte)0x80,0x37,0x12,0x40}) || starts(h,new byte[]{0x37,(byte)0x80,0x40,0x12}) || starts(h,new byte[]{0x40,0x12,0x37,(byte)0x80}); }
    private static boolean printable(byte[] data, int offset, int length) { return printableRatio(data, offset, length) > 0.75; }
    private static double printableRatio(byte[] data, int offset, int length) { if (offset + length > data.length) return 0; int count=0; for(int i=offset;i<offset+length;i++){int c=data[i]&0xff;if(c>=0x20&&c<=0x7e)count++;} return count/(double)length; }
    private static int little16(byte[] data, int offset) { return offset+1<data.length ? (data[offset]&0xff)|((data[offset+1]&0xff)<<8) : 0; }
    private static int little32(byte[] data, int offset) { return offset+3<data.length ? (data[offset]&0xff)|((data[offset+1]&0xff)<<8)|((data[offset+2]&0xff)<<16)|((data[offset+3]&0xff)<<24) : 0; }

    private static void copy(File source, File target) throws Exception {
        target.getParentFile().mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(source)); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[1024 * 1024]; int count; while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
        }
        if (target.length() != source.length()) throw new java.io.IOException("copy verification failed");
    }
    private static void extract(ZipFile zip, ZipEntry entry, File target) throws Exception {
        target.getParentFile().mkdirs();
        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry)); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[1024 * 1024]; int count; long total=0; while ((count=in.read(buffer))>=0){out.write(buffer,0,count);total+=count;}
            if (entry.getSize() >= 0 && total != entry.getSize()) throw new java.io.IOException("archive extraction verification failed");
        }
    }
    private static boolean sameContent(File left, File right) {
        if (left.length() != right.length()) return false;
        try (InputStream a = new BufferedInputStream(new FileInputStream(left));
             InputStream b = new BufferedInputStream(new FileInputStream(right))) {
            byte[] ab = new byte[1024 * 1024], bb = new byte[1024 * 1024];
            while (true) {
                int an = a.read(ab), bn = b.read(bb);
                if (an != bn) return false;
                if (an < 0) return true;
                for (int i = 0; i < an; i++) if (ab[i] != bb[i]) return false;
            }
        } catch (Exception ignored) { return false; }
    }
    private static String readText(File file) throws Exception { StringBuilder out=new StringBuilder(); try(BufferedReader r=new BufferedReader(new FileReader(file))){String line;while((line=r.readLine())!=null)out.append(line).append('\n');} return out.toString(); }
    private static void writeTextAtomic(File target, String value) throws Exception {
        target.getParentFile().mkdirs();
        File temp = new File(target.getParentFile(), "." + target.getName() + ".writing");
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (backup.exists()) backup.delete();
        if (target.exists() && !target.renameTo(backup)) {
            temp.delete();
            throw new java.io.IOException("cannot back up " + target);
        }
        if (!temp.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target);
            throw new java.io.IOException("cannot commit " + target);
        }
    }
    private static void writeJsonAtomic(File target, JSONArray value) throws Exception { writeTextAtomic(target, value.toString(2)+"\n"); }
    private static List<String> splitStanzas(String text) { List<String> values=new ArrayList<>(); for(String item:text.split("\\n\\s*\\n"))if(!item.trim().isEmpty())values.add(item.trim()); return values; }
    private static String joinStanzas(List<String> values) { StringBuilder out=new StringBuilder(); for(String value:values){if(out.length()>0)out.append("\n\n");out.append(value.trim());}return out.append('\n').toString(); }
    private static String field(String stanza, String key) { String prefix=key+":"; for(String line:stanza.split("\\n"))if(line.startsWith(prefix))return line.substring(prefix.length()).trim(); return ""; }
    private static String removeField(String stanza, String key) { String prefix=key+":"; StringBuilder out=new StringBuilder(); for(String line:stanza.split("\\n")){if(line.startsWith(prefix))continue;if(out.length()>0)out.append('\n');out.append(line);}return out.toString(); }
    private static GameSystems.SystemDef systemFromRomPath(String path) { String marker="/Games/"; int at=path.indexOf(marker); if(at<0)return null; String rest=path.substring(at+marker.length()); int slash=rest.indexOf('/'); return GameSystems.byFolder(slash<0?rest:rest.substring(0,slash)); }

    private static String cachedText(File cache, String url, long maxAge, long maxBytes) throws Exception {
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < maxAge) return readText(cache);
        byte[] bytes = fetchBytes(url, maxBytes); String value = new String(bytes, StandardCharsets.UTF_8); writeTextAtomic(cache, value); return value;
    }
    private static byte[] fetchBytes(String url, long maxBytes) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection(); connection.setConnectTimeout(15000);connection.setReadTimeout(45000);connection.setRequestProperty("User-Agent",USER_AGENT);connection.setInstanceFollowRedirects(true);
        if (url.contains("metacritic.com")) { connection.setRequestProperty("Origin", "https://www.metacritic.com"); connection.setRequestProperty("Referer", "https://www.metacritic.com/"); }
        int status=connection.getResponseCode();if(status<200||status>=300)throw new java.io.IOException("HTTP "+status);
        try(InputStream in=new BufferedInputStream(connection.getInputStream());ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[65536];int count;long total=0;while((count=in.read(buffer))>=0){total+=count;if(total>maxBytes)throw new java.io.IOException("response too large");out.write(buffer,0,count);}return out.toByteArray();}finally{connection.disconnect();}
    }
    private static boolean download(String url, File target, long maxBytes) throws Exception {
        if(target.isFile()&&target.length()>512)return true; target.getParentFile().mkdirs(); File part=new File(target.getAbsolutePath()+".part"); HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setRequestProperty("User-Agent",USER_AGENT);c.setInstanceFollowRedirects(true);int code=c.getResponseCode();if(code<200||code>=300){c.disconnect();return false;}long total=0;try(InputStream in=new BufferedInputStream(c.getInputStream());OutputStream out=new BufferedOutputStream(new FileOutputStream(part))){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>=0){total+=n;if(total>maxBytes)throw new java.io.IOException("download too large");out.write(b,0,n);}}finally{c.disconnect();}if(total<512){part.delete();return false;}if(target.exists())target.delete();return part.renameTo(target);
    }
    private static boolean hasPlatform(JSONArray platforms, String expected) { if(platforms==null)return false; for(int i=0;i<platforms.length();i++){JSONObject p=platforms.optJSONObject(i);if(p!=null&&p.optString("name").equalsIgnoreCase(expected))return true;}return false; }
    private static boolean writable(File directory) {
        File probe = new File(directory, ".thorium-write-test");
        try { if (!probe.createNewFile()) return false; return probe.delete(); }
        catch (Exception ignored) { return false; }
    }
    private static boolean recentlyMissed(File cacheRoot, GameSystems.SystemDef system, String title) { File f=new File(cacheRoot,"misses/"+system.folder+"/"+sha1(normalize(title)));return f.isFile()&&System.currentTimeMillis()-f.lastModified()<MISS_RETRY_MS; }
    private static void recordMiss(File cacheRoot, GameSystems.SystemDef system, String title) { try { File f=new File(cacheRoot,"misses/"+system.folder+"/"+sha1(normalize(title)));f.getParentFile().mkdirs();new FileOutputStream(f).close(); } catch(Exception ignored){} }
}
