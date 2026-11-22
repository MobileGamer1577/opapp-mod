// ═══════════════════════════════════════════════════════════════
//  MenuScanner.java – Schnittstelle für OPSUCHT-Menü-Scanner
//
//  ✅ HIER ÄNDERN: nichts – für ein neues Menü eine neue Klasse
//                  schreiben, die dieses Interface umsetzt
//  ❌ NICHT ÄNDERN: Methodennamen (ScreenWatcher und Registry nutzen sie)
//
//  NEUES MENÜ ANDOCKEN (z.B. /jobs):
//    1. Klasse JobsScanner implements MenuScanner anlegen
//       (menuId() = "jobs" – muss zum Worker passen, siehe MENU_HANDLERS
//       in src/achievements.js)
//    2. In MenuScannerRegistry.SCANNERS eintragen
//  Fertig – Inventar-Auslesen, Sitzungs-Logik und Senden übernimmt der
//  ScreenWatcher für alle Scanner gleich.
//
//  Scanner sind reine Funktionen ohne Minecraft-Klassen: Sie bekommen
//  fertig ausgelesene (und von Glyphen bereinigte) Texte und liefern
//  Aufgaben zurück.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
//
//  ÄNDERUNGEN (Fix 1.0.1):
//    - scan(title, slots) ersetzt durch classify() + scanTasks(): Der
//      Fenstertitel von OPSUCHT besteht nur aus Font-Glyphen und enthält
//      KEINEN Kategorienamen. Die Kategorie kommt jetzt vom angeklickten
//      Menüeintrag (classifyClick), der ScreenWatcher reicht sie weiter.
//    - NEU: PageKind (Übersicht / Aufgaben / leer), ClickAction
//      (Kategorie öffnen, nächste/vorherige Seite) und sortOrder().
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan;

import java.util.List;
import java.util.Set;

public interface MenuScanner {

    /** Was für eine Seite gerade offen ist. */
    enum PageKind {
        /** nichts Verwertbares (leer, Ladezustand, fremdes Menü) */
        EMPTY,
        /** Kategorien-Übersicht ("Miner: 22 von 43 abgeschlossen") – wird nicht gesendet */
        OVERVIEW,
        /** Seite mit einzelnen Aufgaben */
        TASKS
    }

    /** Was ein Klick auf einen Menüeintrag bedeutet. */
    enum ClickKind { NONE, OPEN_CATEGORY, NEXT_PAGE, PREV_PAGE }

    /**
     * @param kind     Art des Klicks
     * @param category nur bei OPEN_CATEGORY: Name der Kategorie, sonst null
     */
    record ClickAction(ClickKind kind, String category) {
        public static final ClickAction NONE = new ClickAction(ClickKind.NONE, null);
    }

    /** Kennung des Menüs, wie sie der Worker erwartet ("erfolge"). */
    String menuId();

    /** Befehle (ohne "/"), die dieses Menü öffnen, z.B. "erfolge", "a". */
    Set<String> commands();

    /** true, wenn ein (bereinigter) Fenstertitel zu diesem Menü gehört. */
    boolean matchesTitle(String title);

    /** Ordnet die sichtbare Seite ein (Übersicht / Aufgaben / leer). */
    PageKind classify(List<SlotData> slots);

    /**
     * Liest die Aufgaben einer Aufgaben-Seite.
     *
     * @param category Kategorie (vom ScreenWatcher aus dem Klick abgeleitet)
     * @param page     Seitennummer innerhalb der Kategorie (0 = erste Seite)
     */
    List<TaskEntry> scanTasks(String category, int page, List<SlotData> slots);

    /** Bewertet einen Klick auf einen Slot (Kategorie öffnen / blättern / egal). */
    ClickAction classifyClick(SlotData slot);

    /**
     * Sortier-Nummer einer Aufgabe über alle Seiten einer Kategorie:
     * Seite × 60 + Slot (ein Menü hat höchstens 54 Slots). Der Worker
     * speichert sie als sort_order (erlaubt: 0–255).
     */
    static int sortOrder(int page, int slotIndex) {
        return Math.min(255, Math.max(0, page) * 60 + slotIndex);
    }

    /**
     * Ein belegter Slot des offenen Menüs, bereits in reinen Text umgewandelt.
     *
     * @param index Slot-Nummer im Menü (0 = oben links)
     * @param name  Anzeigename des Items (bereinigt)
     * @param lore  Lore-Zeilen (bereinigt), leer wenn keine
     * @param item  Item-ID ohne Namespace, z.B. "coal_ore"
     */
    record SlotData(int index, String name, List<String> lore, String item) {}
}
