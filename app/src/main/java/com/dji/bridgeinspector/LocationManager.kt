package com.dji.bridgeinspector

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.value.camera.CameraOpticalZoomSpec
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import android.util.Log
import com.dji.bridgeinspector.Legacy.ScreenCoordinates
import dji.sdk.keyvalue.key.GimbalKey.KeyGimbalAttitude

import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3

// Jcoord library import for GPS to UTM conversion
// The CORRECT Proj4J library imports
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateReferenceSystem
import org.locationtech.proj4j.CoordinateTransform
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate

// Data class to hold current drone information
data class DroneData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val yaw: Double,
    val pitch: Double,
    val roll: Double
)

// Data class to hold all info for a single target
data class TargetViewpoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val yaw: Double,
    val pitch: Double,
    val roll: Double
)

// Enum to control synthetic camera behavior
enum class SyntheticMode {
    FOLLOW_DRONE,
    STATIC_VIEWPOINT
}

// 2. Add a listener interface
interface DroneDataListener {
    fun onProjectionResult(result: ProjectionResult?) // NEW
}

interface SyntheticListener {
    fun onCameraTransformUpdated(position: Vector3, rotation: Quaternion)
    fun onLiveDroneTransformUpdated(position: Vector3, rotation: Quaternion)
    fun onPhotoTaken(position: Vector3, rotation: Quaternion) // NEW LISTENER
}

class LocationManager {

    private val TAG = "LocationManager"

    private val MODEL_OFFSET_X = 4.98f
    private val MODEL_OFFSET_Y = 82.48f

    var listener: DroneDataListener? = null // Add a listener property
    var syntheticListener: SyntheticListener? = null // Add a listener property

    var currentTarget: TargetViewpoint? = null // current target viewpoint for projection
    var syntheticMode: SyntheticMode = SyntheticMode.FOLLOW_DRONE // current mode of synthetic camera

    private var latestLatitude: Double? = null
    private var latestLongitude: Double? = null
    private var latestAltitude: Double? = null
    private var latestYaw: Double? = null
    private var latestPitch: Double? = null
    private var latestRoll: Double? = null


    // Proj4J Setup: Convert drone's GPS location to UTM coordinates
    private val ctFactory = CoordinateTransformFactory()
    private val csFactory = CRSFactory()
    // Source CRS: WGS84 (standard GPS)
    private val wgs84: CoordinateReferenceSystem = csFactory.createFromName("EPSG:4326")
    // Target CRS: UTM Zone 16N
    private val utm16n: CoordinateReferenceSystem = csFactory.createFromName("EPSG:32616")
    private val wgsToUtm: CoordinateTransform = ctFactory.createTransform(wgs84, utm16n)
    private val originUTM = doubleArrayOf(395535.14, 4441177.50, 197.5)
    // listen for drone updates
    fun startListening() {
        // gps information - lat, long, alt
        val locationKey = DJIKey.create(FlightControllerKey.KeyAircraftLocation3D)

        // drone information - pitch, roll, yaw
        val attitudeKey = DJIKey.create(FlightControllerKey.KeyAircraftAttitude)

        // gimbal attitude information - pitch, roll, yaw
        val gimbalAttitudeKey = DJIKey.create(KeyGimbalAttitude)

        // debugging: checking for gps signal
        val satelliteCountKey = DJIKey.create(FlightControllerKey.KeyGPSSatelliteCount)
        val gpsSignalKey = DJIKey.create(FlightControllerKey.KeyGPSSignalLevel)

        // media file key (to observe when user takes an image)
        val mediaFileKey = DJIKey.create(CameraKey.KeyNewlyGeneratedMediaFile)

        KeyManager.getInstance().listen(gpsSignalKey, this) { _, gpsSignal ->
            Log.i(TAG, "GPS Signal: $gpsSignal")
        }

        KeyManager.getInstance().listen(satelliteCountKey, this) { _, satelliteCount ->
            Log.i(TAG, "Number of satellites = $satelliteCount")
            if (satelliteCount == null) {
                Log.w(TAG, "Satellite count is null. GPS may still be initializing.")
                return@listen
            }

            if (satelliteCount < 6) {
                // Log an error if the satellite count is too low for a stable GPS lock
                Log.e(TAG, "Poor GPS Signal. Satellites: $satelliteCount. Waiting for lock...")
            } else {
                Log.i(TAG, "Good GPS Signal. Satellites: $satelliteCount")
            }
        }

        // listen for location updates (provides old and new value, but we only care about new value)
        KeyManager.getInstance().listen(locationKey, this) { _, location ->
            location?.let {
                latestLatitude = it.latitude
                latestLongitude = it.longitude
                latestAltitude = it.altitude
                publishData()

                // log these values for testing, would want to process these values in the future
                Log.i(TAG, "Drone GPS: Lat: $latestLatitude, Lon: $latestLongitude, Alt: $latestAltitude")
            }
        }

        // listen for attitude updates (REPLACED WITH GIMBAL ATTITUDE)
        KeyManager.getInstance().listen(attitudeKey, this) { _, attitude ->
            attitude?.let {
//                latestYaw = it.yaw
//                latestPitch = it.pitch
//                latestRoll = it.roll
                publishData()

                // log values for now
                Log.i(TAG, "Drone Attitude: Yaw: $latestYaw")
            }
        }

        // listen for gimbal attitude updates
        KeyManager.getInstance().listen(gimbalAttitudeKey, this) { _, attitude ->
            attitude?.let {
                latestYaw = it.yaw
                latestPitch = it.pitch
                latestRoll = it.roll
                publishData()

                // log values for now
                Log.i(TAG, "Gimbal Attitude: Pitch: $latestPitch, Roll: $latestRoll")
            }
        }

        // Listen for new image generation
        KeyManager.getInstance().listen(mediaFileKey, this) { _, newMedia ->
            newMedia?.let {
                Log.i(TAG, "New media file generated! Capturing pose for coverage mapping.")

                // Grab the pose exactly when the photo event happens
                val lat = latestLatitude ?: return@listen
                val lon = latestLongitude ?: return@listen
                val alt = latestAltitude ?: return@listen
                val yaw = latestYaw ?: return@listen
                val pitch = latestPitch ?: return@listen
                val roll = latestRoll ?: return@listen

                val droneUTM = gpsToUtm(lat, lon)
                val cameraX = (droneUTM[0] - originUTM[0]).toFloat()
                val cameraY = (droneUTM[1] - originUTM[1]).toFloat()
                val cameraZ = (alt + originUTM[2]).toFloat()
                val localPosition =
                    Vector3(cameraX - MODEL_OFFSET_X, cameraY - MODEL_OFFSET_Y, cameraZ)

                val localRotation =
                    Quaternion.eulerAngles(Vector3(pitch.toFloat(), -yaw.toFloat(), roll.toFloat()))
                val preRotation = Quaternion.axisAngle(Vector3.right(), 90f)
                val finalRotation = Quaternion.multiply(preRotation, localRotation)

                // Pass this highly specific pose to the listener
                syntheticListener?.onPhotoTaken(localPosition, finalRotation)
            }
        }
    }

    private fun publishData() {
        val lat = latestLatitude?: 0.0
        val lon = latestLongitude?: 0.0
        val alt = latestAltitude?: 0.0
        val yaw = latestYaw
        val pitch = latestPitch
        val roll = latestRoll

        // Convert UTM location to the local coordinates of the model
        // Synthetic video mode: camera's movement directly follows that of the drone
        if (syntheticListener != null && yaw != null && pitch != null && roll != null) {
            val droneUTM = gpsToUtm(lat, lon) // Placeholder

            val cameraX = (droneUTM[0] - originUTM[0]).toFloat()
            val cameraY = (droneUTM[1] - originUTM[1]).toFloat()
            val cameraZ = (alt + originUTM[2]).toFloat()
            val localPosition = Vector3(cameraX - MODEL_OFFSET_X, cameraY - MODEL_OFFSET_Y, cameraZ)

            // may need modification depending on the model
            val localRotation = Quaternion.eulerAngles(Vector3(pitch.toFloat(), -yaw.toFloat(), roll.toFloat()))
            val preRotation = Quaternion.axisAngle(Vector3.right(), 90f)
            val finalRotation = Quaternion.multiply(preRotation, localRotation)

            // 1. ALWAYS send the live physical drone position to the CoverageManager
            syntheticListener?.onLiveDroneTransformUpdated(localPosition, finalRotation)

            // 2. ONLY move the visual SceneView camera to follow the drone if in FOLLOW_DRONE mode
            if (syntheticMode == SyntheticMode.FOLLOW_DRONE) {
                syntheticListener?.onCameraTransformUpdated(localPosition, finalRotation)
            }
        }

        // Pass information from drone for waypoint calculations
        if (yaw != null && pitch != null && roll != null) {
            val droneData = DroneData(lat, lon, alt, yaw, pitch, roll)

            // set target waypoint to current target
            val target = currentTarget
            if (target == null) {
                Log.w(TAG, "No target viewpoint set. Waypoint projection skipped.")
                listener?.onProjectionResult(null) // Hide waypoint
                return // No target, so skip calculation
            }

            val targetLat = target.latitude
            val targetLon = target.longitude
            val targetAlt = target.altitude

            val fx = 1385.6
            val fy = 1385.6
            val screenWidth = 1920.0
            val screenHeight = 1080.0
            val cx = screenWidth / 2
            val cy = screenHeight / 2

            // perform calulation using waypoint projection
            val projectionResult = WaypointProjection.processDroneData(
                droneData, targetLat, targetLon, targetAlt, fx, fy, cx, cy
            )

            // pass result to listener
            if (projectionResult.screenCoords != null) {
                Log.i(TAG, "Calculation successful: u=${projectionResult.screenCoords.u}, v=${projectionResult.screenCoords.v}, radius=${projectionResult.screenCoords.radius}")
            } else {
                Log.i(TAG, "Point may be behind camera.")
            }
            listener?.onProjectionResult(projectionResult)
        }
    }

    /**
     * Synthetic Image Feature
     * Manually calculates and pushes a synthetic camera update based on the
     * currentTarget's static position and orientation.
     */
    fun updateStaticSyntheticView() {
        val target = currentTarget
        if (target == null) {
            Log.w(TAG, "Cannot update static synthetic view: currentTarget is null.")
            return
        }
        if (syntheticListener == null) {
            Log.w(TAG, "Cannot update static synthetic view: syntheticListener is null.")
            return
        }

        Log.d(TAG, "Setting synthetic camera to static viewpoint: ${target.latitude}, ${target.longitude}")

        val targetUTM = gpsToUtm(target.latitude, target.longitude)

        val cameraX = (targetUTM[0] - originUTM[0]).toFloat()
        val cameraY = (targetUTM[1] - originUTM[1]).toFloat()
        // Use target's altitude for Z position in the synthetic world
        val cameraZ = (target.altitude + originUTM[2]).toFloat()
        val localPosition = Vector3(cameraX - MODEL_OFFSET_X, cameraY - MODEL_OFFSET_Y, cameraZ)

        // Use target's orientation
        val localRotation = Quaternion.eulerAngles(Vector3(target.pitch.toFloat(), -target.yaw.toFloat(), target.roll.toFloat()))
        val preRotation = Quaternion.axisAngle(Vector3.right(), 90f) // Keep the pre-rotation if it's needed for model orientation
        val finalRotation = Quaternion.multiply(preRotation, localRotation)

        syntheticListener?.onCameraTransformUpdated(localPosition, finalRotation)

//
//        // Only move the visual camera if we are in Follow Drone mode
//        if (syntheticMode == SyntheticMode.FOLLOW_DRONE) {
//            syntheticListener?.onCameraTransformUpdated(localPosition, finalRotation)
//        }
    }

    /**
     * Helper function to convert GPS coordinates to UTM coordinates
     * Uses the Jcoord library to handle the conversion.
     */
    private fun gpsToUtm(lat: Double, lon: Double): DoubleArray {
        // Proj4J uses (lon, lat) order for source coordinates
        val srcCoord = ProjCoordinate(lon, lat)
        val dstCoord = ProjCoordinate()
        // Perform the transformation
        wgsToUtm.transform(srcCoord, dstCoord)
        return doubleArrayOf(dstCoord.x, dstCoord.y) // x is East, y is North
    }

    // function to stop listening
    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
    }

    // fetch camera intrinsics
    fun getCameraIntrinsics() {
        val cameraIntrinsicsKey = DJIKey.create(CameraKey.KeyCameraOpticalZoomSpec)

        KeyManager.getInstance().getValue(cameraIntrinsicsKey, object : CommonCallbacks.CompletionCallbackWithParam<CameraOpticalZoomSpec> {
            override fun onSuccess(spec: CameraOpticalZoomSpec?) {
                // spec may be null
                spec?.let {
                    // todo: derive fx, fy from focal length ranges; cx, cy
                    val focalLengthMin = it.minFocalLength
                    val focalLengthMax = it.maxFocalLength
                    Log.i(TAG, "Camera Focal Length Range: $focalLengthMin - $focalLengthMax")
                }
            }

            override fun onFailure(error: IDJIError) {
                Log.e(TAG, "Failed to get camera intrinsics: ${error.description()}")
            }
        })
    }
}