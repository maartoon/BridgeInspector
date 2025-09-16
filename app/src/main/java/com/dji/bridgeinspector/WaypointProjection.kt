package com.dji.bridgeinspector
import android.util.Log
import com.dji.bridgeinspector.Legacy.ScreenCoordinates
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

    /**
     * Converts Geodetic coordinates (latitude, longitude, altitude) to
     * Earth-Centered, Earth-Fixed (ECEF) coordinates.
     */
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

    /**
     * Converts a target's GPS coordinates to a local East-North-Up (ENU) frame
     * relative to the drone's position.
     * @return A Point3D representing the target's position in the local ENU frame (x=East, y=North, z=Up).
     */
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

        // Convert ENU to NED
        // NED(x, y, z) = (North, East, Down)
        // ENU(x, y, z) = (East, North, Up)
        // Therefore: NED_x = ENU_y, NED_y = ENU_x, NED_z = -ENU_z
        return Point3D(-n, e, -u)
    }

    /**
     * Transforms a point from the world ENU frame to the drone's local frame.
     * The drone's frame is typically X-forward, Y-right, Z-down.
     * @param target The point in the world (ENU) frame.
     * @param yaw, pitch, roll The drone's orientation in degrees.
     * @return The point in the drone's coordinate frame.
     */
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

    /**
     * Transforms a point from the drone's frame to the camera's frame.
     * Drone: X-forward, Y-right, Z-down
     * Camera: X-right, Y-down, Z-forward
     * @param pointDrone The point in the drone's coordinate frame.
     * @return The point in the camera's coordinate frame.
     */
    fun droneToCamera(pointDrone: Point3D): Point3D {
        // This matrix swaps axes from drone to camera coordinates:
        // Camera X = Drone Y
        // Camera Y = Drone Z
        // Camera Z = Drone X
        return Point3D(pointDrone.y, pointDrone.z, pointDrone.x)
    }

    /**
     * Projects a 3D point from the camera frame to 2D pixel coordinates.
     * @param pointCam The 3D point in the camera's frame.
     * @param fx, fy The camera's focal lengths in pixels.
     * @param cx, cy The camera's principal point (image center) in pixels.
     * @return The 2D pixel coordinates (u, v).
     */
    fun cameraProject(pointCam: Point3D, fx: Double, fy: Double, cx: Double, cy: Double): Point2D? {
        val (xc, yc, zc) = pointCam
        if (zc <= 0.0) {
//            Log.i(TAG, "Zc is negative. Cannot project.")
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
        // convert target's GPS to local ENU frame relative to the drone
        val targetLocal = gpsToLocal(
            droneData.latitude, droneData.longitude, droneData.altitude,
            targetLat, targetLon, targetAlt
        )

        // transform local ENU frame to drone's body frame
        val pointDrone = worldToDrone(
            targetLocal, droneData.yaw, droneData.pitch, droneData.roll
        )

        // transform drone body frame to camera frame
        val pointCamera = droneToCamera(pointDrone)

        // project 3D camera point to 2D screen coordinates
        val screenCoords = cameraProject(
            pointCamera,
            fx,
            fy,
            cx,
            cy
        ) ?: return null // return null if projection fails

        // calculate the radius based on depth (Zc), with a minimum value
        val zc = pointCamera[2] // Z coordinate in camera frame is depth
        val radius = maxOf(10.0, 500.0 / zc)
        Log.i(TAG, "Screen Coordinates: ${screenCoords.u}, ${screenCoords.v}, ${radius}")

        return ScreenCoordinates(u = screenCoords.u, v = screenCoords.v, radius = radius)
    }
}