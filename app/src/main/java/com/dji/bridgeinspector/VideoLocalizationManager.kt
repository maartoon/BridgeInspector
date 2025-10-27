package com.dji.bridgeinspector

import android.content.Context
import android.util.Log
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import java.io.BufferedReader
import java.io.InputStreamReader

class VideoLocalizationManager(
    context: Context,
    fileResourceId: Int // e.g., R.raw.pose_data
) {
    private val TAG = "VideoLocalizationManager"
    var listener: DroneDataListener? = null
    var syntheticListener: SyntheticListener? = null // This can be removed, but we'll leave it

    private val poses = mutableListOf<PoseData>()

    // --- MODIFIED ---
    // This now holds the FULL list of waypoint poses
    var targetWaypoints: List<WaypointPose> = listOf()

    // This holds the INDEX of the *currently active* target
    var currentTargetIndex: Int = -1

    // Camera intrinsics (hardcoded for now)
    private val fx = 1385.6
    private val fy = 1385.6
    private val screenWidth = 1920.0
    private val screenHeight = 1080.0
    private val cx = screenWidth / 2
    private val cy = screenHeight / 2

    init {
        loadPoseData(context, fileResourceId)
    }

    private fun loadPoseData(context: Context, resourceId: Int) {
        // ... (This function remains unchanged) ...
        try {
            val inputStream = context.resources.openRawResource(resourceId)
            val reader = BufferedReader(InputStreamReader(inputStream))

            reader.readLines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 7) {
                    poses.add(
                        PoseData(
                            timestamp = parts[0].toDouble(),
                            position = Vector3(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()),
                            direction = Vector3(parts[4].toFloat(), parts[5].toFloat(), parts[6].toFloat())
                        )
                    )
                } else {
                    Log.w(TAG, "Skipping malformed line: $line")
                }
            }
            Log.i(TAG, "Successfully loaded ${poses.size} poses.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pose data", e)
        }
    }

    private fun findPoseForTime(currentTimeSeconds: Double): PoseData? {
        // ... (This function remains unchanged) ...
        if (poses.isEmpty()) return null
        return poses.minByOrNull { kotlin.math.abs(it.timestamp - currentTimeSeconds) }
    }

    /**
     * This is the main update function, called by MainActivity's playback loop.
     */
    fun updateForTime(currentTimeSeconds: Double) {
        // Get the VIDEO'S current pose
        val currentVideoPose = findPoseForTime(currentTimeSeconds)
        if (currentVideoPose == null) {
            Log.w(TAG, "No pose found for time: $currentTimeSeconds")
            return
        }

        // --- 1. Update the Synthetic 3D View (REMOVED) ---
        // We no longer update the 3D view from the video's pose.
        // MainActivity will set it manually when the button is pressed.

        // --- 2. Update the 2D Waypoint Projection ---

        // Check if the currentTargetIndex is valid
        val target = if (currentTargetIndex >= 0 && currentTargetIndex < targetWaypoints.size) {
            targetWaypoints[currentTargetIndex]
        } else {
            null // No valid target selected
        }

        if (target == null) {
            listener?.onScreenCoordinatesUpdated(null) // No target, hide waypoint
            return
        }

        // Project the TARGET'S position using the VIDEO'S pose
        val cameraPosition = currentVideoPose.position
        val cameraRotation = Quaternion.lookRotation(currentVideoPose.direction, Vector3.up())
        val targetPosition = target.position // Get the position of the waypoint

        // Convert Sceneform 'Vector3' to 'Point3D' for projection
        val cameraPosAsPoint3D = Point3D(
            cameraPosition.x.toDouble(),
            cameraPosition.y.toDouble(),
            cameraPosition.z.toDouble()
        )
        val targetAsPoint3D = Point3D(
            targetPosition.x.toDouble(),
            targetPosition.y.toDouble(),
            targetPosition.z.toDouble()
        )

        val screenCoords = WaypointProjection.projectWorldToScreen(
            cameraPosition = cameraPosAsPoint3D,
            cameraRotation = cameraRotation,
            targetWorldPosition = targetAsPoint3D,
            fx, fy, cx, cy
        )

        listener?.onScreenCoordinatesUpdated(screenCoords)
    }

    /**
     * Public function to tell the manager which waypoint to focus on.
     */
    fun setTargetIndex(index: Int) {
        // --- MODIFIED ---
        if (index >= 0 && index < targetWaypoints.size) {
            currentTargetIndex = index
        } else {
            currentTargetIndex = -1 // No target
        }
    }
}