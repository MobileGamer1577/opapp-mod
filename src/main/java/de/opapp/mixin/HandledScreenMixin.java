// ═══════════════════════════════════════════════════════════════
//  HandledScreenMixin.java – beobachtet Klicks auf Menü-Slots
//
//  ✅ HIER ÄNDERN: nichts
//  ❌ NICHT ÄNDERN: Es wird NICHTS verändert, abgebrochen oder gesendet –
//                   der Mixin gibt nur "Slot X wurde angeklickt" an den
//                   ScreenWatcher weiter (rein lesend, @At("HEAD")).
//
//  WARUM: Der Fenstertitel der OPSUCHT-Erfolge besteht nur aus
//  Font-Glyphen. Den Namen der Kategorie ("Miner") erfährt die Mod
//  deshalb nur, indem sie mitbekommt, welchen Übersichts-Eintrag der
//  Spieler anklickt – und auf "Weiter"/"Zurück" zum Seitenwechsel.
//
//  SICHERHEIT: require = 0 (und defaultRequire = 0 in opapp.mixins.json) –
//  sollte sich die Zielmethode in einer anderen Minecraft-Version ändern,
//  stürzt das Spiel NICHT ab; die Klick-Erkennung ist dann nur inaktiv
//  (in /opapp debug steht "Klicks erkannt: 0"). Jede Ausnahme im Handler
//  wird abgefangen, damit nie ein Klick im Spiel blockiert wird.
//
//  ÄNDERUNGEN (Fix 1.0.1):
//    - NEU: Datei.
// ═══════════════════════════════════════════════════════════════

package de.opapp.mixin;

import de.opapp.OpappMod;
import de.opapp.scan.ScreenWatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.Window;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {

    @Inject(method = "onMouseClick", at = @At("HEAD"), require = 0)
    private void opapp$onMouseClick(Click click, CallbackInfo ci) {
        try {
            HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
            ScreenHandler handler = screen.getScreenHandler();
            if (handler == null) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.mouse == null) return;

            Window window = client.getWindow();
            double scale = window.getScaleFactor();
            double mouseX = client.mouse.getX() / scale;
            double mouseY = client.mouse.getY() / scale;

            Slot hovered = findSlotAt(screen, handler, mouseX, mouseY);
            if (hovered == null) return;

            ItemStack stack = hovered.getStack();
            if (stack == null || stack.isEmpty()) return;

            ScreenWatcher.onSlotClicked(hovered.id, stack);
        } catch (Throwable t) {
            OpappMod.LOG.debug("Klick-Beobachtung fehlgeschlagen: {}", t.toString());
        }
    }

    private static Slot findSlotAt(HandledScreen<?> screen, ScreenHandler handler, double mouseX, double mouseY) {
        int rows = (handler.slots.size() - 36) / 9;
        if (rows <= 0) return null;

        int containerWidth = 176;
        int containerHeight = 114 + rows * 18;
        int guiLeft = (screen.width - containerWidth) / 2;
        int guiTop = (screen.height - containerHeight) / 2;

        for (Slot slot : handler.slots) {
            int x1 = guiLeft + slot.x;
            int y1 = guiTop + slot.y;
            if (mouseX >= x1 - 1 && mouseX < x1 + 17 && mouseY >= y1 - 1 && mouseY < y1 + 17) {
                return slot;
            }
        }
        return null;
    }
}