// ═══════════════════════════════════════════════════════════════
//  TaskEntry.java – eine aus einem Menü gelesene Aufgabe
//
//  ✅ HIER ÄNDERN: Felder ergänzen (dann auch OpappApi.toJson und den
//                  Worker in src/achievements.js anpassen)
//  ❌ NICHT ÄNDERN: key() – darüber werden Aufgaben wiedererkannt
//
//  Unveränderlich (record). Enthält bewusst keine Minecraft-Klassen,
//  damit Scanner und Parser ohne Spiel testbar sind.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
//
//  ÄNDERUNGEN (Fix 1.0.1):
//    - NEU: targets – die in der Lore unter "Blöcke abbauen:" gelisteten
//      Namen (z.B. "Lapislazulierz"). Nur lokal für den Hintergrund-
//      Zähler, wird NICHT an den Worker gesendet.
//    - slot ist jetzt die Sortier-Nummer (Seite × 60 + Slot, siehe
//      MenuScanner.sortOrder), damit Aufgaben mehrerer Seiten in der
//      richtigen Reihenfolge stehen.
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan;

import java.util.List;
import java.util.Locale;

/**
 * @param category Kategorie (Name des angeklickten Menüeintrags), z.B. "Miner"
 * @param name     Aufgabenname, z.B. "Kohlenoob"
 * @param goal     Zielwert (&gt; 0)
 * @param progress aktueller Stand (0 … goal)
 * @param done     abgeschlossen?
 * @param icon     Item-ID des Menü-Icons ohne Namespace ("coal_ore") oder null
 * @param slot     Sortier-Nummer (Reihenfolge wie im Spiel, über alle Seiten)
 * @param targets  Ziel-Namen aus der Lore ("Blöcke abbauen: - Stein"), evtl. leer
 */
public record TaskEntry(
        String category,
        String name,
        long goal,
        long progress,
        boolean done,
        String icon,
        int slot,
        List<String> targets) {

    /** Stabiler Schlüssel (Groß-/Kleinschreibung egal). */
    public String key() {
        return (category + "|" + name).toLowerCase(Locale.ROOT);
    }

    public TaskEntry withProgress(long newProgress, boolean newDone) {
        return new TaskEntry(category, name, goal, newProgress, newDone, icon, slot, targets);
    }

    /** Neues Ziel; bei erledigten Aufgaben wird der Stand auf das Ziel gesetzt. */
    public TaskEntry withGoal(long newGoal) {
        long newProgress = done ? newGoal : Math.min(progress, newGoal);
        return new TaskEntry(category, name, newGoal, newProgress, done, icon, slot, targets);
    }
}
