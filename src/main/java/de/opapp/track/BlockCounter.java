// ═══════════════════════════════════════════════════════════════
//  BlockCounter.java – Hintergrund-Zähler für abgebaute Blöcke
//
//  ✅ HIER ÄNDERN: KEYWORDS (Stichwort im Aufgabennamen → Block-IDs)
//  ❌ NICHT ÄNDERN: Zählen nur im Speicher; Senden nur über flushDirty()
//                   (asynchron) – nie im Render-Thread
//
//  WIE BLÖCKE ERKANNT WERDEN (resolveBlocks):
//    1. Ziel-Namen aus der Lore ("Blöcke abbauen: - Lapislazulierz")
//       werden über die Block-Namen des Spiels (in der Sprache des
//       Clients) in Block-IDs übersetzt. Das ist die genaueste Quelle.
//       Funktioniert nur, wenn das Spiel auf Deutsch läuft – sonst
//       greift Schritt 2.
//    2. Stichwort im Aufgabennamen (KEYWORDS), z.B. "Kohlenoob" → Kohleerz
//       + Deepslate-Kohleerz
//    3. sonst: das Menü-Icon, falls es ein Block ist
//    4. sonst: Aufgabe wird nicht mitgezählt (nur der Scan von /erfolge
//       aktualisiert sie)
//
//  WANN WIRD GESENDET (alles asynchron):
//    - sobald eine Aufgabe ihr Ziel erreicht
//    - alle flushIntervalMinutes Minuten, falls etwas ungesendet ist
//      (Sicherheitsnetz gegen Abstürze)
//    - beim Ausloggen / Beenden (siehe OpappMod)
//    - /erfolge öffnen: der Scan überschreibt den lokalen Zähler
//
//  Der Zähler ist eine SCHÄTZUNG – OPSUCHT kann anders zählen (z.B.
//  Custom-Enchants, die mehrere Blöcke abbauen). Jeder Scan von
//  /erfolge korrigiert den Stand wieder auf die Server-Wahrheit.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
//
//  ÄNDERUNGEN (Fix 1.0.1):
//    - NEU: Schritt 1 (Ziel-Namen aus der Lore → Block-ID). Behebt z.B.
//      "Reinforced Deepslate Zerstörer" (zählte vorher normalen
//      Tiefenschiefer) sowie Endstein, Spawner und "König der Tiefe"
//      (vorher gar keine Blöcke).
// ═══════════════════════════════════════════════════════════════

package de.opapp.track;

import de.opapp.OpappMod;
import de.opapp.config.OpappConfig;
import de.opapp.net.OpappApi;
import de.opapp.scan.TaskEntry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class BlockCounter {

    /**
     * ✅ HIER ÄNDERN: Stichwort (klein, Umlaute als ae/oe/ue) → Block-IDs.
     * Reihenfolge ist wichtig: Der ERSTE passende Eintrag gewinnt.
     */
    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("kohle", List.of("coal_ore", "deepslate_coal_ore"));
        KEYWORDS.put("eisen", List.of("iron_ore", "deepslate_iron_ore"));
        KEYWORDS.put("kupfer", List.of("copper_ore", "deepslate_copper_ore"));
        KEYWORDS.put("gold", List.of("gold_ore", "deepslate_gold_ore"));
        KEYWORDS.put("redstone", List.of("redstone_ore", "deepslate_redstone_ore"));
        KEYWORDS.put("lapis", List.of("lapis_ore", "deepslate_lapis_ore"));
        KEYWORDS.put("diamant", List.of("diamond_ore", "deepslate_diamond_ore"));
        KEYWORDS.put("smaragd", List.of("emerald_ore", "deepslate_emerald_ore"));
        KEYWORDS.put("quarz", List.of("nether_quartz_ore"));
        KEYWORDS.put("netherit", List.of("ancient_debris"));
        KEYWORDS.put("deepslate", List.of("deepslate"));
        KEYWORDS.put("tiefenschiefer", List.of("deepslate"));
        KEYWORDS.put("diorit", List.of("diorite"));
        KEYWORDS.put("granit", List.of("granite"));
        KEYWORDS.put("andesit", List.of("andesite"));
        KEYWORDS.put("stein", List.of("stone"));
    }

    /** Block-Anzeigename (klein, in der Sprache des Clients) → Block-ID ohne Namespace. */
    private static Map<String, String> nameIndex;

    private final OpappConfig config;
    private final OpappApi api;
    private final ProgressStore store;
    private long ticks;

    public BlockCounter(OpappConfig config, OpappApi api, ProgressStore store) {
        this.config = config;
        this.api = api;
        this.store = store;
    }

    public void register() {
    PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
        if (world.isClient()) {
            onBlockBroken(state.getBlock());
        }
    });
    ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
}

    private void onBlockBroken(Block block) {
        if (!config.counterEnabled || !api.isLinked()) return;
        String id = Registries.BLOCK.getId(block).getPath();
        if (store.onBlockBroken(id)) {
            flushDirty(); // Ziel erreicht → sofort (asynchron) senden
        }
    }

    private void onTick() {
        ticks++;
        long interval = Math.max(1, config.flushIntervalMinutes) * 60L * 20L;
        if (ticks % interval == 0 && api.isLinked() && store.hasDirty()) {
            flushDirty();
        }
    }

    /** Sendet alle ungesendeten Zählerstände asynchron. */
    public void flushDirty() {
        if (!api.isLinked()) return;
        List<TaskEntry> dirty = store.takeDirty();
        if (dirty.isEmpty()) return;
        api.syncAsync("erfolge", dirty, false).thenAccept(ok -> {
            if (!ok) store.markDirty(dirty);
        });
    }

    /** Wie flushDirty(), wartet aber kurz auf das Ergebnis (nur beim Beenden). */
    public void flushDirtyAndWait(long timeoutMs) {
        if (!api.isLinked()) return;
        List<TaskEntry> dirty = store.takeDirty();
        if (dirty.isEmpty()) return;
        try {
            api.syncAsync("erfolge", dirty, false).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            OpappMod.LOG.debug("Letzter Sync beim Beenden nicht abgeschlossen: {}", e.toString());
        }
    }

    /**
     * Welche Block-IDs (ohne Namespace) zählen für diese Aufgabe?
     * Leer = Aufgabe wird nicht mitgezählt. Nur vom Client-Thread aufrufen.
     */
    public static Set<String> resolveBlocks(TaskEntry entry) {
        Set<String> result = new LinkedHashSet<>();

        // 1) Ziel-Namen aus der Lore → Block-IDs
        if (!entry.targets().isEmpty()) {
            Map<String, String> index = blockNameIndex();
            for (String label : entry.targets()) {
                String id = index.get(label.toLowerCase(Locale.ROOT));
                if (id != null) result.add(id);
            }
            if (!result.isEmpty()) return result;
        }

        // 2) Stichwort im Aufgabennamen
        String name = entry.name().toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        for (Map.Entry<String, List<String>> keyword : KEYWORDS.entrySet()) {
            if (name.contains(keyword.getKey())) {
                result.addAll(keyword.getValue());
                return result;
            }
        }

        // 3) Menü-Icon, falls es ein Block ist
        String icon = entry.icon();
        if (icon != null && isBlock(icon)) {
            result.add(icon);
            // Erz-Icons zählen auch die Deepslate-Variante mit.
            if (icon.endsWith("_ore") && !icon.startsWith("deepslate_") && !icon.startsWith("nether_")) {
                result.add("deepslate_" + icon);
            }
        }
        return result;
    }

    /** Baut beim ersten Aufruf die Tabelle Anzeigename → Block-ID (ca. 1.100 Blöcke). */
    private static synchronized Map<String, String> blockNameIndex() {
        if (nameIndex == null) {
            Map<String, String> index = new HashMap<>();
            for (Block block : Registries.BLOCK) {
                String label = block.getName().getString().toLowerCase(Locale.ROOT);
                index.putIfAbsent(label, Registries.BLOCK.getId(block).getPath());
            }
            nameIndex = index;
        }
        return nameIndex;
    }

    private static boolean isBlock(String path) {
        try {
            return Registries.BLOCK.containsId(Identifier.of("minecraft", path));
        } catch (Exception e) {
            return false; // ungültige ID
        }
    }
}
