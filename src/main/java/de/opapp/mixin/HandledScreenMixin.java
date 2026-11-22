// ═══════════════════════════════════════════════════════════════
//  HandledScreenMixin.java – beobachtet Klicks auf Menü-Slots
//
//  ✅ HIER ÄNDERN: nichts
//  ❌ NICHT ÄNDERN: Es wird NICHTS verändert, abgebrochen oder gesendet –
//                   der Mixin gibt nur "Slot X wurde angeklickt" an den
//                   ScreenWatcher weiter (rein lesend, @At("HEAD")).
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

import java.lang.reflect.Field;

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
        int menuSlots = rows * 9;

        int containerWidth = 176;
        int containerHeight = 114 + rows * 18;
        int guiLeft = readIntField(screen, "x", "field_2776", (screen.width - containerWidth) / 2);
        int guiTop = readIntField(screen, "y", "field_2800", (screen.height - containerHeight) / 2);

        Slot best = null;
        double bestDistSq = 10.5 * 10.5;

        for (Slot slot : handler.slots) {
            if (slot.id < 0 || slot.id >= menuSlots) continue;
            double cx = guiLeft + slot.x + 8.0;
            double cy = guiTop + slot.y + 8.0;
            double dx = mouseX - cx;
            double dy = mouseY - cy;
            if (Math.abs(dx) > 9.5 || Math.abs(dy) > 9.5) continue;
            double distSq = dx * dx + dy * dy;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = slot;
            }
        }
        return best;
    }

    private static int readIntField(Object target, String primary, String fallback, int defaultValue) {
        for (String name : new String[]{primary, fallback}) {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.getInt(target);
                } catch (ReflectiveOperationException ignored) {
                    c = c.getSuperclass();
                }
            }
        }
        return defaultValue;
    }
}