package org.kyowa.familyaddons.features

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier

/**
 * Custom render pipelines + layers for FamilyAddons beam rendering.
 *
 * Faithful port of iq-addons' WorldRenderUtils$Pipelines + $Layers + $Parameters
 * pattern. The point of building custom RenderPipelines is to get exact
 * control over depth-test mode, blending, lightmap state — none of which
 * Minecraft's built-in RenderLayer.getDebugFilledBox() provides correctly
 * for "beacon-style" beam visuals.
 *
 * Pipeline composition (matches iq exactly):
 *  - Built FROM RenderPipelines.POSITION_COLOR_SNIPPET (vanilla snippet that
 *    provides the position+color shader chain — same one Mojang uses for
 *    debug filled boxes)
 *  - withVertexFormat(POSITION_COLOR, QUADS)
 *  - filledCull: cull faces, depth-test enabled (default depth function)
 *  - filledNoCull: NO_DEPTH_TEST so beams are visible through walls
 *
 * Layer composition (matches iq exactly):
 *  - bufferSize=1536 (same as iq)
 *  - hasCrumbling=false, translucent=true
 *  - MultiPhaseParameters.builder().lightmap(DISABLE_LIGHTMAP).build(false)
 *    → no lightmap interference; beams render at constant brightness
 *
 * The .build(false) means "don't auto-add to a buffer source's known
 * affecting layers" — we use our own buffer source anyway, so this is fine.
 */
object FamilyRenderTypes {

    // ── Pipelines ─────────────────────────────────────────────────────

    /** Filled quad pipeline with face culling + depth test. Used for solid front pass. */
    val FILLED_CULL: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("familyaddons", "pipeline/familyaddons_filled_cull"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
            .build()
    )

    /** Filled quad pipeline with NO depth test. Used for through-walls pass. */
    val FILLED_NO_CULL: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("familyaddons", "pipeline/familyaddons_filled_no_cull"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .build()
    )

    // ── Layers ────────────────────────────────────────────────────────

    private val PARAMETERS_FILLED: RenderLayer.MultiPhaseParameters =
        RenderLayer.MultiPhaseParameters.builder()
            .lightmap(RenderLayer.DISABLE_LIGHTMAP)
            .build(false)

    /** Filled box layer — solid front, depth tested. */
    val BOX_FILLED: RenderLayer = RenderLayer.of(
        "familyaddons_box_filled",
        1536,
        false,  // hasCrumbling
        true,   // translucent
        FILLED_CULL,
        PARAMETERS_FILLED,
    )

    /** Filled box layer — through walls, no depth test. */
    val BOX_FILLED_NO_CULL: RenderLayer = RenderLayer.of(
        "familyaddons_box_filled_no_cull",
        1536,
        false,
        true,
        FILLED_NO_CULL,
        PARAMETERS_FILLED,
    )
}