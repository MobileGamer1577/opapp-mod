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
//  fertig ausgelesene Texte und liefern Aufgaben zurück.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan;

import java.util.List;
import java.util.Set;

public interface MenuScanner {

    /** Kennung des Menüs, wie sie der Worker erwartet ("erfolge"). */
    String menuId();

    /** Befehle (ohne "/"), die dieses Menü öffnen, z.B. "erfolge", "a". */
    Set<String> commands();

    /** true, wenn ein Fenstertitel zu diesem Menü gehört (Auto-Start der Sitzung). */
    boolean matchesTitle(String title);

    /**
     * Liest die Aufgaben einer Menü-Seite.
     *
     * @return Aufgaben; eine leere Liste, wenn die Seite keine enthält;
     *         {@code null}, wenn die Seite bewusst ignoriert wird
     *         (z.B. die Kategorien-Übersicht)
     */
    List<TaskEntry> scan(String title, List<SlotData> slots);

    /**
     * Ein belegter Slot des offenen Menüs, bereits in reinen Text umgewandelt.
     *
     * @param index Slot-Nummer im Menü (0 = oben links)
     * @param name  Anzeigename des Items (ohne Farbcodes)
     * @param lore  Lore-Zeilen (ohne Farbcodes), leer wenn keine
     * @param item  Item-ID ohne Namespace, z.B. "coal_ore"
     */
    record SlotData(int index, String name, List<String> lore, String item) {}
}
