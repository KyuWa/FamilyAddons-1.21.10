package org.kyowa.familyaddons.mixin;

import net.minecraft.client.render.Camera;
import org.kyowa.familyaddons.features.CameraHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public class CameraMixin {

    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void familyaddons$clipToSpace(float distance, CallbackInfoReturnable<Float> cir) {
        if (CameraHelper.isClipEnabled()) {
            cir.setReturnValue(distance);
        }
    }

    /**
     * Modifies the float arg to clipToSpace inside Camera.update. The value is
     * computed by Math.max(scale * cameraDistanceAttr, vehicleScale * vehicleAttr)
     * — for a player riding nothing, that's 1.0 * 4.0 = 4.0. We override here.
     *
     * require = 1 so if the target call ever changes, mixin apply will FAIL
     * loudly at startup instead of silently doing nothing.
     */
    @ModifyArg(
            method = "update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/Camera;clipToSpace(F)F"
            ),
            require = 1
    )
    private float familyaddons$customDistance(float original) {
        Float custom = CameraHelper.getCustomDistance();
        float result = custom != null ? custom : original;
        // Cheap one-shot debug — uncomment if you want to verify it's running:
        // System.out.println("[FA Camera] clipToSpace arg: original=" + original + " final=" + result);
        return result;
    }
}