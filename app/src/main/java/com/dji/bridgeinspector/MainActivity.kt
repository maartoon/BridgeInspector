package com.dji.bridgeinspector


import LocationManager
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.ux.core.widget.fpv.FPVWidget
import com.dji.bridgeinspector.WaypointOverlayWidget
import dji.v5.manager.SDKManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


open class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var locationManager: LocationManager
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var primaryFpvWidget: FPVWidget? = null
//    private var secondaryFpvWidget: FPVWidget? = null
    private var waypointWidget: WaypointOverlayWidget? = null
    private var pos: PointF? = PointF(950f, 500f)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize
        setContentView(R.layout.live_feed2)
        locationManager = LocationManager()
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
        waypointWidget?.update(pos)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the listener when the activity is destroyed
        locationManager.stopListening()
    }


}