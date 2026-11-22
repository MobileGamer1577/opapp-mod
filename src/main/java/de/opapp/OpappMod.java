// ═══════════════════════════════════════════════════════════════
//  OpappMod.java – Einstiegspunkt der OPAPP-Mod (nur Client)
//
//  ✅ HIER ÄNDERN: neue Komponenten (z.B. weitere Scanner) hier
//                  verdrahten
//  ❌ NICHT ÄNDERN: Mod-ID "opapp" (muss zu fabric.mod.json passen)
//
//  Die Mod ist rein passiv: kein HUD, kein Rendering, kein Klicken.
//  Alles läuft über Client-Events (Tick, Befehle, Blockabbau, beobachtete
//  Menü-Klicks) und Netzwerk-Aufrufe ausschließlich asynchron (siehe
//  OpappApi).
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
//
//  ÄNDERUNGEN (Fix 1.0.1):
//    - ScreenWatcher bekommt die Config (für den Live-Debug-Schalter).
// ═══════════════════════════════════════════════════════════════

package de.opapp;

import de.opapp.command.OpappCommand;
import de.opapp.config.OpappConfig;
import de.opapp.net.OpappApi;
import de.opapp.scan.ScreenWatcher;
import de.opapp.track.BlockCounter;
import de.opapp.track.ProgressStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpappMod implements ClientModInitializer {

    public static final String MOD_ID = "opapp";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** Version aus fabric.mod.json (kommt aus gradle.properties → mod_version). */
    public static final String VERSION = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");

    @Override
    public void onInitializeClient() {
        OpappConfig config = OpappConfig.load();
        OpappApi api = new OpappApi(config);
        ProgressStore store = new ProgressStore();

        ScreenWatcher watcher = new ScreenWatcher(api, store, config);
        BlockCounter counter = new BlockCounter(config, api, store);

        watcher.register();
        counter.register();
        OpappCommand.register(api, config, watcher);

        // Ausloggen: noch nicht gesendete Scans und Zählerstände rausschicken.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            watcher.flushPending();
            counter.flushDirty();
        });

        // Spiel beenden: letzter Versuch, kurz (max. 2 s) auf die Antwort
        // zu warten, damit beim Schließen des Fensters nichts verloren geht.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            watcher.flushPending();
            counter.flushDirtyAndWait(2000);
        });

        LOG.info("OPAPP {} geladen (verbunden: {}).", VERSION, api.isLinked());
    }

    /**
     * Chat-Nachricht an den Spieler (nur lokal sichtbar). Darf von jedem
     * Thread aufgerufen werden – die Ausgabe wird auf den Client-Thread
     * verschoben.
     */
    public static void chat(String message, Formatting color) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player == null) return;
            Text line = Text.literal("[OPAPP] ").formatted(Formatting.LIGHT_PURPLE)
                    .append(Text.literal(message).formatted(color));
            client.player.sendMessage(line, false);
        });
    }

    public static void chat(String message) {
        chat(message, Formatting.GRAY);
    }
}
