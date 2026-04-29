package org.kyowa.familyaddons.features.pearl

import net.minecraft.util.math.Vec3d

/**
 * A "double pearl" route — describes a chained pearl-throw setup where the
 * player is in [pre] (their current Pre area), throws to [drop] (the next
 * Pre area), with the pearl's mid-air handoff at [location].
 *
 * Used to render a fixed-position waypoint at the handoff location for
 * specific from→to combinations during Kuudra Phase 1. Coordinates and
 */
data class DoublePearl(
    val id: String,
    val location: Vec3d,
    val pre: Pre,
    val drop: Pre,
)

object DoublePearls {
    val dPearls: Map<String, DoublePearl> = linkedMapOf(
        "SLASH->X_CANNON"     to DoublePearl("SLASH->X_CANNON",     Vec3d(-128.5, 79.0, -113.5), Pre.SLASH,    Pre.X_CANNON),
        "SLASH->SQUARE"       to DoublePearl("SLASH->SQUARE",       Vec3d(-139.5, 77.0,  -86.5), Pre.SLASH,    Pre.SQUARE),
        "X->SQUARE"           to DoublePearl("X->SQUARE",           Vec3d(-139.5, 77.0,  -86.5), Pre.X,        Pre.SQUARE),
        "TRIANGLE->X_CANNON"  to DoublePearl("TRIANGLE->X_CANNON",  Vec3d(-127.5, 79.0, -123.5), Pre.TRIANGLE, Pre.X_CANNON),
        "EQUALS->SHOP"        to DoublePearl("EQUALS->SHOP",        Vec3d( -74.5, 79.0, -134.5), Pre.EQUALS,   Pre.SHOP),
    )
}
