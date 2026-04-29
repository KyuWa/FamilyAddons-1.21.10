package org.kyowa.familyaddons.features.pearl

import net.minecraft.util.math.Vec3d

/**
 * Drop-off / pearl-throw destination spots in the central arena. Each has a
 * physical [location] (where the pile of supplies is dropped), and an [area]
 * bounding box used to determine which spot the player is currently in.
 *
 *   array[0] = x_min  (less-negative bound, larger numerically)
 *   array[1] = z_min
 *   array[2] = x_max  (note: x_min/x_max here means "smallest" and "largest"
 *                       — for negative coordinates, x_max is closer to zero)
 *   array[3] = z_max
 *
 * The bytecode test is:
 *   eye.x >= array[0] && eye.x <= array[2] &&
 *   eye.z >= array[1] && eye.z <= array[3]
 */
enum class Pre(val location: Vec3d) {
    SHOP    (Vec3d( -85.3, 79.0, -128.3)),
    X       (Vec3d(-142.5, 77.0, -148.0)),
    X_CANNON(Vec3d(-143.0, 76.0, -125.0)),
    EQUALS  (Vec3d( -65.5, 76.0,  -87.5)),
    SLASH   (Vec3d(-113.5, 77.0,  -68.5)),
    TRIANGLE(Vec3d( -67.5, 77.0, -122.5)),
    SQUARE  (Vec3d(-143.0, 76.0,  -80.0)),
    NONE    (Vec3d(0.0, 0.0, 0.0));

    companion object {
        private data class Area(
            val xMin: Double, val zMin: Double,
            val xMax: Double, val zMax: Double,
        )

        // Bytecode raw values, storage order [x_min, z_min, x_max, z_max].
        // Each row matches the source array verbatim so it's easy to verify against
        // a fresh disassembly.
        private val areas: Map<Pre, Area> = mapOf(
            X         to Area(-171.0, -183.0, -124.0, -133.0),
            X_CANNON  to Area(-171.0, -133.0, -124.0, -103.0),
            SQUARE    to Area(-171.0, -103.0, -124.0,  -51.0),
            SLASH     to Area(-124.0,  -92.0,  -99.0,  -42.0),
            EQUALS    to Area( -84.0, -105.0,  -33.0,  -48.0),
            TRIANGLE  to Area( -78.0, -126.0,  -42.0, -105.0),
            SHOP      to Area( -96.0, -173.0,  -42.0, -126.0),
        )

        /**
         * Returns the [Pre] spot whose XZ bounding box contains the given eye
         * position, or [NONE] if none of them do.
         *
         * The bytecode does NOT min/max-normalise the array values, so we use
         * `inRange` helpers below that handle either ordering.
         */
        fun getClosestSpot(eye: Vec3d): Pre {
            for ((spot, area) in areas) {
                val xLo = minOf(area.xMin, area.xMax)
                val xHi = maxOf(area.xMin, area.xMax)
                val zLo = minOf(area.zMin, area.zMax)
                val zHi = maxOf(area.zMin, area.zMax)
                if (eye.x in xLo..xHi && eye.z in zLo..zHi) return spot
            }
            return NONE
        }
    }
}