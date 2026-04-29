package org.kyowa.familyaddons.features.pearl

import net.minecraft.util.math.Vec3d

/**
 * Supply pickup locations around the Kuudra arena. These are where Elle's
 * supply chests physically spawn and are picked up. After picking one up,
 * you carry it to a [Pre] drop-off area in the centre.
 *
 */
enum class Place(val location: Vec3d) {
    SHOP    (Vec3d( -98.0, 79.0, -113.0)),
    X       (Vec3d(-106.0, 79.0, -113.0)),
    X_CANNON(Vec3d(-110.0, 79.0, -106.0)),
    EQUALS  (Vec3d(-106.0, 79.0,  -99.0)),
    SLASH   (Vec3d( -98.0, 79.0,  -99.0)),
    TRIANGLE(Vec3d( -94.0, 79.0, -106.0)),
}
