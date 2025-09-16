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
import com.dji.bridgeinspector.Legacy.ScreenCoordinates
import kotlinx.coroutines.cancel

open class MainActivity : AppCompatActivity(), DroneDataListener {

    private val TAG = "MainActivity"

    private lateinit var locationManager: LocationManager
    private var primaryFpvWidget: FPVWidget? = null
    private var waypointWidget: WaypointOverlayWidget? = null

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize
        setContentView(R.layout.live_feed2)

        locationManager = LocationManager()
        locationManager.listener = this // Set MainActivity as the listener

        initUI()

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
        mainScope.cancel() // Cancel the coroutine scope
    }

    /**
     * Receives the final calculated screen coordinates directly from LocationManager.
     */
    override fun onScreenCoordinatesUpdated(coords: ScreenCoordinates?) {
        // calculation is done already, just need to update UI
        runOnUiThread {
            if (coords != null) {
                // update waypoint overlay with correct coordinates and radius
                val point = PointF(coords.u.toFloat(), coords.v.toFloat())
                val radius = coords.radius.toFloat()
                waypointWidget?.update(point, radius)
                Log.d(TAG, "UI Updated with: u=${point.x}, v=${point.y}, radius=$radius")
            } else {
                // Optionally, hide the waypoint if the calculation fails (e.g., target is behind drone).
                waypointWidget?.update(null, 0f)
                Log.w(TAG, "Received null coordinates, hiding waypoint.")
            }
        }
    }
}