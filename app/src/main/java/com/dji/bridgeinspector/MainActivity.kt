package com.dji.bridgeinspector

import android.graphics.Color
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
import android.view.View
import android.widget.Toast
import com.dji.bridgeinspector.Legacy.ScreenCoordinates
import kotlinx.coroutines.cancel
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.SceneView
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Light
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory
import android.graphics.PixelFormat
import android.widget.Button


open class MainActivity : AppCompatActivity(), DroneDataListener, SyntheticListener {

    private val TAG = "MainActivity"

    private lateinit var locationManager: LocationManager
    private var primaryFpvWidget: FPVWidget? = null
    private var waypointWidget: WaypointOverlayWidget? = null
    private lateinit var sceneView: SceneView

    private lateinit var nextButton: Button // Button for cycling waypoints

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentWaypointIndex = -1 // Start at -1, first click will go to 0
    private val targetViewpoints: List<TargetViewpoint> = listOf(
        // Format: TargetViewpoint(lat, lon, alt, yaw, pitch, roll)
        // Alt is relative to sea level (AMSL) as used by the drone
        // Yaw/Pitch/Roll should be in radians
        TargetViewpoint(40.114425, -88.225853, 1.0, 0.0, 0.0, 0.0),
        TargetViewpoint(40.114425, -88.225953, 1.0, 0.0, 0.0, 0.0),
        TargetViewpoint(40.114425, -88.226100, 1.0, 0.0, 0.0, 0.0),
        TargetViewpoint(40.114425, -88.226300, 1.0, 0.0, 0.0, 0.0),
        TargetViewpoint(40.114425, -88.226500, 1.0, 0.0, 0.0, 0.0),  // Viewpoint 3

    )

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

        goToNextViewpoint() // set initial viewpoint to the first one
    }

    private fun initUI() {
        // Initialize FPV and SceneView
        primaryFpvWidget = findViewById(R.id.widget_primary_fpv)
        waypointWidget = findViewById(R.id.waypoint)
        primaryFpvWidget?.updateVideoSource(ComponentIndexType.LEFT_OR_MAIN)
        sceneView = findViewById(R.id.sceneView)

        // button logic
        nextButton = findViewById(R.id.next_waypoint_button)
        nextButton.setOnClickListener {
            goToNextViewpoint()
        }
    }

    private fun initSceneView() {
        sceneView.holder.setFormat(PixelFormat.TRANSLUCENT)
        sceneView.setZOrderOnTop(true)

        sceneView.renderer?.setClearColor(com.google.ar.sceneform.rendering.Color(0.0f, 0.0f, 0.0f, 0.0f))

        // Original Sceneform uses a builder with callbacks to load models
        ModelRenderable.builder()
            .setSource(this, Uri.parse("file:///android_asset/model/local_coord_mesh_building.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { modelRenderable ->
                val modelNode = Node()
                modelNode.renderable = modelRenderable
                sceneView.scene.addChild(modelNode)

                // --- ADD THIS LIGHTING CODE ---
                val light = Light.builder(Light.Type.DIRECTIONAL)
                    .setColor(com.google.ar.sceneform.rendering.Color(Color.WHITE)) // A bright white light
                    .setIntensity(10f) // Start with a strong intensity
                    .build()

                val lightNode = Node()
                lightNode.light = light
                // Position the light source above and in front of the model
                lightNode.worldPosition = Vector3(0f, 3f, 2f)
                // Point the light downwards and slightly towards the model
                lightNode.setLookDirection(Vector3(0f, -1f, -1f))
                sceneView.scene.addChild(lightNode)

                Log.d(TAG, "3D Model and Light loaded successfully.")
            }
            .exceptionally { throwable ->
                // This code runs if the model fails to load
                Log.e(TAG, "Unable to load 3D model", throwable)
                Toast.makeText(this, "Error loading model", Toast.LENGTH_LONG).show()
                null
            }
        Log.i(TAG, "initSceneView complete")
    }

    /**
     * Helper function to switch to next waypoint
     * Advances to the next viewpoint in the list, updates the LocationManager's
     * target, and sets the synthetic camera to the new static view.
     */
    private fun goToNextViewpoint() {
        if (targetViewpoints.isEmpty()) {
            Log.e(TAG, "No target viewpoints defined.")
            return
        }

        // Advance index, wrapping around to the start
        currentWaypointIndex = (currentWaypointIndex + 1) % targetViewpoints.size
        val newTarget = targetViewpoints[currentWaypointIndex]

        // Tell LocationManager to use this new target for *projections*
        locationManager.currentTarget = newTarget

        // Tell LocationManager to switch to static mode for the *synthetic view*
        locationManager.syntheticMode = SyntheticMode.STATIC_VIEWPOINT

        // Manually trigger the synthetic camera update to snap to the new static view
        locationManager.updateStaticSyntheticView()

        // Signal success in app
        Toast.makeText(this, "Set to viewpoint ${currentWaypointIndex + 1}/${targetViewpoints.size}", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Set target to viewpoint ${currentWaypointIndex + 1}")
    }

    override fun onResume() {
        super.onResume()
        // It is essential to resume the SceneView's rendering thread
        try {
            sceneView.resume()
            Log.d(TAG, "onResume called")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume SceneView", e)
        }
    }

    override fun onPause() {
        super.onPause()
        // It is essential to pause the SceneView's rendering thread
        sceneView.pause()
        Log.d(TAG, "onPause called")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the listener when the activity is destroyed
        locationManager.stopListening()
        mainScope.cancel() // Cancel the coroutine scope
        sceneView.destroy() // Ensure all Sceneform resources are released
        Log.d(TAG, "onDestory called")
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
            Log.i(TAG, "[Synthetic] Current camera position: ${position}")
            Log.i(TAG, "[Synthetic] Recieved camera rotation: $rotation")
        }
    }
}