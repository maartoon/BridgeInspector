package com.dji.bridgeinspector

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.value.camera.CameraOpticalZoomSpec
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import android.util.Log

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
    fun onDroneDataUpdated(data: DroneData)
}

class LocationManager {

    private val TAG = "com.dji.bridgeinspector.LocationManager"

    var listener: DroneDataListener? = null // Add a listener property

    private var latestLatitude: Double? = null
    private var latestLongitude: Double? = null
    private var latestAltitude: Double? = null
    private var latestYaw: Double? = null
    private var latestPitch: Double? = null
    private var latestRoll: Double? = null

    // listen for drone updates
    fun startListening() {
        // gps information - lat, long, alt
        val locationKey = DJIKey.create(FlightControllerKey.KeyAircraftLocation3D)

        // drone information - pitch, roll, yaw
        val attitudeKey = DJIKey.create(FlightControllerKey.KeyAircraftAttitude)

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

        // listen for attitude updates
        KeyManager.getInstance().listen(attitudeKey, this) { _, attitude ->
            attitude?.let {
                latestYaw = it.yaw
                latestPitch = it.pitch
                latestRoll = it.roll
                publishData()

                // log values for now
                Log.i(TAG, "Drone Attitude: Yaw: $latestYaw, Pitch: $latestPitch, Roll: $latestRoll")
            }
        }
    }

    private fun publishData() {
        // Check if all data points are available
        val lat = latestLatitude
        val lon = latestLongitude
        val alt = latestAltitude
        val yaw = latestYaw
        val pitch = latestPitch
        val roll = latestRoll

        if (lat != null && lon != null && alt != null && yaw != null && pitch != null && roll != null) {
            val droneData = DroneData(lat, lon, alt, yaw, pitch, roll)
            listener?.onDroneDataUpdated(droneData)
        }
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
