// ═══════════════════════════════════════════════════════════════
//  ScreenWatcher.java – liest geöffnete OPSUCHT-Menüs passiv aus
//
//  ✅ HIER ÄNDERN: Zeitkonstanten unten (Prüf-Intervall, Wartezeiten)
//  ❌ NICHT ÄNDERN: Es wird NIE geklickt und kein Paket gesendet – die
//                   Mod liest nur, was der Server ohnehin schon geschickt
//                   hat (und beobachtet, worauf DU klickst). Kein Zugriff
//                   aus dem Render-Thread (nur Tick bzw. Klick-Event).
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan;

import de.opapp.OpappMod;
import de.opapp.config.OpappConfig;
import de.opapp.net.OpappApi;
import de.opapp.track.BlockCounter;
import de.opapp.track.ProgressStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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

    /** Wie viele zuletzt gelesene Seiten das Debug-Protokoll behält. */
    private static final int MAX_DUMPS = 8;

    /** Brücke für den HandledScreenMixin (statisch, weil Mixins keine Instanz kennen). */
    private static volatile ScreenWatcher instance;

    private final OpappConfig config;
    private final OpappApi api;
    private final ProgressStore store;

    private MenuScanner scanner;          // null = keine Sitzung
    private long tick;
    private long sessionStartTick;
    private long lastContainerTick;
    private boolean containerSeen;
    private int lastHash;

    private boolean leftMouseDown;
    private boolean rightMouseDown;

    // ─ Kategorie / Seite ─
    private String category;              // null = (noch) unbekannt
    private int page;                     // 0 = erste Seite
    private String pendingCategory;       // angeklickt, Seite noch nicht erschienen
    private int pendingPageDelta;         // +1 / -1 nach Klick auf Weiter/Zurück
    private boolean warnedNoCategory;
    private int clicksSeen;               // zeigt in /opapp debug, ob Klicks erkannt werden
    private String lastClickInfo = "keiner";

    /** Während der Sitzung gesammelte Aufgaben (Schlüssel → Aufgabe). */
    private final Map<String, TaskEntry> pending = new LinkedHashMap<>();
    private String pendingMenu = "";

    private final Deque<String> dumps = new ArrayDeque<>();
    private String lastSummary = "";

    public ScreenWatcher(OpappApi api, ProgressStore store, OpappConfig config) {
        this.api = api;
        this.store = store;
        this.config = config;
    }

    public void register() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientSendMessageEvents.COMMAND.register(this::onCommand);
    }

    // ═══════════════════════════════════════════════════════════
    //  Events
    // ═══════════════════════════════════════════════════════════

    /** Befehl ohne führenden "/" – z.B. "erfolge" oder "a". */
    private void onCommand(String command) {
        String root = command.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        MenuScanner found = MenuScannerRegistry.findByCommand(root);
        if (found != null) startSession(found);
    }

    private void onTick(MinecraftClient client) {
        tick++;

        Screen screen = client.currentScreen;
        GenericContainerScreen container = screen instanceof GenericContainerScreen g ? g : null;

        // ─ Keine Sitzung: höchstens gelegentlich den Titel prüfen ─
        if (scanner == null) {
            leftMouseDown = false;
            rightMouseDown = false;
            if (container != null && tick % AUTOSTART_CHECK_TICKS == 0) {
                MenuScanner found = MenuScannerRegistry
                        .findByTitle(TextClean.clean(container.getTitle().getString()));
                if (found != null) startSession(found);
            }
            return;
        }

        // ─ Sitzung läuft ─
        if (container != null) {
            containerSeen = true;
            lastContainerTick = tick;
            if (tick % INSPECT_EVERY_TICKS == 0) inspect(container);
            pollMouseClicks(client, container);
        } else if (containerSeen) {
            leftMouseDown = false;
            rightMouseDown = false;
            if (tick - lastContainerTick > SESSION_GRACE_TICKS) endSession();
        } else if (tick - sessionStartTick > SESSION_START_TIMEOUT_TICKS) {
            leftMouseDown = false;
            rightMouseDown = false;
            scanner = null; // Befehl hat kein Menü geöffnet
        }
    }

    private void pollMouseClicks(MinecraftClient client, GenericContainerScreen container) {
        long handle = client.getWindow().getHandle();
        boolean leftNow = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightNow = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if ((leftNow && !leftMouseDown) || (rightNow && !rightMouseDown)) {
            clicksSeen++;
            Slot hovered = findHoveredSlot(client, container);
            if (hovered != null && hovered.hasStack()) {
                lastClickInfo = "Slot [" + hovered.id + "] ("
                        + TextClean.clean(hovered.getStack().getName().getString()) + ")";
                handleClick(hovered.id, hovered.getStack());
            } else {
                double scale = client.getWindow().getScaleFactor();
                int mx = (int) Math.round(client.mouse.getX() / scale);
                int my = (int) Math.round(client.mouse.getY() / scale);
                lastClickInfo = "kein Slot bei (" + mx + "," + my + ") / Screen("
                        + container.width + "x" + container.height + ")";
            }
        }
        leftMouseDown = leftNow;
        rightMouseDown = rightNow;
    }

    private static Slot findHoveredSlot(MinecraftClient client, GenericContainerScreen container) {
        GenericContainerScreenHandler handler = container.getScreenHandler();
        if (handler == null) return null;

        double scale = client.getWindow().getScaleFactor();
        double mouseX = client.mouse.getX() / scale;
        double mouseY = client.mouse.getY() / scale;

        int rows = handler.getRows();
        int backgroundWidth = 176;
        int backgroundHeight = 114 + rows * 18;
        int x = (container.width - backgroundWidth) / 2;
        int y = (container.height - backgroundHeight) / 2;

        for (Slot slot : handler.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            if (mouseX >= sx - 4 && mouseX <= sx + 20 && mouseY >= sy - 4 && mouseY <= sy + 20) {
                return slot;
            }
        }
        return null;
    }

    /** Wird bei einem Klick auf einen Slot aufgerufen (Client-Thread). */
    public static void onSlotClicked(int slotId, ItemStack stack) {
        ScreenWatcher watcher = instance;
        if (watcher != null) watcher.handleClick(slotId, stack);
    }

    private void handleClick(int slotId, ItemStack stack) {
        if (scanner == null || stack == null || stack.isEmpty()) return;

        Screen screen = MinecraftClient.getInstance().currentScreen;
        if (!(screen instanceof GenericContainerScreen container)) return;

        // Nur Slots des Menüs, nicht die des Spieler-Inventars darunter.
        int menuSlots = container.getScreenHandler().getRows() * 9;
        if (slotId < 0 || slotId >= menuSlots) return;

        MenuScanner.ClickAction action = scanner.classifyClick(toSlotData(slotId, stack));
        switch (action.kind()) {
            case OPEN_CATEGORY -> {
                pendingCategory = action.category();
                pendingPageDelta = 0;
            }
            case NEXT_PAGE -> pendingPageDelta = 1;
            case PREV_PAGE -> pendingPageDelta = -1;
            default -> { }
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
        resetNavigation();
    }

    private void endSession() {
        scanner = null;
        containerSeen = false;
        flushPending();
        resetNavigation();
    }

    private void resetNavigation() {
        category = null;
        page = 0;
        pendingCategory = null;
        pendingPageDelta = 0;
        warnedNoCategory = false;
    }

    /**
     * Sendet alle gesammelten Aufgaben (asynchron) – beim Schließen des
     * Menüs und beim Ausloggen. Ist nichts gesammelt (z.B. nur die
     * Übersicht gesehen), passiert bewusst NICHTS: kein Request, keine
     * "0 Aufgaben"-Meldung.
     */
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
        String rawTitle = container.getTitle().getString();
        String title = TextClean.clean(rawTitle);
        List<MenuScanner.SlotData> slots = readSlots(container.getScreenHandler());

        // Unverändert seit dem letzten Mal? Dann nichts tun.
        int hash = Objects.hash(title, slots);
        if (hash == lastHash) return;
        lastHash = hash;

        MenuScanner.PageKind kind = scanner.classify(slots);
        List<TaskEntry> entries = null;
        String note;

        switch (kind) {
            case OVERVIEW -> {
                category = null;
                page = 0;
                pendingPageDelta = 0;
                note = "Kategorien-Übersicht (wird nicht gesendet)";
            }
            case TASKS -> {
                applyNavigation();
                if (category == null) {
                    note = "Aufgaben-Seite, aber Kategorie unbekannt – NICHT gesammelt";
                    warnNoCategory();
                } else {
                    entries = scanner.scanTasks(category, page, slots);
                    collect(entries);
                    note = "Kategorie \"" + category + "\", Seite " + (page + 1) + ": "
                            + entries.size() + " Aufgaben erkannt";
                }
            }
            default -> note = "keine Aufgaben erkannt (leer oder Ladezustand)";
        }

        lastSummary = "Titel \"" + title + "\": " + note;
        addDump(buildDump(rawTitle, title, slots, kind, entries, note));
        if (config.debugLive) writeDebugFileAsync();
    }

    /** Übernimmt Klick-Informationen, sobald die zugehörige Aufgaben-Seite erscheint. */
    private void applyNavigation() {
        if (pendingCategory != null) {
            category = pendingCategory;
            page = 0;
            pendingCategory = null;
            pendingPageDelta = 0;
        } else if (pendingPageDelta != 0) {
            page = Math.max(0, page + pendingPageDelta);
            pendingPageDelta = 0;
        }
    }

    private void collect(List<TaskEntry> entries) {
        pendingMenu = scanner.menuId();
        for (TaskEntry entry : entries) {
            TaskEntry fixed = withKnownGoal(entry);
            pending.put(fixed.key(), fixed);
            store.applyScan(fixed, BlockCounter.resolveBlocks(fixed));
        }
    }

    private void warnNoCategory() {
        if (warnedNoCategory) return;
        warnedNoCategory = true;
        OpappMod.chat("Kategorie nicht erkannt – diese Seite wird nicht gesendet. Öffne die Kategorie "
                + "über das Erfolge-Hauptmenü (/erfolge → Kategorie anklicken).", Formatting.YELLOW);
    }

    private TaskEntry withKnownGoal(TaskEntry entry) {
        if (!entry.done() || entry.goal() != 1) return entry;
        Long known = store.knownGoal(entry.key());
        return known != null && known > 1 ? entry.withGoal(known) : entry;
    }

    private static List<MenuScanner.SlotData> readSlots(GenericContainerScreenHandler handler) {
        int size = Math.min(handler.getRows() * 9, handler.slots.size());
        List<MenuScanner.SlotData> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            slots.add(toSlotData(i, stack));
        }
        return slots;
    }

    private static MenuScanner.SlotData toSlotData(int index, ItemStack stack) {
        return new MenuScanner.SlotData(
                index,
                TextClean.clean(stack.getName().getString()),
                readLore(stack),
                Registries.ITEM.getId(stack.getItem()).getPath());
    }

    private static List<String> readLore(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (Text line : lore.lines()) lines.add(TextClean.clean(line.getString()));
        return lines;
    }

    // ═══════════════════════════════════════════════════════════
    //  Debug
    // ═══════════════════════════════════════════════════════════

    public boolean hasDebugData() {
        return !dumps.isEmpty();
    }

    public String lastSummary() {
        return lastSummary;
    }

    public String stateSummary() {
        return "Sitzung: " + (scanner != null ? "aktiv" : "aus")
                + " · Kategorie: " + (category != null ? category + " (Seite " + (page + 1) + ")" : "unbekannt")
                + " · gesammelt: " + pending.size()
                + " · erkannt gesamt: " + store.size()
                + " · Klicks erkannt: " + clicksSeen
                + " · letzter Klick: " + lastClickInfo;
    }

    public String debugReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("OPAPP Debug – ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")))
                .append(" – Mod ").append(OpappMod.VERSION).append('\n');
        sb.append("Verbunden: ").append(api.isLinked() ? "ja" : "nein").append('\n');
        sb.append(stateSummary()).append('\n');
        sb.append("Seiten im Protokoll: ").append(dumps.size()).append(" (max. ").append(MAX_DUMPS).append(")\n");
        for (String dump : dumps) {
            sb.append("\n════════════════════════════════════════\n").append(dump);
        }
        return sb.toString();
    }

    public Path writeDebugFile() throws IOException {
        Path file = debugFile();
        Files.writeString(file, debugReport(), StandardCharsets.UTF_8);
        return file;
    }

    private void writeDebugFileAsync() {
        String report = debugReport();
        Path file = debugFile();
        CompletableFuture.runAsync(() -> {
            try {
                Files.writeString(file, report, StandardCharsets.UTF_8);
            } catch (IOException e) {
                OpappMod.LOG.warn("Live-Debug-Datei konnte nicht geschrieben werden: {}", e.toString());
            }
        });
    }

    private static Path debugFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("opapp-debug.txt");
    }

    private void addDump(String dump) {
        dumps.addLast(dump);
        while (dumps.size() > MAX_DUMPS) dumps.removeFirst();
    }

    private String buildDump(String rawTitle, String title, List<MenuScanner.SlotData> slots,
                             MenuScanner.PageKind kind, List<TaskEntry> entries, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("Zeit: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append('\n');
        sb.append("Fenstertitel (bereinigt): \"").append(title).append("\"\n");
        sb.append("Fenstertitel (roh, Unicode): ").append(TextClean.codePoints(rawTitle)).append('\n');
        sb.append("Seitentyp: ").append(kind).append('\n');
        sb.append("Kategorie: ").append(category != null ? category : "(unbekannt)")
                .append(" · Seite ").append(page + 1).append('\n');
        sb.append("Ergebnis: ").append(note).append('\n');
        sb.append("Belegte Slots: ").append(slots.size()).append("\n\n");

        for (MenuScanner.SlotData slot : slots) {
            sb.append('[').append(slot.index()).append("] ").append(slot.name())
                    .append("  (Item: ").append(slot.item()).append(")\n");
            for (String line : slot.lore()) sb.append("      | ").append(line).append('\n');

            TaskEntry match = null;
            if (entries != null) {
                int order = MenuScanner.sortOrder(page, slot.index());
                for (TaskEntry e : entries) {
                    if (e.slot() == order && e.name().equalsIgnoreCase(slot.name().replaceAll("^[^\\p{L}\\p{N}]+", ""))) {
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