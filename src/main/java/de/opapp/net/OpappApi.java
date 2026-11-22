// ═══════════════════════════════════════════════════════════════
//  OpappApi.java – HTTP-Kommunikation mit dem OPAPP-Worker
//
//  ✅ HIER ÄNDERN: Fehlertexte (errorText), Chunk-Größe (CHUNK_SIZE)
//  ❌ NICHT ÄNDERN: ALLE Aufrufe laufen über HttpClient.sendAsync und
//                   dürfen nie auf dem Render-/Client-Thread blockieren
//                   (einzige Ausnahme: kurzes Warten beim Beenden, siehe
//                   BlockCounter.flushDirtyAndWait)
//
//  ENDPUNKTE (siehe src/achievements.js im Worker-Repo opapp-api):
//    POST /api/link   { code, mc_uuid, mc_name }        → { token }
//    POST /api/sync   { menu, client, mc_name, entries } → { accepted, rejected }
//                     Header: Authorization: Bearer <token>
//
//  Bei HTTP 401 (Token ungültig, z.B. in der App getrennt) wird der
//  lokale Token gelöscht und der Spieler auf /opapp link hingewiesen.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
//
//  ÄNDERUNGEN (Fix 1.0.1):
//    - Die Chat-Meldung nennt jetzt auch "rejected" aus der Worker-
//      Antwort. Lehnt der Worker Aufgaben ab, erscheint eine Warnung
//      statt einer irreführenden Erfolgsmeldung ("0 Aufgaben
//      synchronisiert" war genau dieses Symptom).
// ═══════════════════════════════════════════════════════════════

package de.opapp.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.opapp.OpappMod;
import de.opapp.config.OpappConfig;
import de.opapp.scan.TaskEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class OpappApi {

    /** Aufgaben pro Request (der Worker erlaubt max. 300). */
    private static final int CHUNK_SIZE = 250;

    private final OpappConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OpappApi(OpappConfig config) {
        this.config = config;
    }

    public boolean isLinked() {
        return config.token != null && !config.token.isBlank();
    }

    /** Löscht den lokalen Token (z.B. bei /opapp unlink). */
    public void clearToken() {
        config.token = "";
        config.save();
    }

    // ═══════════════════════════════════════════════════════════
    //  Verknüpfen
    // ═══════════════════════════════════════════════════════════

    /** Tauscht den 6-stelligen Code aus der App gegen einen Token. */
    public void link(String code, String mcUuid, String mcName) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("mc_uuid", mcUuid);
        body.addProperty("mc_name", mcName);

        http.sendAsync(post("/api/link", body, false), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(response -> {
                    JsonObject json = parseObject(response.body());
                    if (response.statusCode() == 200 && json != null && json.has("token")) {
                        config.token = json.get("token").getAsString();
                        config.save();
                        OpappMod.chat("Verbunden als " + mcName + "! Öffne jetzt /erfolge und klicke "
                                + "durch die Kategorien – der Rest passiert automatisch.", Formatting.GREEN);
                    } else {
                        OpappMod.chat(errorText(response.statusCode(), json), Formatting.RED);
                    }
                })
                .exceptionally(ex -> {
                    OpappMod.LOG.warn("Verknüpfen fehlgeschlagen", ex);
                    OpappMod.chat("Keine Verbindung zum OPAPP-Server. Bitte später erneut versuchen.", Formatting.RED);
                    return null;
                });
    }

    // ═══════════════════════════════════════════════════════════
    //  Synchronisieren
    // ═══════════════════════════════════════════════════════════

    /**
     * Sendet Aufgaben eines Menüs an den Worker (in Paketen zu je
     * {@value #CHUNK_SIZE}).
     *
     * @param announce true = nach Abschluss eine kurze Chat-Meldung (falls in
     *                 der Config erlaubt) – für Scans, nicht für Zähler
     * @return Future, das mit true (alles gesendet) oder false (Fehler) endet;
     *         wirft nie
     */
    public CompletableFuture<Boolean> syncAsync(String menu, List<TaskEntry> entries, boolean announce) {
        if (!isLinked() || entries.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        // Spielername für den Worker (aktualisiert die Anzeige in der App).
        MinecraftClient client = MinecraftClient.getInstance();
        String mcName = client.player != null ? client.player.getName().getString() : null;

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
        for (List<TaskEntry> chunk : partition(entries, CHUNK_SIZE)) {
            chain = chain.thenCompose(ok -> ok
                    ? sendChunk(menu, chunk, mcName, accepted, rejected)
                    : CompletableFuture.completedFuture(false));
        }

        return chain.thenApply(ok -> {
            if (ok && announce && config.chatFeedback) {
                announceResult(accepted.get(), rejected.get());
            }
            return ok;
        });
    }

    private static void announceResult(int accepted, int rejected) {
        if (accepted == 0 && rejected > 0) {
            OpappMod.chat("Der Server hat alle " + rejected + " Aufgaben abgelehnt. "
                    + "Bitte /opapp debug ausführen und die Datei melden.", Formatting.RED);
        } else if (rejected > 0) {
            OpappMod.chat(accepted + " Aufgaben synchronisiert, " + rejected + " abgelehnt.", Formatting.YELLOW);
        } else {
            OpappMod.chat(accepted + " Aufgaben mit der App synchronisiert.", Formatting.GREEN);
        }
    }

    private CompletableFuture<Boolean> sendChunk(String menu, List<TaskEntry> chunk, String mcName,
                                                 AtomicInteger accepted, AtomicInteger rejected) {
        JsonObject body = new JsonObject();
        body.addProperty("menu", menu);
        body.addProperty("client", "opapp-mod/" + OpappMod.VERSION);
        if (mcName != null) body.addProperty("mc_name", mcName);
        JsonArray array = new JsonArray();
        for (TaskEntry entry : chunk) array.add(toJson(entry));
        body.add("entries", array);

        return http.sendAsync(post("/api/sync", body, true), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    int status = response.statusCode();
                    JsonObject json = parseObject(response.body());
                    if (status == 200) {
                        if (json != null && json.has("accepted")) accepted.addAndGet(json.get("accepted").getAsInt());
                        if (json != null && json.has("rejected")) rejected.addAndGet(json.get("rejected").getAsInt());
                        return true;
                    }
                    if (status == 401) {
                        clearToken();
                        OpappMod.chat("Die Verknüpfung ist nicht mehr gültig (in der App getrennt?). "
                                + "Neu verbinden mit /opapp link <code>.", Formatting.RED);
                    } else {
                        OpappMod.LOG.warn("Sync fehlgeschlagen: HTTP {} {}", status, response.body());
                    }
                    return false;
                })
                .exceptionally(ex -> {
                    OpappMod.LOG.warn("Sync fehlgeschlagen: {}", ex.toString());
                    return false;
                });
    }

    // ═══════════════════════════════════════════════════════════
    //  Hilfsfunktionen
    // ═══════════════════════════════════════════════════════════

    private HttpRequest post(String path, JsonObject body, boolean authorized) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.apiBase + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "OPAPP-Mod/" + OpappMod.VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (authorized) builder.header("Authorization", "Bearer " + config.token);
        return builder.build();
    }

    /** Bewusst OHNE targets: Die Ziel-Namen sind nur für den lokalen Zähler. */
    private static JsonObject toJson(TaskEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("category", entry.category());
        json.addProperty("name", entry.name());
        json.addProperty("goal", entry.goal());
        json.addProperty("progress", entry.progress());
        json.addProperty("done", entry.done());
        if (entry.icon() != null) json.addProperty("icon", entry.icon());
        json.addProperty("slot", entry.slot());
        return json;
    }

    private static JsonObject parseObject(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return parts;
    }

    /** ✅ HIER ÄNDERN: verständliche Texte zu den Fehlercodes des Workers. */
    private static String errorText(int status, JsonObject json) {
        String code = json != null && json.has("error") ? json.get("error").getAsString() : "";
        return switch (code) {
            case "invalid_code" -> "Der Code muss aus genau 6 Ziffern bestehen.";
            case "code_unknown_or_expired" ->
                    "Code unbekannt oder abgelaufen. Erzeuge in der App einen neuen Code.";
            case "code_already_used" -> "Dieser Code wurde schon verwendet. Erzeuge in der App einen neuen.";
            case "too_many_attempts" -> "Zu viele Fehlversuche. Bitte warte 10 Minuten.";
            case "backend_not_configured", "db_error" -> "Der OPAPP-Server hat gerade ein Problem. Später erneut versuchen.";
            default -> "Verknüpfen fehlgeschlagen (HTTP " + status + ").";
        };
    }
}
