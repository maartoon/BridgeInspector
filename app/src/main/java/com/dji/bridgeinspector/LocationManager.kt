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

data class DroneData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val yaw: Double,
    val pitch: Double,
    val roll: Double
)

// 2. Add a listener interface
interface DroneDataListener {
    fun onScreenCoordinatesUpdated(coords: ScreenCoordinates?)
}

interface SyntheticListener {
    fun onCameraTransformUpdated(position: Vector3, rotation: Quaternion)
}

class LocationManager {

    private val TAG = "LocationManager"

    var listener: DroneDataListener? = null // Add a listener property
    var syntheticListener: SyntheticListener? = null // Add a listener property

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
    private val originUTM = doubleArrayOf(395893.73, 4441058.45, 194.46)

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

//         listen for gimbal attitude updates
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
    }

    private fun publishData() {
        val lat = latestLatitude?: 0.0
        val lon = latestLongitude?: 0.0
        val alt = latestAltitude?: 0.0
        val yaw = latestYaw
        val pitch = latestPitch
        val roll = latestRoll

        // Convert UTM location to the local coordinates of the model
        if (syntheticListener != null) {
            val droneUTM = gpsToUtm(lat, lon) // Placeholder

            val cameraX = (droneUTM[0] - originUTM[0]).toFloat()
            val cameraY = (droneUTM[1] - originUTM[1]).toFloat()
            val cameraZ = (alt - originUTM[2]).toFloat()
            val localPosition = Vector3(cameraX, cameraY, cameraZ)

            val localRotation = Quaternion.eulerAngles(pitch?.let { yaw?.let { it1 -> roll?.let { it2 -> Vector3(it.toFloat(), it1.toFloat(), it2.toFloat()) } } })

            // Notify the 3D listener
            syntheticListener?.onCameraTransformUpdated(localPosition, localRotation)
        }

        // Pass information from drone for waypoint calculations
        if (yaw != null && pitch != null && roll != null) {
            val droneData = DroneData(lat, lon, alt, yaw, pitch, roll)

            // TODO: implement multiple waypoint entries capability
            val targetLat = 40.11526039934174
            val targetLon = -88.22506985710966
            val targetAlt = 0.0

            val fx = 1385.6
            val fy = 1385.6
            val screenWidth = 1920.0
            val screenHeight = 1080.0
            val cx = screenWidth / 2
            val cy = screenHeight / 2

            // perform calulation using waypoint projection
            val screenCoordinates = WaypointProjection.processDroneData(
                droneData, targetLat, targetLon, targetAlt, fx, fy, cx, cy
            )

            // pass result to listener
            if (screenCoordinates != null) {
                Log.i(TAG, "Calculation successful: u=${screenCoordinates.u}, v=${screenCoordinates.v}, radius=${screenCoordinates.radius}")
            } else {
                Log.w(TAG, "Calculation failed. Point may be behind camera.")
            }
            listener?.onScreenCoordinatesUpdated(screenCoordinates)
        }
    }

    /**
     * Converts WGS84 GPS coordinates (Latitude, Longitude) to UTM coordinates (Easting, Northing).
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
