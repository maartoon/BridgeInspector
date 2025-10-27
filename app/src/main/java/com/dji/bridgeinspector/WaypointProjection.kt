package com.dji.bridgeinspector
import android.util.Log
import com.dji.bridgeinspector.Legacy.ScreenCoordinates
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// --- ADD SCENEFORM IMPORTS ---
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3

// Data class for a 3D point/vector for better readability
data class Point3D(val x: Double, val y: Double, val z: Double) {
    // Allows accessing components by index like an array
    operator fun get(index: Int): Double {
        return when (index) {
            0 -> x
            1 -> y
            2 -> z
            else -> throw IndexOutOfBoundsException("Invalid index for Point3D")
        }
    }
}

// Data class for the final output
data class ScreenCoordinates(
    val u: Double,
    val v: Double,
    val radius: Double
)

// Data class for 2D pixel coordinates
data class Point2D(val u: Double, val v: Double)

/**
 * A utility object for matrix and vector operations.
 */
object MatrixHelper {
    // ... (multiply functions remain unchanged) ...
    /**
     * Multiplies a 3x3 matrix by a 3D vector.
     */
    fun multiply(matrix: Array<DoubleArray>, vector: Point3D): Point3D {
        val result = DoubleArray(3)
        for (i in 0..2) {
            result[i] = matrix[i][0] * vector.x + matrix[i][1] * vector.y + matrix[i][2] * vector.z
        }
        return Point3D(result[0], result[1], result[2])
    }

    /**
     * Multiplies two 3x3 matrices (A x B).
     */
    fun multiply(matrixA: Array<DoubleArray>, matrixB: Array<DoubleArray>): Array<DoubleArray> {
        val result = Array(3) { DoubleArray(3) }
        for (i in 0..2) {
            for (j in 0..2) {
                for (k in 0..2) {
                    result[i][j] += matrixA[i][k] * matrixB[k][j]
                }
            }
        }
        return result
    }
}

/**
 * A utility object for coordinate system transformations.
 */
object WaypointProjection {
    private val TAG = "WaypointProjection"

    // WGS-84 ellipsoid parameters
    private const val WGS84_A = 6378137.0 // Semi-major axis
    private const val WGS84_F = 1.0 / 298.257223563 // Flattening

    // --- (geodeticToEcef, gpsToLocal, worldToDrone, droneToCamera, cameraProject) ---
    // --- All your original functions remain here, unchanged ---

    private fun geodeticToEcef(lat: Double, lon: Double, alt: Double): Point3D {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val eSq = WGS84_F * (2 - WGS84_F)
        val n = WGS84_A / sqrt(1 - eSq * sin(latRad).pow(2))

        val x = (n + alt) * cos(latRad) * cos(lonRad)
        val y = (n + alt) * cos(latRad) * sin(lonRad)
        val z = (n * (1 - eSq) + alt) * sin(latRad)

        return Point3D(x, y, z)
    }

    fun gpsToLocal(
        droneLat: Double, droneLon: Double, droneAlt: Double,
        targetLat: Double, targetLon: Double, targetAlt: Double
    ): Point3D {
        val ecefDrone = geodeticToEcef(droneLat, droneLon, droneAlt)
        val ecefTarget = geodeticToEcef(targetLat, targetLon, targetAlt)

        val diff = Point3D(
            ecefTarget.x - ecefDrone.x,
            ecefTarget.y - ecefDrone.y,
            ecefTarget.z - ecefDrone.z
        )

        val latRad = Math.toRadians(droneLat)
        val lonRad = Math.toRadians(droneLon)
        val sLat = sin(latRad)
        val cLat = cos(latRad)
        val sLon = sin(lonRad)
        val cLon = cos(lonRad)

        val e = -sLon * diff.x + cLon * diff.y
        val n = -sLat * cLon * diff.x - sLat * sLon * diff.y + cLat * diff.z
        val u = cLat * cLon * diff.x + cLat * sLon * diff.y + sLat * diff.z

        return Point3D(n, e, -u)
    }

    fun worldToDrone(target: Point3D, yaw: Double, pitch: Double, roll: Double): Point3D {
        val y = Math.toRadians(-yaw)
        val p = Math.toRadians(-pitch)
        val r = Math.toRadians(-roll)

        val cY = cos(y)
        val sY = sin(y)
        val cP = cos(p)
        val sP = sin(p)
        val cR = cos(r)
        val sR = sin(r)

        // Rotation matrices (negative angles for world-to-drone)
        val rYaw = arrayOf(
            doubleArrayOf(cY, -sY, 0.0),
            doubleArrayOf(sY, cY, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        )
        val rPitch = arrayOf(
            doubleArrayOf(cP, 0.0, sP),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(-sP, 0.0, cP)
        )
        val rRoll = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, cR, -sR),
            doubleArrayOf(0.0, sR, cR)
        )

        // Combine rotations: R = R_roll * R_pitch * R_yaw
        val rCombined = MatrixHelper.multiply(rRoll, MatrixHelper.multiply(rPitch, rYaw))

        return MatrixHelper.multiply(rCombined, target)
    }

    fun droneToCamera(pointDrone: Point3D): Point3D {
        return Point3D(pointDrone.y, pointDrone.z, pointDrone.x)
    }

    fun cameraProject(pointCam: Point3D, fx: Double, fy: Double, cx: Double, cy: Double): Point2D? {
        val (xc, yc, zc) = pointCam
        if (zc <= 0.0) {
            return null
        }
        val u = fx * xc / zc + cx
        val v = fy * yc / zc + cy
        return Point2D(u, v)
    }

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
        val targetLocal = gpsToLocal(
            droneData.latitude, droneData.longitude, droneData.altitude,
            targetLat, targetLon, targetAlt
        )
        val pointDrone = worldToDrone(
            targetLocal, droneData.yaw, droneData.pitch, droneData.roll
        )
        val pointCamera = droneToCamera(pointDrone)
        val screenCoords = cameraProject(
            pointCamera,
            fx,
            fy,
            cx,
            cy
        ) ?: return null
        val zc = pointCamera[2]
        val radius = maxOf(10.0, 500.0 / zc)
        Log.i(TAG, "Screen Coordinates: ${screenCoords.u}, ${screenCoords.v}, ${radius}")
        return ScreenCoordinates(u = screenCoords.u, v = screenCoords.v, radius = radius)
    }

    // --- ADD HELPER FUNCTIONS / EXTENSIONS ---

    /** Converts our Point3D to Sceneform's Vector3 */
    private fun Point3D.toVector3(): Vector3 {
        return Vector3(this.x.toFloat(), this.y.toFloat(), this.z.toFloat())
    }

    /** Helper for Point3D subtraction */
    private fun Point3D.subtract(other: Point3D): Point3D {
        return Point3D(this.x - other.x, this.y - other.y, this.z - other.z)
    }

    // --- ADD THE NEW PROJECTION FUNCTION ---

    /**
     * Projects a 3D point from the *world* coordinate system to 2D pixel coordinates,
     * given a camera's pose in that same world.
     *
     * @param cameraPosition The camera's (x,y,z) in the world
     * @param cameraRotation The camera's rotation in the world
     * @param targetWorldPosition The target's (x,y,z) in the world
     * @return The 2D screen coordinates, or null if the point is behind the camera.
     */
    fun projectWorldToScreen(
        cameraPosition: Point3D,
        cameraRotation: Quaternion,
        targetWorldPosition: Point3D,
        fx: Double, fy: Double, cx: Double, cy: Double
    ): ScreenCoordinates? {

        // 1. Find target's position relative to the camera in world space
        val targetRelativeWorld = targetWorldPosition.subtract(cameraPosition)

        // 2. Rotate this vector from world-space into the camera's local-space.
        //    We use the inverse rotation for this.
        val targetInCameraFrameVec = Quaternion.rotateVector(
            cameraRotation.inverted(), // <-- This is the fix
            targetRelativeWorld.toVector3() // Convert to Vector3 for rotation
        )

        // 3. The `lookRotation` quaternion (used in VideoLocalizationManager)
        //    creates a frame where:
        //    +X is right, +Y is up, +Z is forward (out of the lens)
        //
        //    Our `cameraProject` function expects a standard computer vision frame:
        //    +X is right, +Y is *down*, +Z is forward
        //
        //    Therefore, we must flip the Y-axis of the resulting vector.
        val pointForProjection = Point3D(
            targetInCameraFrameVec.x.toDouble(),
            -targetInCameraFrameVec.y.toDouble(), // Flip Y from "up" to "down"
            targetInCameraFrameVec.z.toDouble()
        )

        // 4. Project this 3D camera-space point to 2D pixels
        //    We can reuse the existing cameraProject function
        val screenPoint = cameraProject(
            pointForProjection,
            fx, fy, cx, cy
        ) ?: return null // Return null if projection fails (e.g., behind camera)

        // 5. Calculate radius based on depth (Zc)
        val zc = pointForProjection.z // Z coordinate in camera frame is depth
        val radius = maxOf(10.0, 500.0 / zc)
        Log.d(TAG, "Projected Coords: u=${screenPoint.u}, v=${screenPoint.v}, r=$radius")

        return ScreenCoordinates(u = screenPoint.u, v = screenPoint.v, radius = radius)
    }
}