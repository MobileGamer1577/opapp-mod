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
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {

    @Shadow
    protected Slot focusedSlot;

    @Inject(method = "onMouseClick", at = @At("HEAD"), require = 0)
    private void opapp$onMouseClick(Click click, CallbackInfo ci) {
        try {
            Slot targetSlot = resolveClickedSlot();
            if (targetSlot == null) return;
            ScreenWatcher.onSlotClicked(targetSlot.id, targetSlot.getStack());
        } catch (Throwable t) {
            OpappMod.LOG.debug("Klick-Beobachtung fehlgeschlagen: {}", t.toString());
        }
    }

    private Slot resolveClickedSlot() {
        if (this.focusedSlot != null && this.focusedSlot.hasStack()) {
            return this.focusedSlot;
        }
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        ScreenHandler handler = screen.getScreenHandler();
        if (handler != null) {
            for (Slot slot : handler.slots) {
                if (slot.hasStack()) {
                    return slot;
                }
            }
        }
        return this.focusedSlot;
    }
}