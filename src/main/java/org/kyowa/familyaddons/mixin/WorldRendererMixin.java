package org.kyowa.familyaddons.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.kyowa.familyaddons.features.CorpseESP;
import org.kyowa.familyaddons.features.EntityHighlight;
import org.kyowa.familyaddons.features.KuudraCrateWaypoints;
import org.kyowa.familyaddons.features.NpcLocations;
import org.kyowa.familyaddons.features.Parkour;
import org.kyowa.familyaddons.features.PearlWaypoints;
import org.kyowa.familyaddons.features.PileWaypoints;
import org.kyowa.familyaddons.features.SupplyWaypoints;
import org.kyowa.familyaddons.features.Waypoints;
import org.kyowa.familyaddons.features.WorldScanner;
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
        // Early-out if no feature wants to render
        if (!Waypoints.INSTANCE.hasWaypoints() &&
                !CorpseESP.INSTANCE.hasCachedCorpses() &&
                !NpcLocations.INSTANCE.hasActiveWaypoints() &&
                !Parkour.INSTANCE.hasRings() &&
                !EntityHighlight.INSTANCE.hasHighlighted() &&
                !WorldScanner.INSTANCE.hasWaypoints() &&
                !KuudraCrateWaypoints.INSTANCE.hasCrates() &&
                !PearlWaypoints.INSTANCE.hasWaypoints() &&
                !PileWaypoints.INSTANCE.hasBeams() &&
                !SupplyWaypoints.INSTANCE.hasBeams()) return;

        fa_matrices.loadIdentity();
        fa_matrices.multiplyPositionMatrix(positionMatrix);

        Waypoints.INSTANCE.onWorldRender(fa_matrices, camera);
        CorpseESP.INSTANCE.onWorldRender(fa_matrices, camera);
        NpcLocations.INSTANCE.onWorldRender(fa_matrices, camera);
        Parkour.INSTANCE.onWorldRender(fa_matrices, camera);
        EntityHighlight.INSTANCE.onWorldRender(fa_matrices, camera);
        WorldScanner.INSTANCE.onWorldRender(fa_matrices, camera);
        KuudraCrateWaypoints.INSTANCE.onWorldRender(fa_matrices, camera);
        PearlWaypoints.INSTANCE.onWorldRender(fa_matrices, camera);
        PileWaypoints.INSTANCE.onWorldRender(fa_matrices, camera);
        SupplyWaypoints.INSTANCE.onWorldRender(fa_matrices, camera);
    }
}