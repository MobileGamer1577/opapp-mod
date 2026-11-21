// ═══════════════════════════════════════════════════════════════
//  MenuScannerRegistry.java – Liste aller bekannten Menü-Scanner
//
//  ✅ HIER ÄNDERN: neue Scanner in SCANNERS eintragen
//  ❌ NICHT ÄNDERN: die beiden find…-Methoden
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei. Aktuell nur ErfolgeScanner (/erfolge, /a).
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan;

import de.opapp.scan.erfolge.ErfolgeScanner;

import java.util.List;
import java.util.Locale;

public final class MenuScannerRegistry {

    private static final List<MenuScanner> SCANNERS = List.of(
            new ErfolgeScanner()
            // ← Weitere Menüs hier ergänzen, z.B.:
            // new JobsScanner(),
    );

    private MenuScannerRegistry() {}

    /** Scanner für einen Chat-Befehl (ohne "/"), z.B. "erfolge" – oder null. */
    public static MenuScanner findByCommand(String commandRoot) {
        String root = commandRoot.toLowerCase(Locale.ROOT);
        for (MenuScanner scanner : SCANNERS) {
            if (scanner.commands().contains(root)) return scanner;
        }
        return null;
    }

    /** Scanner, der zu einem Fenstertitel passt – oder null. */
    public static MenuScanner findByTitle(String title) {
        for (MenuScanner scanner : SCANNERS) {
            if (scanner.matchesTitle(title)) return scanner;
        }
        return null;
    }
}
