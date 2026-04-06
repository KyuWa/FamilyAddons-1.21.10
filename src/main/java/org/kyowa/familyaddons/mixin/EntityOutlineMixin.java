package org.kyowa.familyaddons.mixin;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.kyowa.familyaddons.features.CorpseESP;
import org.kyowa.familyaddons.features.EntityHighlight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityOutlineMixin<T extends Entity, S extends EntityRenderState> {

    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void onUpdateRenderState(T entity, S state, float tickProgress, CallbackInfo ci) {
        // Check EntityHighlight outline
        int highlightColor = EntityHighlight.INSTANCE.getOutlineColor(entity);
        if (highlightColor != 0) {
            state.outlineColor = highlightColor;
            return;
        }

        // Check CorpseESP outline
        int corpseColor = CorpseESP.INSTANCE.getOutlineColor(entity);
        if (corpseColor != 0) {
            state.outlineColor = corpseColor;
        }
    }
}
