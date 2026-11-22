// ═══════════════════════════════════════════════════════════════
//  TaskLoreParser.java – liest Fortschritt/Ziel/Status aus einer Lore
//
//  ✅ HIER ÄNDERN: Schlüsselwörter/Muster, falls OPSUCHT die Texte ändert
//  ❌ NICHT ÄNDERN: Reihenfolge der vier Regeln (spezifisch vor allgemein)
//
//  REGELN (in dieser Reihenfolge, erste Treffer-Regel gewinnt):
//    1. Zeile mit "Fortschritt" und "x / y"      → laufende Aufgabe
//    2. Zeile mit "abgeschlossen" und "N von M"  → Bereichs-/Kategorie-
//       Zusammenfassung (area = true) – KEINE einzelne Aufgabe
//    3. Zeile mit "Achievement abgeschlossen"    → erledigt, Ziel unbekannt
//    4. irgendeine Zeile mit "x / y"             → Rückfall
//  Tausendertrenner (Punkt/Komma) werden entfernt: "1.250" → 1250.
//
//  BEISPIELE AUS DEM SPIEL:
//    offen:   "Fortschritt: 660.675/1.000.000" + "Schließe dieses
//             Achievement ab, um 12.500 XP zu erhalten"
//    fertig:  "Fortschritt: 1.000/1.000" + "Achievement abgeschlossen
//             - 100 XP erhalten"
//    Bereich: "22 von 43 abgeschlossen"
//
//  Eigene Umsetzung der Logik des QuestLore-Beispiels (Preiswächter),
//  bewusst nicht 1:1 aus dem dekompilierten Code kopiert.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
//
//  ÄNDERUNGEN (Fix 1.0.1):
//    - Reading hat jetzt "strong": true bei Regel 1–3 (eindeutig), false
//      beim Rückfall (Regel 4). Die Seiten-Erkennung zählt nur "strong".
//    - NEU: targets() liest die Ziel-Namen unter einer Überschrift wie
//      "Blöcke abbauen:" ("- Lapislazulierz" …) für den Hintergrund-Zähler.
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan.erfolge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TaskLoreParser {

    private static final Pattern PROGRESS = Pattern.compile("(\\d[\\d.,]*)\\s*/\\s*(\\d[\\d.,]*)");
    private static final Pattern OF = Pattern.compile("(\\d+)\\s+von\\s+(\\d+)");

    private TaskLoreParser() {}

    /**
     * @param area      true = Bereichs-/Kategorie-Zusammenfassung, keine Aufgabe
     * @param goalKnown false, wenn nur "abgeschlossen" bekannt ist (Ziel dann 1)
     * @param strong    true, wenn eine eindeutige Regel (1–3) gegriffen hat
     * @param value     aktueller Stand
     * @param goal      Zielwert
     */
    public record Reading(boolean area, boolean goalKnown, boolean strong, long value, long goal) {
        public boolean finished() {
            return goal > 0 && value >= goal;
        }
    }

    /** @return Ergebnis oder null, wenn die Lore keine Fortschrittsangabe enthält */
    public static Reading read(List<String> lore) {
        if (lore == null || lore.isEmpty()) return null;

        // 1) "Fortschritt: 42 / 100"
        for (String line : lore) {
            if (!lower(line).contains("fortschritt")) continue;
            Reading r = progressReading(line, true);
            if (r != null) return r;
        }

        // 2) "3 von 20 abgeschlossen" → Bereich
        for (String line : lore) {
            String l = lower(line);
            if (!l.contains("abgeschlossen") || l.contains("achievement")) continue;
            Matcher m = OF.matcher(line);
            if (m.find()) {
                long value = plain(m.group(1));
                long goal = plain(m.group(2));
                if (goal > 0 && value >= 0) return new Reading(true, true, true, value, goal);
            }
        }

        // 3) "Achievement abgeschlossen"
        for (String line : lore) {
            if (lower(line).contains("achievement abgeschlossen")) {
                return new Reading(false, false, true, 1, 1);
            }
        }

        // 4) irgendeine "x / y"-Zeile (unsicher)
        for (String line : lore) {
            Reading r = progressReading(line, false);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * Ziel-Namen unter einer Überschrift, die auf ":" endet und keine Ziffer
     * enthält, z.B.
     * <pre>
     *   Blöcke abbauen:
     *   - Lapislazulierz
     *   - Tiefenschiefer-Lapislazulierz
     * </pre>
     * → ["Lapislazulierz", "Tiefenschiefer-Lapislazulierz"]. Leer, wenn keine
     * solche Liste vorkommt.
     */
    public static List<String> targets(List<String> lore) {
        List<String> result = new ArrayList<>();
        if (lore == null) return result;

        boolean collecting = false;
        for (String raw : lore) {
            String line = raw.trim();
            if (!collecting) {
                if (line.endsWith(":") && line.chars().noneMatch(Character::isDigit)) {
                    collecting = true;
                }
                continue;
            }
            if (line.startsWith("-")) {
                String item = line.substring(1).trim();
                if (!item.isEmpty()) result.add(item);
            } else if (!result.isEmpty()) {
                break; // Liste zu Ende (leere Zeile oder anderer Text)
            } else if (!line.isEmpty()) {
                collecting = false; // keine Liste, nach der nächsten Überschrift suchen
            }
        }
        return result;
    }

    private static Reading progressReading(String line, boolean strong) {
        Matcher m = PROGRESS.matcher(line);
        if (!m.find()) return null;
        long value = plain(m.group(1));
        long goal = plain(m.group(2));
        if (goal > 0 && value >= 0) return new Reading(false, true, strong, value, goal);
        return null;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** "1.250" / "1,250" → 1250; bei Fehlern -1. */
    static long plain(String s) {
        try {
            return Long.parseLong(s.replace(".", "").replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
