// ═══════════════════════════════════════════════════════════════
//  OpappConfig.java – Konfiguration (config/opapp.json)
//
//  ✅ HIER ÄNDERN: Standardwerte der Felder
//  ❌ NICHT ÄNDERN: Feldnamen (stehen so in bestehenden config-Dateien
//                   der Nutzer; Umbenennen würde den Token "vergessen")
//
//  Die Datei liegt im Minecraft-Ordner unter config/opapp.json und
//  enthält den geheimen API-Token. Nicht weitergeben oder hochladen!
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
//
//  ÄNDERUNGEN (Fix 1.0.1):
//    - NEU: debugLive – schreibt config/opapp-debug.txt bei jeder neu
//      gelesenen Menü-Seite automatisch (asynchron). Umschalten mit
//      /opapp debug live. Bestehende config-Dateien ohne dieses Feld
//      bekommen automatisch den Standardwert (false).
// ═══════════════════════════════════════════════════════════════

package de.opapp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.opapp.OpappMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OpappConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Basis-Adresse des Cloudflare Workers (ohne abschließenden Schrägstrich). */
    public String apiBase = "https://opapp-api.px32.workers.dev";

    /** Geheimer API-Token (leer = nicht verbunden). Kommt von /opapp link. */
    public String token = "";

    /** Hintergrund-Zähler für erkannte Blöcke an/aus. */
    public boolean counterEnabled = true;

    /** Alle X Minuten ungesendete Zählerstände sicherheitshalber senden. */
    public int flushIntervalMinutes = 10;

    /** Kurze Chat-Meldung nach dem Synchronisieren eines Menüs. */
    public boolean chatFeedback = true;

    /** Debug-Datei bei jeder gelesenen Seite automatisch aktualisieren. */
    public boolean debugLive = false;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("opapp.json");
    }

    /** Lädt die Datei; fehlt sie oder ist sie kaputt, gelten die Standardwerte. */
    public static OpappConfig load() {
        Path file = path();
        if (Files.exists(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                OpappConfig loaded = GSON.fromJson(text, OpappConfig.class);
                if (loaded != null) return loaded.sanitized();
            } catch (Exception e) {
                OpappMod.LOG.warn("config/opapp.json konnte nicht gelesen werden, Standardwerte aktiv.", e);
            }
        }
        OpappConfig fresh = new OpappConfig();
        fresh.save();
        return fresh;
    }

    /** Fehlende/ungültige Werte (z.B. von Hand editiert) auf Standard setzen. */
    private OpappConfig sanitized() {
        if (apiBase == null || apiBase.isBlank()) apiBase = new OpappConfig().apiBase;
        apiBase = apiBase.trim().replaceAll("/+$", "");
        if (token == null) token = "";
        if (flushIntervalMinutes < 1) flushIntervalMinutes = 10;
        return this;
    }

    public synchronized void save() {
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            OpappMod.LOG.error("config/opapp.json konnte nicht gespeichert werden.", e);
        }
    }
}
