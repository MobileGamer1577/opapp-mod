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
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan;

import java.util.Locale;

/**
 * @param category Kategorie (aus dem Fenstertitel), z.B. "Miner"
 * @param name     Aufgabenname, z.B. "Kohlenoob"
 * @param goal     Zielwert (&gt; 0)
 * @param progress aktueller Stand (0 … goal)
 * @param done     abgeschlossen?
 * @param icon     Item-ID des Menü-Icons ohne Namespace ("coal_ore") oder null
 * @param slot     Position im Menü (Reihenfolge wie im Spiel)
 */
public record TaskEntry(
        String category,
        String name,
        long goal,
        long progress,
        boolean done,
        String icon,
        int slot) {

    /** Stabiler Schlüssel (Groß-/Kleinschreibung egal). */
    public String key() {
        return (category + "|" + name).toLowerCase(Locale.ROOT);
    }

    public TaskEntry withProgress(long newProgress, boolean newDone) {
        return new TaskEntry(category, name, goal, newProgress, newDone, icon, slot);
    }

    /** Neues Ziel; bei erledigten Aufgaben wird der Stand auf das Ziel gesetzt. */
    public TaskEntry withGoal(long newGoal) {
        long newProgress = done ? newGoal : Math.min(progress, newGoal);
        return new TaskEntry(category, name, newGoal, newProgress, done, icon, slot);
    }
}
