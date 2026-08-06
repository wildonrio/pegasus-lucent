package com.thorium.preview;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Curated, RetroArch-free Android emulator catalog.
 *
 * The catalog deliberately separates discovery from installation. Android only
 * permits a regular app to silently install another APK when it is a device or
 * profile owner. Lucent can therefore detect a missing emulator, select the
 * correct official source, and stage an installer, but a normal retail device
 * must still show Android's package-install confirmation.
 */
final class EmulatorCatalog {
    static final class Entry {
        final String id;
        final String name;
        final List<String> systems;
        final List<String> packages;
        final String source;
        final String delivery;
        final boolean directLaunch;

        Entry(String id, String name, String systems, String packages, String source,
              String delivery, boolean directLaunch) {
            this.id = id;
            this.name = name;
            this.systems = words(systems);
            this.packages = words(packages);
            this.source = source;
            this.delivery = delivery;
            this.directLaunch = directLaunch;
        }

        String installedPackage(Context context) {
            PackageManager packagesManager = context.getPackageManager();
            for (String packageName : packages) {
                try {
                    packagesManager.getPackageInfo(packageName, 0);
                    return packageName;
                } catch (Exception ignored) {}
            }
            return "";
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, Entry> BY_SYSTEM = new LinkedHashMap<>();

    static {
        // Current standalone Android emulators. Package aliases let Lucent
        // recognize paid/free, stable/nightly, and actively maintained forks
        // without surfacing those implementation details in the game library.
        add("mame4droid-current", "MAME4droid Current", "arcade",
                "com.seleuco.mame4d2024",
                "https://github.com/seleuco/MAME4droid-2024/releases", "github", true);
        add("2600emu", "2600.emu", "atari2600",
                "com.explusalpha.A2600Emu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("colleen", "Colleen", "atari800 atari5200",
                "name.nick.jubanka.colleen", "https://github.com/romjacket/Colleen",
                "external", true);
        add("nesemu", "NES.emu", "nes",
                "com.explusalpha.NesEmu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("snes9x", "Snes9x EX+", "snes",
                "com.explusalpha.Snes9xPlus", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("gbcemu", "GBC.emu", "gb gbc",
                "com.explusalpha.GbcEmu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("gbaemu", "GBA.emu", "gba",
                "com.explusalpha.GbaEmu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("mdemu", "MD.emu", "sg1000 mastersystem megadrive segacd sega32x gamegear",
                "com.explusalpha.MdEmu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("pceemu", "PCE.emu", "pcengine pcenginecd",
                "com.PceEmu", "https://www.explusalpha.com/contents/emuex", "store", true);
        add("neoemu", "NEO.emu", "neogeo neogeocd",
                "com.explusalpha.NeoEmu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("ngpemu", "NGP.emu", "ngp",
                "com.explusalpha.NgpEmu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("swanemu", "Swan.emu", "wonderswan wonderswancolor",
                "com.explusalpha.SwanEmu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("saturnemu", "Saturn.emu", "saturn",
                "com.explusalpha.SaturnEmu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("c64emu", "C64.emu", "c64",
                "com.explusalpha.C64Emu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("msxemu", "MSX.emu", "msx",
                "com.explusalpha.MsxEmu", "https://www.explusalpha.com/contents/emuex",
                "store", true);
        add("colem", "ColEm", "colecovision",
                "com.fms.colem.deluxe com.fms.colem", "https://fms.komkon.org/ColEm/",
                "store", true);
        add("m64plus-fz", "M64Plus FZ", "n64",
                "org.mupen64plusae.v3.fzurita.pro org.mupen64plusae.v3.fzurita " +
                "org.mupen64plusae.v3.fzurita.amazon",
                "https://github.com/mupen64plus-ae/mupen64plus-ae", "store", true);
        add("duckstation", "DuckStation", "psx",
                "com.github.stenzek.duckstation",
                "https://github.com/stenzek/duckstation/releases", "store", true);
        add("armsx2", "ARMSX2", "ps2",
                "com.armsx2 xyz.aethersx2.android", "https://github.com/ARMSX2/ARMSX2",
                "external", true);
        add("flycast", "Flycast", "dreamcast",
                "com.flycast.emulator com.reicast.emulator",
                "https://github.com/flyinghead/flycast/releases", "github", true);
        add("melonds", "melonDS", "nds",
                "me.magnum.melonds.nightly me.magnum.melonds me.magnum.melondualds",
                "https://github.com/rafaelvcaetano/melonDS-android/releases", "github", true);
        add("dolphin", "Dolphin", "gc wii",
                "org.dolphinemu.dolphinemu",
                "https://api.dolphin-emu.org/download/", "official-api", true);
        add("azahar", "Azahar", "n3ds",
                "org.azahar_emu.azahar io.github.lime3ds.android org.citra.emu",
                "https://github.com/azahar-emu/azahar/releases", "github", true);
        add("ppsspp", "PPSSPP", "psp",
                "org.ppsspp.ppssppgold org.ppsspp.ppsspp",
                "https://dev.ppsspp.org/download/", "official-api", true);
        add("vita3k", "Vita3K", "psvita",
                "org.vita3k.emulator org.vita3k.emulator.ikhoeyZX",
                "https://github.com/Vita3K/Vita3K-Android/releases", "github", true);
        add("cemu", "Cemu", "wiiu",
                "info.cemu.cemu", "https://github.com/cemu-project/Cemu/releases", "github", true);
        add("eden", "Eden", "switch",
                "dev.legacy.eden_emulator org.citron.citron_emu",
                "https://git.eden-emu.dev/eden-emu/eden", "external", true);
        add("winlator", "Winlator", "windows",
                "com.winlator.vanilla com.winlator.cmod com.winlator",
                "https://github.com/brunodev85/winlator/releases", "github", true);
        add("dosbox", "Magic DOSBox", "dos",
                "bruenor.magicbox.free bruenor.magicbox",
                "https://magicbox.imejl.sk/", "store", true);
        add("scummvm", "ScummVM", "scummvm",
                "org.scummvm.scummvm", "https://www.scummvm.org/downloads/", "official", true);

        // No mature direct-launch Android option is selected for these systems.
        // Keeping them explicit prevents Lucent from pretending that an unsafe
        // or abandoned build can be silently installed.
        unsupported("no-standalone", "No maintained standalone Android emulator selected",
                "odyssey2 intellivision apple2 amstradcpc amiga atarist atari7800 " +
                "3do amigacd32 jaguar virtualboy xbox xbox360 ps3 zxspectrum");
    }

    private static void add(String id, String name, String systems, String packages,
                            String source, String delivery, boolean directLaunch) {
        Entry entry = new Entry(id, name, systems, packages, source, delivery, directLaunch);
        ENTRIES.add(entry);
        for (String system : entry.systems) BY_SYSTEM.put(system, entry);
    }

    private static void unsupported(String id, String name, String systems) {
        add(id, name, systems, "", "", "unsupported", false);
    }

    static Entry forSystem(String folder) { return BY_SYSTEM.get(folder); }

    static List<Entry> missingFor(Context context, Set<String> activeSystems) {
        List<Entry> missing = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String folder : activeSystems) {
            Entry entry = forSystem(folder);
            if (entry == null || "unsupported".equals(entry.delivery) ||
                    !entry.installedPackage(context).isEmpty() || !seen.add(entry.id)) continue;
            missing.add(entry);
        }
        return missing;
    }

    static String statusJson(Context context, Set<String> activeSystems) {
        JSONObject response = new JSONObject();
        JSONArray systems = new JSONArray();
        int ready = 0;
        int missing = 0;
        try {
            for (String folder : activeSystems) {
                GameSystems.SystemDef system = GameSystems.byFolder(folder);
                if (system == null) continue;
                Entry emulator = forSystem(folder);
                JSONObject row = new JSONObject();
                row.put("system", folder);
                row.put("collection", system.collection);
                if (emulator == null) {
                    row.put("state", "unsupported");
                    row.put("emulator", "No catalog entry");
                } else {
                    String installed = emulator.installedPackage(context);
                    boolean supported = !"unsupported".equals(emulator.delivery);
                    boolean present = !installed.isEmpty();
                    row.put("state", present ? "ready" : supported ? "missing" : "unsupported");
                    row.put("emulator", emulator.name);
                    row.put("installedPackage", installed);
                    row.put("source", emulator.source);
                    row.put("delivery", emulator.delivery);
                    row.put("directLaunch", emulator.directLaunch);
                    if (present) ready++;
                    else if (supported) missing++;
                }
                systems.put(row);
            }
            response.put("active", systems.length());
            response.put("ready", ready);
            response.put("missing", missing);
            response.put("systems", systems);
            response.put("silentInstallAvailable", false);
            response.put("installPolicy", "Android confirmation required unless device owner");
        } catch (Exception ignored) {}
        return response.toString();
    }

    private static List<String> words(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? Collections.emptyList() :
                Collections.unmodifiableList(Arrays.asList(trimmed.split("\\s+")));
    }

    private EmulatorCatalog() {}
}
