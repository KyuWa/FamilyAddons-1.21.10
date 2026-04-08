package org.kyowa.familyaddons.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.kyowa.familyaddons.config.FamilyConfigManager;

@Mixin(Mouse.class)
public class HotbarScrollMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (!FamilyConfigManager.INSTANCE.getConfig().utilities.lockHotbarScroll) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.currentScreen != null) return;

        int slot = client.player.getInventory().getSelectedSlot();

        if (vertical < 0 && slot == 8) {
            ci.cancel();
            return;
        }

        if (vertical > 0 && slot == 0) {
            ci.cancel();
        }
    }
}