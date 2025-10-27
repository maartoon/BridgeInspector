package com.dji.bridgeinspector
import com.google.ar.sceneform.math.Vector3

/**
 * Stores the static pose for a single waypoint.
 * @param position The (x, y, z) location of the waypoint.
 * @param direction The (vx, vy, vz) normalized direction vector to look in.
 */
data class WaypointPose(
    val position: Vector3,
    val direction: Vector3
)