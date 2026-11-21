// ═══════════════════════════════════════════════════════════════
//  ProgressStore.java – erkannte Aufgaben + lokale Zählerstände
//
//  ✅ HIER ÄNDERN: nichts Spezielles nötig
//  ❌ NICHT ÄNDERN: Semantik von applyScan() (Scan = Server-Wahrheit,
//                   überschreibt den lokalen Zähler immer)
//
//  Liegt nur im Arbeitsspeicher (kein Schreiben auf die Festplatte).
//  Nach einem Neustart ist der Speicher leer, bis /erfolge wieder
//  geöffnet wurde – der Scan stellt den Stand dann wieder her.
//
//  THREADS: Zugriff kommt vom Client-Thread und aus den Callbacks des
//  HttpClients (markDirty bei Fehlern) → alle Methoden synchronized.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
// ═══════════════════════════════════════════════════════════════

package de.opapp.track;

import de.opapp.scan.TaskEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProgressStore {

    private static final class Tracked {
        TaskEntry entry;
        Set<String> blocks = Set.of();
        boolean dirty;
    }

    private final Map<String, Tracked> tracked = new HashMap<>();

    /** Block-ID → betroffene Aufgaben. Wird bei Bedarf neu aufgebaut. */
    private Map<String, List<Tracked>> index = new HashMap<>();
    private boolean indexDirty = true;

    /** Übernimmt einen gescannten Stand (überschreibt lokale Zähler). */
    public synchronized void applyScan(TaskEntry entry, Set<String> blocks) {
        Tracked t = tracked.computeIfAbsent(entry.key(), k -> new Tracked());
        t.entry = entry;
        t.blocks = blocks;
        t.dirty = false;
        indexDirty = true;
    }

    /** Zuletzt bekannter Zielwert einer Aufgabe oder null. */
    public synchronized Long knownGoal(String key) {
        Tracked t = tracked.get(key);
        return t == null ? null : t.entry.goal();
    }

    /**
     * Zählt einen abgebauten Block bei allen passenden, noch offenen
     * Aufgaben hoch.
     *
     * @return true, wenn dabei mindestens eine Aufgabe ihr Ziel erreicht hat
     */
    public synchronized boolean onBlockBroken(String blockId) {
        if (tracked.isEmpty()) return false;
        if (indexDirty) rebuildIndex();

        List<Tracked> hits = index.get(blockId);
        if (hits == null) return false;

        boolean reached = false;
        for (Tracked t : hits) {
            if (t.entry.done()) continue;
            long progress = t.entry.progress() + 1;
            boolean done = progress >= t.entry.goal();
            t.entry = t.entry.withProgress(Math.min(progress, t.entry.goal()), done);
            t.dirty = true;
            if (done) reached = true;
        }
        return reached;
    }

    /** Alle geänderten Aufgaben abholen (und als "gesendet" markieren). */
    public synchronized List<TaskEntry> takeDirty() {
        List<TaskEntry> result = new ArrayList<>();
        for (Tracked t : tracked.values()) {
            if (t.dirty) {
                result.add(t.entry);
                t.dirty = false;
            }
        }
        return result;
    }

    /** Nach fehlgeschlagenem Senden erneut als "ungesendet" markieren. */
    public synchronized void markDirty(List<TaskEntry> entries) {
        for (TaskEntry e : entries) {
            Tracked t = tracked.get(e.key());
            if (t != null) t.dirty = true;
        }
    }

    public synchronized boolean hasDirty() {
        for (Tracked t : tracked.values()) {
            if (t.dirty) return true;
        }
        return false;
    }

    public synchronized int size() {
        return tracked.size();
    }

    private void rebuildIndex() {
        Map<String, List<Tracked>> next = new HashMap<>();
        for (Tracked t : tracked.values()) {
            for (String block : t.blocks) {
                next.computeIfAbsent(block, b -> new ArrayList<>()).add(t);
            }
        }
        index = next;
        indexDirty = false;
    }
}
