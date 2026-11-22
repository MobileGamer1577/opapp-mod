// ═══════════════════════════════════════════════════════════════
//  TextClean.java – bereinigt Texte aus OPSUCHT-Menüs
//
//  ✅ HIER ÄNDERN: keep() – welche Zeichen erlaubt bleiben
//  ❌ NICHT ÄNDERN: clean() muss auf JEDEN Text angewendet werden, der
//                   gespeichert, angezeigt oder gesendet wird
//
//  PROBLEM: OPSUCHT zeichnet Menü-Hintergründe über eine eigene Schrift:
//  Der Fenstertitel besteht dann aus Zeichen wie "ሹሹሹሹሹሹⴁ" (äthiopische/
//  georgische Glyphen oder Private-Use-Zeichen) statt aus lesbarem Text.
//
//  REGEL: Erhalten bleiben nur lateinische Buchstaben (inkl. Umlaute),
//  Ziffern, Satzzeichen und Leerzeichen (Schriftart "LATIN", "COMMON",
//  "INHERITED"). Alles andere – andere Schriften, Private-Use-,
//  Steuer- und Formatzeichen – fliegt raus. "§x"-Farbcodes ebenfalls.
//
//  ÄNDERUNGEN (Fix 1.0.1):
//    - NEU: Datei.
// ═══════════════════════════════════════════════════════════════

package de.opapp.scan;

import java.util.regex.Pattern;

public final class TextClean {

    private static final Pattern COLOR_CODE = Pattern.compile("§.");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private TextClean() {}

    /** Entfernt Glyphen/Farbcodes, faltet Leerzeichen zusammen und trimmt. Nie null. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String text = COLOR_CODE.matcher(raw).replaceAll("");

        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp)) {
                sb.append(' ');
            } else if (keep(cp)) {
                sb.appendCodePoint(cp);
            }
        });
        return SPACES.matcher(sb).replaceAll(" ").trim();
    }

    private static boolean keep(int cp) {
        int type = Character.getType(cp);
        if (type == Character.PRIVATE_USE
                || type == Character.FORMAT
                || type == Character.CONTROL
                || type == Character.SURROGATE
                || type == Character.UNASSIGNED) {
            return false;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.LATIN
                || script == Character.UnicodeScript.COMMON
                || script == Character.UnicodeScript.INHERITED;
    }

    /** "ሹⴁ" → "U+1239 U+2D01" – nur für die Debug-Datei (zeigt, was entfernt wurde). */
    public static String codePoints(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        raw.codePoints().forEach(cp -> sb.append(String.format("U+%04X ", cp)));
        return sb.toString().trim();
    }
}
