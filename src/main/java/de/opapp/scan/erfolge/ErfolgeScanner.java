// ═══════════════════════════════════════════════════════════════
//  ErfolgeScanner.java – Scanner für /erfolge (und /a)
//
//  ✅ HIER ÄNDERN: COMMANDS (weitere Aliase), NAVIGATION / NEXT / PREV,
//                  falls OPSUCHT die Buttons anders benennt
//  ❌ NICHT ÄNDERN: classify() zählt nur "strong"-Treffer des
//                   TaskLoreParser (sonst werden Blätter-Buttons mit
//                   "Seite 1/3" fälschlich zu Aufgaben)
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan.erfolge;

import de.opapp.scan.MenuScanner;
import de.opapp.scan.TaskEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ErfolgeScanner implements MenuScanner {

    /** ✅ HIER ÄNDERN: Befehle (ohne "/"), die das Erfolge-Menü öffnen. */
    private static final Set<String> COMMANDS = Set.of("erfolge", "erfolg", "a", "achievements");

    /** Buttons, die keine Aufgaben und keine Kategorien sind. */
    private static final Pattern NAVIGATION = Pattern.compile(
            "(?i)^(seite.*|zurück|zurueck|weiter|nächste seite|naechste seite|vorherige seite"
                    + "|schließen|schliessen|back|next page|previous page|close)$");

    /** Teilmenge von NAVIGATION: vorwärts blättern. */
    private static final Pattern NEXT = Pattern.compile(
            "(?i)^(weiter|nächste seite|naechste seite|next page)$");

    /** Teilmenge von NAVIGATION: zurück blättern. */
    private static final Pattern PREV = Pattern.compile(
            "(?i)^(zurück|zurueck|vorherige seite|back|previous page)$");

    @Override
    public String menuId() {
        return "erfolge";
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public boolean matchesTitle(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        return t.contains("erfolg") || t.contains("achievement");
    }

    private static boolean isTrophySlot(SlotData slot) {
        String lowerName = cleanName(slot.name()).toLowerCase(Locale.ROOT);
        if (lowerName.startsWith("trophäe ") || lowerName.startsWith("trophaee ")) {
            return true;
        }
        for (String line : slot.lore()) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("trophäe noch nicht erreicht") || l.startsWith("benötigt:")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PageKind classify(List<SlotData> slots) {
        int areas = 0;
        int tasks = 0;
        for (SlotData slot : slots) {
            if (slot.lore().isEmpty() || isNavigation(slot.name()) || isTrophySlot(slot)) continue;
            TaskLoreParser.Reading r = TaskLoreParser.read(slot.lore());
            if (r == null || !r.strong()) continue;
            if (r.area()) areas++;
            else tasks++;
        }
        if (tasks > 0) return PageKind.TASKS;
        if (areas > 0) return PageKind.OVERVIEW;
        return PageKind.EMPTY;
    }

    @Override
    public List<TaskEntry> scanTasks(String category, int page, List<SlotData> slots) {
        List<TaskEntry> entries = new ArrayList<>();
        for (SlotData slot : slots) {
            if (slot.lore().isEmpty() || isTrophySlot(slot)) continue;

            String name = cleanName(slot.name());
            if (name.isEmpty() || NAVIGATION.matcher(name).matches()) continue;

            TaskLoreParser.Reading reading = TaskLoreParser.read(slot.lore());
            if (reading == null || reading.area()) continue;

            long goal = reading.goal();
            boolean done = reading.finished();
            long progress = done ? goal : Math.min(reading.value(), goal);
            entries.add(new TaskEntry(
                    category, name, goal, progress, done, slot.item(),
                    MenuScanner.sortOrder(page, slot.index()),
                    TaskLoreParser.targets(slot.lore())));
        }
        return entries;
    }

    @Override
    public ClickAction classifyClick(SlotData slot) {
        String name = cleanName(slot.name());
        if (name.isEmpty()) return ClickAction.NONE;

        if (NAVIGATION.matcher(name).matches()) {
            if (NEXT.matcher(name).matches()) return new ClickAction(ClickKind.NEXT_PAGE, null);
            if (PREV.matcher(name).matches()) return new ClickAction(ClickKind.PREV_PAGE, null);
            return ClickAction.NONE;
        }

        if (name.equalsIgnoreCase("aufgaben")) {
            return ClickAction.NONE;
        }

        TaskLoreParser.Reading r = TaskLoreParser.read(slot.lore());
        if (r != null && r.area() && r.strong()) {
            return new ClickAction(ClickKind.OPEN_CATEGORY, name);
        }
        return ClickAction.NONE;
    }

    private static boolean isNavigation(String rawName) {
        return NAVIGATION.matcher(cleanName(rawName)).matches();
    }

    /** Entfernt führende Symbole (z.B. Haken, Pfeile) und überflüssige Leerzeichen. */
    private static String cleanName(String raw) {
        String t = raw == null ? "" : raw.replaceAll("^[^\\p{L}\\p{N}]+", "");
        return t.replaceAll("\\s+", " ").trim();
    }
}