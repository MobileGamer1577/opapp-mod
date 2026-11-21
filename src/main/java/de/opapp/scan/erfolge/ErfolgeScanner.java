// ═══════════════════════════════════════════════════════════════
//  ErfolgeScanner.java – Scanner für /erfolge (und /a)
//
//  ✅ HIER ÄNDERN: COMMANDS (weitere Aliase), IGNORED_CATEGORIES,
//                  TITLE_PREFIX / NAVIGATION, falls OPSUCHT die
//                  Fenstertitel oder Buttons anders benennt
//  ❌ NICHT ÄNDERN: die Reihenfolge in scan() (erst Seite prüfen, dann
//                   Slots lesen)
//
//  ABLAUF EINER SEITE:
//    1. Kategorie = Fenstertitel ohne Präfix ("Erfolge »"), ohne
//       Seitenzahl-Anhang "(1/2)". Bleibt nichts übrig oder ist es ein
//       Übersichts-Titel → Seite wird ignoriert (return null).
//    2. Jeder Slot mit Lore wird mit TaskLoreParser gelesen.
//       - area = true (Kategorie-Zusammenfassung)  → übersprungen
//       - Navigations-Buttons (Zurück, Seite …)    → übersprungen
//       - sonst → Aufgabe (Name, Ziel, Stand, Status, Icon, Slot)
//
//  Die genaue Titel-Schreibweise von OPSUCHT ist nicht dokumentiert.
//  Mit /opapp debug lässt sich prüfen, was erkannt wurde; Anpassungen
//  passieren dann nur in den Konstanten oben in dieser Datei.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
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

    /** ✅ HIER ÄNDERN: Titel (klein), die eine Übersicht statt einer Kategorie sind. */
    private static final Set<String> IGNORED_CATEGORIES = Set.of(
            "übersicht", "uebersicht", "hauptmenü", "hauptmenue", "menü", "menue",
            "kategorien", "alle", "alle erfolge");

    /** Präfix wie "Erfolge » ", "Achievements: " am Titelanfang. */
    private static final Pattern TITLE_PREFIX =
            Pattern.compile("(?i)^\\s*(erfolge|erfolg|achievements?)\\s*[»>:|\\-–—·•]*\\s*");

    /** Seitenzahl-Anhänge wie " (1/2)" oder " - Seite 2". */
    private static final Pattern TITLE_PAGE_A =
            Pattern.compile("\\s*[(\\[]\\s*\\d+\\s*/\\s*\\d+\\s*[)\\]]\\s*$");
    private static final Pattern TITLE_PAGE_B =
            Pattern.compile("(?i)\\s*[-–|:]?\\s*seite\\s*\\d+(\\s*/\\s*\\d+)?\\s*$");

    /** Buttons, die zwar "x / y" in der Lore haben können, aber keine Aufgaben sind. */
    private static final Pattern NAVIGATION = Pattern.compile(
            "(?i)^(seite.*|zurück|zurueck|weiter|nächste seite|naechste seite|vorherige seite"
                    + "|schließen|schliessen|back|next page|previous page|close)$");

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

    @Override
    public List<TaskEntry> scan(String title, List<SlotData> slots) {
        String category = categoryFromTitle(title);
        if (category.isEmpty() || IGNORED_CATEGORIES.contains(category.toLowerCase(Locale.ROOT))) {
            return null; // Übersicht/Hauptmenü – nicht auswerten
        }

        List<TaskEntry> entries = new ArrayList<>();
        for (SlotData slot : slots) {
            if (slot.lore().isEmpty()) continue;

            String name = cleanName(slot.name());
            if (name.isEmpty() || NAVIGATION.matcher(name).matches()) continue;

            TaskLoreParser.Reading reading = TaskLoreParser.read(slot.lore());
            if (reading == null || reading.area()) continue;

            long goal = reading.goal();
            boolean done = reading.finished();
            long progress = done ? goal : Math.min(reading.value(), goal);
            entries.add(new TaskEntry(category, name, goal, progress, done, slot.item(), slot.index()));
        }
        return entries;
    }

    /** "Erfolge » Miner (1/2)" → "Miner"; leer, wenn nur ein Präfix übrig bleibt. */
    static String categoryFromTitle(String title) {
        String t = title == null ? "" : title.trim();
        t = TITLE_PREFIX.matcher(t).replaceFirst("");
        t = TITLE_PAGE_A.matcher(t).replaceFirst("");
        t = TITLE_PAGE_B.matcher(t).replaceFirst("");
        // Reste von Symbolen/Trennzeichen an den Rändern entfernen.
        return t.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N})\\]]+$", "").trim();
    }

    /** Entfernt führende Symbole (z.B. Haken, Pfeile) und überflüssige Leerzeichen. */
    private static String cleanName(String raw) {
        String t = raw == null ? "" : raw.replaceAll("^[^\\p{L}\\p{N}]+", "");
        return t.replaceAll("\\s+", " ").trim();
    }
}
