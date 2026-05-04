package org.kyowa.familyaddons.features

import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import kotlin.math.cos
import kotlin.math.sin

/**
 * Beacon beam renderer using immediate-mode VertexConsumerProvider.
 *
 * Why not use [net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer.renderBeam]
 * directly? In 1.21.10 that method uses [OrderedRenderCommandQueue] which
 * DEFERS rendering — by the time the queue drains, our matrix state is
 * gone. That caused the "moves with camera" bug in earlier attempts.
 *
 * Instead we emit beam geometry directly via the world's
 * VertexConsumerProvider.Immediate (same pattern as Waypoints/CorpseESP),
 * using [RenderLayer.getBeaconBeam] which is a regular render layer with
 * the proper shader, blend mode, and beacon texture binding.
 *
 * Geometry is a port of the standard CT BeaconBeam helper:
 *  - INNER beam: 4 quads forming a rectangular column, rotated each frame
 *    by angles offset 45°/135°/225°/315°. Solid alpha.
 *  - OUTER beam: 4 quads forming a fixed wider rectangular column. Faded
 *    alpha (0.25× input alpha).
 *  - Texture v-coords scroll over time creating the upward streaks effect.
 *
 * This is mathematically equivalent to vanilla's renderBeam, just rendered
 * synchronously instead of via the queue.
 */
object BeaconBeamRenderer {

    /** Vanilla beacon beam texture path. */
    private val BEAM_TEXTURE: Identifier =
        Identifier.ofVanilla("textures/entity/beacon_beam.png")

    /**
     * Draw a beacon beam at world position (x, baseY, z) with given height
     * and color.
     *
     * @param matrices  camera-relative MatrixStack from WorldRendererMixin,
     *                  with caller's translate(-cam) already applied
     * @param x, baseY, z  absolute world position of the beam base
     * @param height    beam height in blocks
     * @param r, g, b   color components in 0..1
     * @param alpha     opacity 0..1 (top of beam fades to this; bottom is solid)
     */
    fun drawBeam(
        matrices: MatrixStack,
        x: Double,
        baseY: Double,
        z: Double,
        height: Double,
        r: Float, g: Float, b: Float,
        alpha: Float = 1f,
    ) {
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        val consumers = mc.bufferBuilders?.entityVertexConsumers ?: return

        val time = world.time.toFloat() + mc.renderTickCounter.getTickProgress(false)

        // d1: vertical texture-scroll offset that wraps every 5 ticks (matches CT helper)
        val d1 = MathHelper.fractionalPart(-time * 0.2f - MathHelper.floor(-time * 0.1f).toFloat())

        // d2: rotation angle, derived from world time. -1.5 sign matches CT helper.
        val d2 = (time * 0.025 * -1.5).toFloat()

        // 4 corner offsets, each rotated 90° from the prev. Inner beam radius 0.2.
        // CT uses constants (1π/4 + π) etc; the four angles are 135°, 45°, 225°, 315°.
        val d4  = 0.5f + cos(d2 + 2.356194490192345f) * 0.2f   // 135°
        val d5  = 0.5f + sin(d2 + 2.356194490192345f) * 0.2f
        val d6  = 0.5f + cos(d2 + (Math.PI.toFloat() / 4f)) * 0.2f   // 45°
        val d7  = 0.5f + sin(d2 + (Math.PI.toFloat() / 4f)) * 0.2f
        val d8  = 0.5f + cos(d2 + 3.9269908169872414f) * 0.2f   // 225°
        val d9  = 0.5f + sin(d2 + 3.9269908169872414f) * 0.2f
        val d10 = 0.5f + cos(d2 + 5.497787143782138f) * 0.2f   // 315°
        val d11 = 0.5f + sin(d2 + 5.497787143782138f) * 0.2f

        // Texture v range for the inner beam — top stretches by height*2.5/0.4.
        val d14 = -1f + d1
        val d15 = (height.toFloat() * 2.5f / 0.4f) + d14
        // Texture v range for the outer beam — top stretches by height.
        val d12 = d14
        val d13 = height.toFloat() + d12

        // Translate matrix to beam base. Subtract 0.5 from x and z so passing
        // integer world coords produces a beam centered on the 4-block
        // intersection (matching the BeaconBeam.js helper). Vertex offsets
        // below (d4..d11 ≈ 0.5; outer 0.2..0.8) place the beam center at
        // (x + 0.5, z + 0.5) relative to translation, so net center is (x, z).
        matrices.push()
        matrices.translate(x - 0.5, baseY, z - 0.5)

        val pose = matrices.peek()
        val matPos = pose.positionMatrix

        // ────────────────────────────────────────────────────────────
        // INNER beam — 4 quads, rotating, solid alpha
        // ────────────────────────────────────────────────────────────
        val innerBuf = consumers.getBuffer(RenderLayer.getBeaconBeam(BEAM_TEXTURE, false))
        val topY = height.toFloat()
        val botY = 0f

        // Face 1: from (d4, d5) to (d6, d7)
        innerBuf.vertex(matPos, d4, topY, d5).color(r, g, b, alpha).texture(1f, d15).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d4, botY, d5).color(r, g, b, 1f).texture(1f, d14).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d6, botY, d7).color(r, g, b, 1f).texture(0f, d14).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d6, topY, d7).color(r, g, b, alpha).texture(0f, d15).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)

        // Face 2: from (d10, d11) to (d8, d9)
        innerBuf.vertex(matPos, d10, topY, d11).color(r, g, b, alpha).texture(1f, d15).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d10, botY, d11).color(r, g, b, 1f).texture(1f, d14).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d8,  botY, d9 ).color(r, g, b, 1f).texture(0f, d14).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d8,  topY, d9 ).color(r, g, b, alpha).texture(0f, d15).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)

        // Face 3: from (d6, d7) to (d10, d11)
        innerBuf.vertex(matPos, d6,  topY, d7 ).color(r, g, b, alpha).texture(1f, d15).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d6,  botY, d7 ).color(r, g, b, 1f).texture(1f, d14).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d10, botY, d11).color(r, g, b, 1f).texture(0f, d14).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d10, topY, d11).color(r, g, b, alpha).texture(0f, d15).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)

        // Face 4: from (d8, d9) to (d4, d5)
        innerBuf.vertex(matPos, d8,  topY, d9 ).color(r, g, b, alpha).texture(1f, d15).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d8,  botY, d9 ).color(r, g, b, 1f).texture(1f, d14).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d4,  botY, d5 ).color(r, g, b, 1f).texture(0f, d14).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        innerBuf.vertex(matPos, d4,  topY, d5 ).color(r, g, b, alpha).texture(0f, d15).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)

        consumers.draw(RenderLayer.getBeaconBeam(BEAM_TEXTURE, false))

        // ────────────────────────────────────────────────────────────
        // OUTER beam — 4 quads, fixed shape, faded alpha (with-alpha layer)
        // ────────────────────────────────────────────────────────────
        val outerBuf = consumers.getBuffer(RenderLayer.getBeaconBeam(BEAM_TEXTURE, true))
        val outerAlpha = 0.25f * alpha

        // Face 1: x=0.2..0.8 at z=0.2
        outerBuf.vertex(matPos, 0.2f, topY, 0.2f).color(r, g, b, outerAlpha).texture(1f, d13).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.2f, botY, 0.2f).color(r, g, b, 0.25f).texture(1f, d12).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.8f, botY, 0.2f).color(r, g, b, 0.25f).texture(0f, d12).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.8f, topY, 0.2f).color(r, g, b, outerAlpha).texture(0f, d13).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)

        // Face 2: x=0.8 from z=0.2..0.8
        outerBuf.vertex(matPos, 0.8f, topY, 0.8f).color(r, g, b, outerAlpha).texture(1f, d13).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.8f, botY, 0.8f).color(r, g, b, 0.25f).texture(1f, d12).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.2f, botY, 0.8f).color(r, g, b, 0.25f).texture(0f, d12).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.2f, topY, 0.8f).color(r, g, b, outerAlpha).texture(0f, d13).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)

        // Face 3: x=0.8 from z=0.2..0.8 (other side)
        outerBuf.vertex(matPos, 0.8f, topY, 0.2f).color(r, g, b, outerAlpha).texture(1f, d13).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.8f, botY, 0.2f).color(r, g, b, 0.25f).texture(1f, d12).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.8f, botY, 0.8f).color(r, g, b, 0.25f).texture(0f, d12).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.8f, topY, 0.8f).color(r, g, b, outerAlpha).texture(0f, d13).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)

        // Face 4: x=0.2 from z=0.8..0.2
        outerBuf.vertex(matPos, 0.2f, topY, 0.8f).color(r, g, b, outerAlpha).texture(1f, d13).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.2f, botY, 0.8f).color(r, g, b, 0.25f).texture(1f, d12).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.2f, botY, 0.2f).color(r, g, b, 0.25f).texture(0f, d12).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)
        outerBuf.vertex(matPos, 0.2f, topY, 0.2f).color(r, g, b, outerAlpha).texture(0f, d13).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(pose, 0f, 1f, 0f)

        consumers.draw(RenderLayer.getBeaconBeam(BEAM_TEXTURE, true))

        matrices.pop()
    }
}