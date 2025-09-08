package com.dji.bridgeinspector

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.ux.core.widget.fpv.FPVWidget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.graphics.PointF
import kotlinx.coroutines.cancel
import org.json.JSONObject

open class MainActivity : AppCompatActivity(), DroneDataListener, WebSocketManager.WebSocketListener {

    private val TAG = "MainActivity"

    private lateinit var locationManager: LocationManager
    private val webSocketManager = WebSocketManager()
    private var primaryFpvWidget: FPVWidget? = null
    private var waypointWidget: WaypointOverlayWidget? = null

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())



//    private var secondaryFpvWidget: FPVWidget? = null
//    private var pos: PointF? = PointF(515.80f, 34.10f)
//    private var pos: PointF? = PointF(1040.00f, 533.35f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize
        setContentView(R.layout.live_feed2)

        locationManager = LocationManager()
        locationManager.listener = this // Set MainActivity as the listener

        initUI()

        // Setup WebSocket Manager with a listener
        webSocketManager.listener = this
        webSocketManager.connect("10.194.245.135")

        // Read location information using locationManager
        mainScope.launch {
            (application as MyApplication).connectionState.collect { event ->
                when (event) {
                    is ConnectionEvent.ProductConnected -> {
                        Log.i(TAG, "Product Connected! Starting listeners.")
                        locationManager.startListening()
                    }
                    is ConnectionEvent.ProductDisconnected -> {
                        Log.i(TAG, "Product Disconnected! Stopping listeners.")
                        locationManager.stopListening()
                    }
                }
            }
        }
    }

    private fun initUI() {
        primaryFpvWidget = findViewById(R.id.widget_primary_fpv)
        waypointWidget = findViewById(R.id.waypoint)
        primaryFpvWidget?.updateVideoSource(ComponentIndexType.LEFT_OR_MAIN)
//        waypointWidget?.update(pos)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the listener when the activity is destroyed
        locationManager.stopListening()
        webSocketManager.disconnect()
        mainScope.cancel() // Cancel the coroutine scope
    }

    override fun onDroneDataUpdated(data: DroneData) {
        Log.i(TAG, "onDroneDataUpdated: Preparing to send data via WebSocket.")
        // Create JSON Object
        val jsonObject = JSONObject().apply {
            put("drone_lat", data.latitude)
            put("drone_lon", data.longitude)
            put("drone_alt", data.altitude)
            put("yaw", data.yaw)
            put("pitch", data.pitch)
            put("roll", data.roll)
        }
        // Send data through the WebSocket
        webSocketManager.sendMessage(jsonObject.toString())
    }

    override fun onMessage(point: Pair<Float, Float>) {
        // We received the calculated (u, v) point.
        // Update the UI on the main thread.
        runOnUiThread {
            waypointWidget?.update(PointF(point.first, point.second))
        }
    }

    override fun onConnectionError() {
        // Handle connection errors, e.g., show a toast message
        runOnUiThread {
            Log.e(TAG, "WebSocket connection failed")
        }
    }
}