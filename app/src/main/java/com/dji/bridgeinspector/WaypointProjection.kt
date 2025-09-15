package com.dji.bridgeinspector

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Data class to hold the final result
data class ScreenCoordinates(val u: Double, val v: Double, val radius: Double)

object WaypointProjection {

    // Earth constants

    private const val EARTH_RADIUS = 6378137.0 // WGS-84 semi-major axis

    /**
     * Converts Geodetic coordinates (lat, lon, alt) to a local East, North, Up (ENU) frame.
     * This is the Kotlin implementation of pymap3d.geodetic2enu.
     *
     * @param targetLat Latitude of the target point (degrees).
     * @param targetLon Longitude of the target point (degrees).
     * @param targetAlt Altitude of the target point (meters).
     * @param droneLat Latitude of the reference point (drone's location, degrees).
     * @param droneLon Longitude of the reference point (drone's location, degrees).
     * @param droneAlt Altitude of the reference point (drone's location, meters).
     * @return A DoubleArray containing the [x, y, z] coordinates in the local ENU frame.
     */
    private fun gpsToLocal(
        targetLat: Double, targetLon: Double, targetAlt: Double,
        droneLat: Double, droneLon: Double, droneAlt: Double
    ): DoubleArray {
        val latRad = Math.toRadians(droneLat)

        val deltaLat = Math.toRadians(targetLat - droneLat)
        val deltaLon = Math.toRadians(targetLon - droneLon)

        val y = deltaLat * EARTH_RADIUS // North
        val x = deltaLon * EARTH_RADIUS * cos(latRad) // East
        val z = targetAlt - droneAlt // Up

        return doubleArrayOf(x, y, z)
    }

    /**
     * Rotates a point from the world frame to the drone's body frame.
     * @param target The point in the local ENU frame [x, y, z].
     * @param yaw Drone's yaw in degrees.
     * @param pitch Drone's pitch in degrees.
     * @param roll Drone's roll in degrees.
     * @return The point's coordinates in the drone's frame.
     */
    private fun worldToDrone(target: DoubleArray, yaw: Double, pitch: Double, roll: Double): DoubleArray {
        val y = Math.toRadians(-yaw)
        val p = Math.toRadians(-pitch)
        val radius = Math.toRadians(-roll)

        // Yaw rotation matrix
        val rYaw = arrayOf(
            doubleArrayOf(cos(y), -sin(y), 0.0),
            doubleArrayOf(sin(y), cos(y), 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        )
        // Pitch rotation matrix
        val rPitch = arrayOf(
            doubleArrayOf(cos(p), 0.0, sin(p)),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(-sin(p), 0.0, cos(p))
        )
        // Roll rotation matrix
        val rRoll = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, cos(radius), -sin(radius)),
            doubleArrayOf(0.0, sin(radius), cos(radius))
        )

        // Combined rotation: R = R_roll * R_pitch * R_yaw
        val rPitchYaw = multiplyMatrices(rPitch, rYaw)
        val r = multiplyMatrices(rRoll, rPitchYaw)

        return multiplyMatrixVector(r, target)
    }

    /**
     * Rotates a point from the drone's body frame to the camera frame.
     * Assumes Camera X=Drone Y, Camera Y=Drone Z, Camera Z=Drone X.
     * @param pointDrone The point in the drone's frame.
     * @return The point's coordinates in the camera's frame.
     */
    private fun droneToCamera(pointDrone: DoubleArray): DoubleArray {
        // This matrix assumes Camera X=Drone Y, Camera Y=Drone Z, Camera Z=Drone X.
        // Verify this matches your drone's camera setup.
        val rDroneToCamera = arrayOf(
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(1.0, 0.0, 0.0)
        )
        return multiplyMatrixVector(rDroneToCamera, pointDrone)
    }

    /**
     * Projects a 3D point from the camera frame onto the 2D image plane.
     * @param pointCam The 3D point [Xc, Yc, Zc] in the camera's frame.
     * @param fx Horizontal focal length.
     * @param fy Vertical focal length.
     * @param cx Horizontal principal point offset.
     * @param cy Vertical principal point offset.
     * @return A pair of screen coordinates (u, v), or null if the point is behind the camera.
     */
    private fun cameraProject(pointCam: DoubleArray, fx: Double, fy: Double, cx: Double, cy: Double): Pair<Double, Double>? {
        val (xc, yc, zc) = pointCam
        if (zc <= 0.0) {
            // Point is behind the camera, cannot project.
            return null
        }
        val u = fx * xc / zc + cx
        val v = fy * yc / zc + cy
        return Pair(u, v)
    }

    /**
     * A public function that runs the entire calculation pipeline.
     */
    fun processDroneData(
        droneData: DroneData,
        targetLat: Double,
        targetLon: Double,
        targetAlt: Double,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double
    ): ScreenCoordinates? {
        // 1. Convert target's GPS to local ENU frame relative to the drone
        val targetLocal = gpsToLocal(
            targetLat, targetLon, targetAlt,
            droneData.latitude, droneData.longitude, droneData.altitude
        )

        // 2. Transform local frame to drone's body frame
        val pointDrone = worldToDrone(
            targetLocal, droneData.yaw, droneData.pitch, droneData.roll
        )

        // 3. Transform drone frame to camera frame
        val pointCamera = droneToCamera(pointDrone)

        // 4. Project 3D camera point to 2D screen coordinates
        val screenCoords = cameraProject(pointCamera, fx, fy, cx, cy) ?: return null

        // 5. Calculate the radius based on depth (Zc)
        val zc = pointCamera[2] // Z coordinate in camera frame is depth
        val radius = maxOf(10.0, 500.0 / zc)

        return ScreenCoordinates(u = screenCoords.first, v = screenCoords.second, radius = radius)
    }


    // --- Matrix Helper Functions ---

    private fun multiplyMatrices(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val result = Array(3) { DoubleArray(3) }
        for (i in 0..2) {
            for (j in 0..2) {
                for (k in 0..2) {
                    result[i][j] += a[i][k] * b[k][j]
                }
            }
        }
        return result
    }

    private fun multiplyMatrixVector(m: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        val result = DoubleArray(3)
        for (i in 0..2) {
            result[i] = m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2]
        }
        return result
    }
}