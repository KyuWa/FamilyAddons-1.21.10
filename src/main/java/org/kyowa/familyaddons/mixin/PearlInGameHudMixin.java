package org.kyowa.familyaddons.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.kyowa.familyaddons.features.PearlWaypoints;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads Hypixel's progress title (`[some prefix] XX%`) by intercepting
 * {@code InGameHud.renderTitleAndSubtitle} at HEAD and reading the cached
 * {@code title} field directly. Deduplicates per identical title text so
 * the parse logic fires once per unique title, not every render frame.
 *
 * Yarn 1.21.10 names:
 *   - InGameHud#title  (field — the cached title Text)
 *   - InGameHud#renderTitleAndSubtitle  (called every frame while a title is up)
 */
@Mixin(InGameHud.class)
public class PearlInGameHudMixin {

    @Shadow @Final @Nullable
    private Text title;

    @Unique private String fa$lastTitleText = "";

    @Inject(method = "renderTitleAndSubtitle", at = @At("HEAD"))
    private void familyaddons$onRenderTitle(
            net.minecraft.client.gui.DrawContext context,
            RenderTickCounter tickCounter,
            CallbackInfo ci
    ) {
        try {
            Text t = this.title;
            if (t == null) return;
            String raw = t.getString();
            if (raw == null || raw.isEmpty()) return;
            if (raw.equals(fa$lastTitleText)) return;
            fa$lastTitleText = raw;
            PearlWaypoints.INSTANCE.onTitle(raw);
        } catch (Throwable ignored) {
            // Never let this propagate — would break HUD rendering.
        }
    }
}
