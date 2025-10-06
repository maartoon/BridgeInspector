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
import android.net.Uri
import android.widget.Toast
import com.dji.bridgeinspector.Legacy.ScreenCoordinates
import kotlinx.coroutines.cancel
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.SceneView
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable

open class MainActivity : AppCompatActivity(), DroneDataListener, SyntheticListener {

    private val TAG = "MainActivity"

    private lateinit var locationManager: LocationManager
    private var primaryFpvWidget: FPVWidget? = null
    private var waypointWidget: WaypointOverlayWidget? = null
    private lateinit var sceneView: SceneView

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize
        setContentView(R.layout.live_feed2)

        locationManager = LocationManager()
        locationManager.listener = this
        locationManager.syntheticListener = this

        initUI()
        initSceneView()

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
        sceneView = findViewById(R.id.sceneView)
    }

    private fun initSceneView() {
        // Original Sceneform uses a builder with callbacks to load models
        ModelRenderable.builder()
            .setSource(this, Uri.parse("local_coord_mesh_building.obj"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { modelRenderable ->
                // This code runs when the model has successfully loaded
                val modelNode = Node()
                modelNode.renderable = modelRenderable
                // Add the model to the scene
                sceneView.scene.addChild(modelNode)
                Log.d(TAG, "3D Model loaded successfully.")
            }
            .exceptionally { throwable ->
                // This code runs if the model fails to load
                Log.e(TAG, "Unable to load 3D model", throwable)
                Toast.makeText(this, "Error loading model", Toast.LENGTH_LONG).show()
                null
            }
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

    override fun onCameraTransformUpdated(position: Vector3, rotation: Quaternion) {
        runOnUiThread {
            // Update the SceneView's camera to match the drone's real-world pose
            sceneView.scene.camera.worldPosition = position
            sceneView.scene.camera.worldRotation = rotation
        }
    }
}