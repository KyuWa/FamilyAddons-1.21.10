package org.kyowa.familyaddons.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import org.kyowa.familyaddons.features.SignMath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSignEditScreen.class)
public class SignEditMixin {

    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void onRemoved(CallbackInfo ci) {
        try {
            AbstractSignEditScreen self = (AbstractSignEditScreen)(Object)this;
            String[] lines = null;
            SignBlockEntity sign = null;
            boolean isFront = true;

            // Try to find fields by type since names may be obfuscated
            for (java.lang.reflect.Field f : AbstractSignEditScreen.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(self);
                if (val instanceof String[]) {
                    lines = (String[]) val;
                } else if (val instanceof SignBlockEntity) {
                    sign = (SignBlockEntity) val;
                } else if (f.getType() == boolean.class) {
                    // Last boolean field is likely isFront
                    isFront = (boolean) val;
                }
            }

            if (lines == null || sign == null) return;

            UpdateSignC2SPacket original = new UpdateSignC2SPacket(
                    sign.getPos(), isFront,
                    lines.length > 0 ? lines[0] : "",
                    lines.length > 1 ? lines[1] : "",
                    lines.length > 2 ? lines[2] : "",
                    lines.length > 3 ? lines[3] : ""
            );

            UpdateSignC2SPacket modified = SignMath.INSTANCE.handleSignPacket(original);
            if (modified == null) return; // no math found, let vanilla run normally

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(modified);
                ci.cancel();
            }
        } catch (Exception e) {
            // fallback to vanilla
        }
    }
}
