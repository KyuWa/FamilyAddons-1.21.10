package org.kyowa.familyaddons.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.kyowa.familyaddons.features.CorpseESP;
import org.kyowa.familyaddons.features.NpcLocations;
import org.kyowa.familyaddons.features.EntityHighlight;
import org.kyowa.familyaddons.features.Parkour;
import org.kyowa.familyaddons.features.Waypoints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    private final MatrixStack fa_matrices = new MatrixStack();

    @Inject(method = "render", at = @At("RETURN"))
    private void onRender(
            ObjectAllocator allocator,
            @Coerce Object tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f positionMatrix,
            Matrix4f matrix4f,
            Matrix4f projectionMatrix,
            @Coerce Object fogBuffer,
            Vector4f fogColor,
            boolean renderSky,
            CallbackInfo ci
    ) {
        if (!Waypoints.INSTANCE.hasWaypoints() &&
                !CorpseESP.INSTANCE.hasCachedCorpses() &&
                !NpcLocations.INSTANCE.hasActiveWaypoints() &&
                !Parkour.INSTANCE.hasRings() &&
                !EntityHighlight.INSTANCE.hasHighlighted()) return;

        fa_matrices.loadIdentity();
        fa_matrices.multiplyPositionMatrix(positionMatrix);
        Waypoints.INSTANCE.onWorldRender(fa_matrices, camera);
        CorpseESP.INSTANCE.onWorldRender(fa_matrices, camera);
        NpcLocations.INSTANCE.onWorldRender(fa_matrices, camera);
        Parkour.INSTANCE.onWorldRender(fa_matrices, camera);
        EntityHighlight.INSTANCE.onWorldRender(fa_matrices, camera);
    }
}
