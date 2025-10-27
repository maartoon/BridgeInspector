package com.dji.bridgeinspector

import com.google.ar.sceneform.math.Vector3

/**
 * Holds a single pose from the localization file.
 * @param timestamp The time in seconds from the start of the video.
 * @param position The (x, y, z) position in the world coordinate system.
 * @param direction The (vx, vy, vz) normalized direction vector the camera is pointing.
 */
data class PoseData(
    val timestamp: Double,
    val position: Vector3,
    val direction: Vector3
)