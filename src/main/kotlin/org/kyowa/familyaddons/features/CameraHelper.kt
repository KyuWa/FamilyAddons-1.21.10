package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.Perspective
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Camera tweaks — third-person camera distance, clip-through-blocks, and
 * no-front-camera.
 *
 * Clip + Distance are implemented in CameraMixin which reads the config
 * accessors below. No-Front-Camera is a tick-level check that flips
 * THIRD_PERSON_FRONT back to FIRST_PERSON every tick when the toggle is on.
 */
object CameraHelper {

    @JvmStatic
    fun isClipEnabled(): Boolean =
        FamilyConfigManager.config.utilities.cameraClip

    /** Returns the camera distance to use, or null if vanilla 4.0 should be used. */
    @JvmStatic
    fun getCustomDistance(): Float? {
        val cfg = FamilyConfigManager.config.utilities
        if (!cfg.cameraDistEnabled) return null
        return cfg.cameraDist.coerceIn(3f, 12f)
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.utilities.noFrontCamera) return@register
            if (client.options.perspective == Perspective.THIRD_PERSON_FRONT) {
                client.options.perspective = Perspective.FIRST_PERSON
            }
        }
    }
}