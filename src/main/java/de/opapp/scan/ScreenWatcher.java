// ═══════════════════════════════════════════════════════════════
//  ScreenWatcher.java – liest geöffnete OPSUCHT-Menüs passiv aus
//
//  ✅ HIER ÄNDERN: Zeitkonstanten unten (Prüf-Intervall, Wartezeiten)
//  ❌ NICHT ÄNDERN: Es wird NIE geklickt und kein Paket gesendet – die
//                   Mod liest nur, was der Server ohnehin schon geschickt
//                   hat. Kein Zugriff aus dem Render-Thread (nur Tick).
//
//  SITZUNG:
//    Start  - Spieler tippt einen Menü-Befehl (/erfolge, /a …) ODER ein
//             Container-Fenster hat einen passenden Titel
//    Lauf   - solange ein Container-Fenster offen ist, wird alle paar
//             Ticks geprüft, ob sich der Inhalt geändert hat. Wechselt der
//             Spieler per Klick in eine Kategorie, bleibt die Sitzung bestehen.
//    Ende   - kein Container-Fenster mehr für SESSION_GRACE_TICKS Ticks
//             (= Menü geschlossen) → alle gesammelten Aufgaben werden in
//             EINEM asynchronen Request gesendet
//
//  PERFORMANCE: Ohne Sitzung kostet ein Tick nur einen Vergleich. Mit
//  Sitzung wird nur alle INSPECT_EVERY_TICKS Ticks ein Menü mit höchstens
//  54 Slots gelesen – kein Rendering, keine Allokation pro Frame.
//
//  DEBUG: Der Inhalt der zuletzt gelesenen Seite wird als Text vorgehalten
//  (lastDump) und kann mit /opapp debug in config/opapp-debug.txt
//  geschrieben werden.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan;

import de.opapp.OpappMod;
import de.opapp.net.OpappApi;
import de.opapp.track.BlockCounter;
import de.opapp.track.ProgressStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ScreenWatcher {

    /** ✅ HIER ÄNDERN: alle wie viele Ticks (20 = 1 s) der Inhalt geprüft wird. */
    private static final int INSPECT_EVERY_TICKS = 4;

    /** Kein Container mehr so lange → Menü gilt als geschlossen (Klick in eine Kategorie
     *  ersetzt das Fenster kurz – 40 Ticks = 2 s Toleranz). */
    private static final int SESSION_GRACE_TICKS = 40;

    /** Befehl getippt, aber so lange kein Menü erschienen → Sitzung verwerfen (5 s). */
    private static final int SESSION_START_TIMEOUT_TICKS = 100;

    /** Auto-Start-Prüfung (Titel) nur alle X Ticks. */
    private static final int AUTOSTART_CHECK_TICKS = 10;

    private final OpappApi api;
    private final ProgressStore store;

    private MenuScanner scanner;          // null = keine Sitzung
    private long tick;
    private long sessionStartTick;
    private long lastContainerTick;
    private boolean containerSeen;
    private int lastHash;

    /** Während der Sitzung gesammelte Aufgaben (Schlüssel → Aufgabe). */
    private final Map<String, TaskEntry> pending = new LinkedHashMap<>();
    private String pendingMenu = "";

    private String lastDump = "";
    private String lastSummary = "";

    public ScreenWatcher(OpappApi api, ProgressStore store) {
        this.api = api;
        this.store = store;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientSendMessageEvents.COMMAND.register(this::onCommand);
    }

    // ═══════════════════════════════════════════════════════════
    //  Events
    // ═══════════════════════════════════════════════════════════

    /** Befehl ohne führenden "/" – z.B. "erfolge" oder "a". */
    private void onCommand(String command) {
        String root = command.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        MenuScanner found = de.opapp.scan.MenuScannerRegistry.findByCommand(root);
        if (found != null) startSession(found);
    }

    private void onTick(MinecraftClient client) {
        tick++;

        Screen screen = client.currentScreen;
        GenericContainerScreen container = screen instanceof GenericContainerScreen g ? g : null;

        // ─ Keine Sitzung: höchstens gelegentlich den Titel prüfen ─
        if (scanner == null) {
            if (container != null && tick % AUTOSTART_CHECK_TICKS == 0) {
                MenuScanner found = de.opapp.scan.MenuScannerRegistry
                        .findByTitle(container.getTitle().getString());
                if (found != null) startSession(found);
            }
            return;
        }

        // ─ Sitzung läuft ─
        if (container != null) {
            containerSeen = true;
            lastContainerTick = tick;
            if (tick % INSPECT_EVERY_TICKS == 0) inspect(container);
        } else if (containerSeen) {
            if (tick - lastContainerTick > SESSION_GRACE_TICKS) endSession();
        } else if (tick - sessionStartTick > SESSION_START_TIMEOUT_TICKS) {
            scanner = null; // Befehl hat kein Menü geöffnet
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Sitzung
    // ═══════════════════════════════════════════════════════════

    private void startSession(MenuScanner newScanner) {
        if (scanner != null && scanner != newScanner) flushPending(); // anderes Menü: altes erst senden
        scanner = newScanner;
        sessionStartTick = tick;
        containerSeen = false;
        lastHash = 0;
    }

    private void endSession() {
        scanner = null;
        containerSeen = false;
        flushPending();
    }

    /** Sendet alle gesammelten Aufgaben (asynchron) – auch beim Ausloggen aufgerufen. */
    public void flushPending() {
        if (pending.isEmpty()) return;
        List<TaskEntry> entries = new ArrayList<>(pending.values());
        String menu = pendingMenu;
        pending.clear();
        if (!api.isLinked()) return; // ohne Verknüpfung nichts senden
        api.syncAsync(menu, entries, true);
    }

    // ═══════════════════════════════════════════════════════════
    //  Auslesen
    // ═══════════════════════════════════════════════════════════

    private void inspect(GenericContainerScreen container) {
        String title = container.getTitle().getString();
        GenericContainerScreenHandler handler = container.getScreenHandler();

        int size = Math.min(handler.getRows() * 9, handler.slots.size());
        List<MenuScanner.SlotData> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            slots.add(new MenuScanner.SlotData(
                    i,
                    stack.getName().getString(),
                    readLore(stack),
                    Registries.ITEM.getId(stack.getItem()).getPath()));
        }

        // Unverändert seit dem letzten Mal? Dann nichts tun.
        int hash = Objects.hash(title, slots);
        if (hash == lastHash) return;
        lastHash = hash;

        List<TaskEntry> entries = scanner.scan(title, slots);
        lastDump = buildDump(title, slots, entries);
        lastSummary = entries == null
                ? "Titel \"" + title + "\": Seite ignoriert (Übersicht / keine Kategorie erkannt)."
                : "Titel \"" + title + "\": " + entries.size() + " Aufgaben erkannt.";
        if (entries == null) return;

        pendingMenu = scanner.menuId();
        for (TaskEntry entry : entries) {
            TaskEntry fixed = withKnownGoal(entry);
            pending.put(fixed.key(), fixed);
            store.applyScan(fixed, BlockCounter.resolveBlocks(fixed));
        }
    }

    /**
     * Erledigte Aufgaben liefern in der Lore manchmal nur "abgeschlossen"
     * ohne Zielwert (dann Ziel = 1). Ist das echte Ziel schon aus einem
     * früheren Scan bekannt, wird es wieder eingesetzt – sonst würde das
     * Ziel im Katalog fälschlich auf 1 gesetzt.
     */
    private TaskEntry withKnownGoal(TaskEntry entry) {
        if (!entry.done() || entry.goal() != 1) return entry;
        Long known = store.knownGoal(entry.key());
        return known != null && known > 1 ? entry.withGoal(known) : entry;
    }

    private static List<String> readLore(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (Text line : lore.lines()) lines.add(line.getString());
        return lines;
    }

    // ═══════════════════════════════════════════════════════════
    //  Debug
    // ═══════════════════════════════════════════════════════════

    /** Kurzfassung der zuletzt gelesenen Seite (leer, wenn noch nichts gelesen wurde). */
    public String lastSummary() {
        return lastSummary;
    }

    /** Vollständiger Text-Dump der zuletzt gelesenen Seite (leer, wenn noch nichts gelesen wurde). */
    public String lastDump() {
        return lastDump;
    }

    private static String buildDump(String title, List<MenuScanner.SlotData> slots, List<TaskEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("OPAPP Debug – ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")))
                .append(" – Mod ").append(OpappMod.VERSION).append('\n');
        sb.append("Fenstertitel: \"").append(title).append("\"\n");
        sb.append("Ergebnis: ").append(entries == null
                ? "Seite ignoriert (Übersicht oder keine Kategorie erkannt)"
                : entries.size() + " Aufgaben erkannt").append('\n');
        sb.append("Belegte Slots: ").append(slots.size()).append("\n\n");

        for (MenuScanner.SlotData slot : slots) {
            sb.append('[').append(slot.index()).append("] ").append(slot.name())
                    .append("  (Item: ").append(slot.item()).append(")\n");
            for (String line : slot.lore()) sb.append("      | ").append(line).append('\n');

            TaskEntry match = null;
            if (entries != null) {
                for (TaskEntry e : entries) {
                    if (e.slot() == slot.index()) {
                        match = e;
                        break;
                    }
                }
            }
            if (match != null) {
                sb.append("      → Aufgabe: ").append(match.category()).append(" / ").append(match.name())
                        .append("  ").append(match.progress()).append('/').append(match.goal())
                        .append(match.done() ? "  (erledigt)" : "  (offen)")
                        .append("  Blöcke: ").append(BlockCounter.resolveBlocks(match)).append('\n');
            } else {
                sb.append("      → ignoriert\n");
            }
        }
        return sb.toString();
    }
}
