// ═══════════════════════════════════════════════════════════════
//  OpappCommand.java – Client-Befehle der Mod (/opapp …)
//
//  ✅ HIER ÄNDERN: Texte der Rückmeldungen
//  ❌ NICHT ÄNDERN: Registrierung über ClientCommandRegistrationCallback
//                   (rein clientseitig – der Server sieht diese Befehle nie)
//
//  BEFEHLE:
//    /opapp                 Hilfe
//    /opapp link <code>     6-stelligen Code aus der App einlösen
//    /opapp status          verbunden? als wer? Menü-/Zählerstand
//    /opapp unlink          lokalen Token löschen (Fortschritt in der App
//                           bleibt; in der App lässt sich zusätzlich trennen)
//    /opapp debug           zuletzt gelesene Menü-Seite nach
//                           config/opapp-debug.txt schreiben
//
//  Alle Netzwerk-Aufrufe laufen asynchron (siehe OpappApi); die Befehle
//  selbst kehren sofort zurück.
//
//  ÄNDERUNGEN (Erfolge-Update):
//    - NEU: Datei.
// ═══════════════════════════════════════════════════════════════

package de.opapp.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.opapp.OpappMod;
import de.opapp.config.OpappConfig;
import de.opapp.net.OpappApi;
import de.opapp.scan.ScreenWatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class OpappCommand {

    private OpappCommand() {}

    public static void register(OpappApi api, OpappConfig config, ScreenWatcher watcher) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("opapp")
                        .executes(ctx -> {
                            help();
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(literal("link")
                                .then(argument("code", StringArgumentType.word())
                                        .executes(ctx -> {
                                            link(api, StringArgumentType.getString(ctx, "code"));
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(literal("status")
                                .executes(ctx -> {
                                    status(api, config);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(literal("unlink")
                                .executes(ctx -> {
                                    unlink(api);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(literal("debug")
                                .executes(ctx -> {
                                    debug(watcher);
                                    return Command.SINGLE_SUCCESS;
                                }))));
    }

    private static void help() {
        OpappMod.chat("/opapp link <code>  – mit der App verbinden (Code aus Einstellungen → Konto)");
        OpappMod.chat("/opapp status       – Verbindungsstatus");
        OpappMod.chat("/opapp unlink       – Verbindung auf diesem PC lösen");
        OpappMod.chat("/opapp debug        – zuletzt gelesenes Menü in eine Datei schreiben");
    }

    private static void link(OpappApi api, String code) {
        if (!code.matches("\\d{6}")) {
            OpappMod.chat("Der Code muss aus genau 6 Ziffern bestehen, z.B. /opapp link 123456", Formatting.RED);
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        OpappMod.chat("Verbinde …");
        api.link(code, client.player.getUuidAsString(), client.player.getName().getString());
    }

    private static void status(OpappApi api, OpappConfig config) {
        if (api.isLinked()) {
            OpappMod.chat("Verbunden mit der OPAPP-App.", Formatting.GREEN);
        } else {
            OpappMod.chat("Nicht verbunden. Code in der App erzeugen, dann /opapp link <code>.", Formatting.YELLOW);
        }
        OpappMod.chat("Hintergrund-Zähler: " + (config.counterEnabled ? "an" : "aus")
                + " (config/opapp.json → counterEnabled)");
    }

    private static void unlink(OpappApi api) {
        if (!api.isLinked()) {
            OpappMod.chat("Es besteht keine Verbindung.", Formatting.YELLOW);
            return;
        }
        api.clearToken();
        OpappMod.chat("Verbindung gelöscht. Dein Fortschritt in der App bleibt erhalten.", Formatting.GREEN);
    }

    private static void debug(ScreenWatcher watcher) {
        String dump = watcher.lastDump();
        if (dump.isBlank()) {
            OpappMod.chat("Noch nichts gelesen. Öffne /erfolge, klicke eine Kategorie an und tippe dann "
                    + "/opapp debug.", Formatting.YELLOW);
            return;
        }
        Path file = FabricLoader.getInstance().getConfigDir().resolve("opapp-debug.txt");
        try {
            Files.writeString(file, dump, StandardCharsets.UTF_8);
            OpappMod.chat(watcher.lastSummary());
            OpappMod.chat("Vollständig gespeichert in: " + file.toAbsolutePath(), Formatting.GREEN);
        } catch (IOException e) {
            OpappMod.LOG.error("Debug-Datei konnte nicht geschrieben werden.", e);
            OpappMod.chat("Debug-Datei konnte nicht geschrieben werden (siehe Log).", Formatting.RED);
        }
    }
}
