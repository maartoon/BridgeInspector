package com.dji.bridgeinspector

import android.annotation.SuppressLint
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
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlin.math.PI


open class MainActivity : AppCompatActivity(), DroneDataListener, SyntheticListener {

    private val TAG = "MainActivity"

    private lateinit var locationManager: LocationManager
    private var primaryFpvWidget: FPVWidget? = null
    private var waypointWidget: WaypointOverlayWidget? = null
    private lateinit var sceneView: SceneView

    private var isSceneViewPrimary = false
    private lateinit var viewSwapOverlay: View

    private lateinit var nextButton: Button // Button for cycling waypoints
    private lateinit var distanceTextView: TextView // displays the distance from next waypoint

    // Coverage Visualization variables
    private lateinit var toggleCoverageButton: Button
    private lateinit var coveragePercentText: TextView
    private var isCoverageOverlayEnabled = false
    private lateinit var coverageManager: CoverageManager
    private lateinit var coverageMeshNode: DynamicCoverageMesh // Replaced greenNetNode

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentWaypointIndex = -1 // Start at -1, first click will go to 0
    private val targetViewpoints: List<TargetViewpoint> = listOf(
        // Format: TargetViewpoint(lat, lon, alt, yaw, pitch, roll)
        // Alt is relative to sea level (AMSL) as used by the drone
        // Yaw/Pitch/Roll should be in radians
        TargetViewpoint(40.114464, -88.225883, 0.25, 0.0, 0.0, 0.0),
        TargetViewpoint(40.114495, -88.225696, 0.25, -60.0, 0.0, 0.0),
        TargetViewpoint(40.114641, -88.225689, 0.25, -90.0, 0.0, 0.0)
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

        // Initialize coverage visualization
        coverageManager = CoverageManager(this, "model/model_with_texture.obj")
        coverageMeshNode = DynamicCoverageMesh(this)

        // Read location information using locationManager
        mainScope.launch {
            (application as MyApplication).connectionState.collect { event ->
                when (event) {
                    is ConnectionEvent.ProductConnected -> {
                        Log.i(TAG, "Product Connected! Starting listeners.")
                        locationManager.startListening()

                        // Initialize photo-taking capabilities
                        mainScope.launch {
                            // 1. Wait 2 seconds for the camera payload to fully boot up
                            kotlinx.coroutines.delay(2000)

                            // 2. Set the top-level mode to Photo
                            val cameraModeKey = DJIKey.create(CameraKey.KeyCameraMode)
                            KeyManager.getInstance().setValue(cameraModeKey, CameraMode.PHOTO_NORMAL, object : CommonCallbacks.CompletionCallback {
                                override fun onSuccess() {
                                    Log.i(TAG, "Successfully set camera to Photo Mode.")
                                }
                                override fun onFailure(error: IDJIError) {
                                    Log.e(TAG, "Failed to set Photo Mode: ${error.errorCode()}")
                                }
                            })

                            // 3. Explicitly tell the M3E we want to take Single shots
                            val shootPhotoModeKey = DJIKey.create(CameraKey.KeyShootPhotoMode)
                            KeyManager.getInstance().setValue(
                                shootPhotoModeKey,
                                dji.sdk.keyvalue.value.camera.CameraShootPhotoMode.NORMAL,
                                null
                            )
                        }


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

        // Logic for swapping between FPV and SceneView
        viewSwapOverlay = findViewById(R.id.view_swap_overlay)
        primaryFpvWidget?.updateVideoSource(ComponentIndexType.LEFT_OR_MAIN)

        // button logic
        nextButton = findViewById(R.id.next_waypoint_button)
        nextButton.setOnClickListener {
            goToNextViewpoint()
        }
        // displays distance to next viewpoint
        distanceTextView = findViewById(R.id.distance_text)

        // Initialize UI elements for coverage visualization
        coveragePercentText = findViewById(R.id.coverage_percent_text)
        toggleCoverageButton = findViewById(R.id.toggle_coverage_btn)

        toggleCoverageButton.setOnClickListener {
            isCoverageOverlayEnabled = !isCoverageOverlayEnabled

            if (isCoverageOverlayEnabled) {
                // Attach to the scene to make it visible
                coverageMeshNode.setParent(sceneView.scene)

                // Refresh the mesh with the latest data in case photos were taken while hidden
                val coverageData = coverageManager.getCoverageData()
                coverageMeshNode.updateMesh(coverageData)
            } else {
                // Detach from the scene to hide it
                coverageMeshNode.setParent(null)
            }

            toggleCoverageButton.text = if (isCoverageOverlayEnabled) "Hide Net" else "Show Net"
        }

        val takePhotoButton = findViewById<Button>(R.id.take_photo_btn)
        takePhotoButton.setOnClickListener {
            val startShootKey = DJIKey.create(CameraKey.KeyStartShootPhoto)

            KeyManager.getInstance().performAction(startShootKey, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(p0: EmptyMsg?) {
                    Log.i(TAG, "Successfully captured a photo!")
                }

                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Failed to capture photo: ${error.description()}")
                }
            })
        }

        setupViewSwapping()
    }

    private fun initSceneView() {
        sceneView.holder.setFormat(PixelFormat.TRANSLUCENT)
//        sceneView.setZOrderOnTop(true)

        sceneView.renderer?.setClearColor(com.google.ar.sceneform.rendering.Color(0.0f, 0.0f, 0.0f, 0.0f))

        // Original Sceneform uses a builder with callbacks to load models
        ModelRenderable.builder()
            .setSource(this, Uri.parse("file:///android_asset/model/model_with_texture.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { modelRenderable ->
                val modelNode = Node()
                modelNode.renderable = modelRenderable
                sceneView.scene.addChild(modelNode)

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
    override fun onProjectionResult(result: ProjectionResult?) {
        // Pass the entire result object (or null) to the waypoint widget
        runOnUiThread {
            waypointWidget?.update(result)
            displayDistance(result)
        }
    }

    // This updates strictly on the Synthetic Camera Listener (drone movement)
    private var lastCoverageUpdateTime = 0L
    private val UPDATE_INTERVAL_MS = 500 // Update coverage every 500ms to save performance
    @SuppressLint("SetTextI18n")
    override fun onCameraTransformUpdated(position: Vector3, rotation: Quaternion) {
        runOnUiThread {
            // Update the SceneView's camera to match the drone's real-world pose
            sceneView.scene.camera.worldPosition = position
            sceneView.scene.camera.worldRotation = rotation
            Log.i(TAG, "[Synthetic] Current camera position: ${position}")
            Log.i(TAG, "[Synthetic] Recieved camera rotation: $rotation")
//
//            // Coverage visualization logic
//            val currentTime = System.currentTimeMillis()
//            if (currentTime - lastCoverageUpdateTime > UPDATE_INTERVAL_MS) {
//                lastCoverageUpdateTime = currentTime
//
//                // Calculate Forward Vector from Rotation Quaternion
//                val forward = Quaternion.rotateVector(rotation, Vector3.forward())
//
//                // Run math
//                val changed = coverageManager.updateCoverage(position, forward)
//
//                // Update Text
//                val percent = coverageManager.getCoveragePercentage()
//                coveragePercentText.text = "Coverage: %.1f%%".format(percent)
//
//                // Update Net if enabled and data changed
//                if (changed && isCoverageOverlayEnabled) {
//                    val newVerts = coverageManager.getScannedVertices()
//                    greenNetNode.updateMesh(newVerts)
//                }
//            }
        }
    }

    // This runs continuously based on the drone's actual live GPS
    override fun onLiveDroneTransformUpdated(position: Vector3, rotation: Quaternion) {
//        runOnUiThread {
//            val currentTime = System.currentTimeMillis()
//            if (currentTime - lastCoverageUpdateTime > UPDATE_INTERVAL_MS) {
//                lastCoverageUpdateTime = currentTime
//
//                // IMPORTANT FIX: Sceneform cameras look down the -Z axis.
//                // We must rotate the -Z vector to get the correct forward direction.
//                val forward = Quaternion.rotateVector(rotation, Vector3(0f, 0f, -1f))
//
//                // Run math using the live physical drone position
//                val changed = coverageManager.updateCoverage(position, forward)
//
//                // Update Text
//                val percent = coverageManager.getCoveragePercentage()
//                coveragePercentText.text = "Coverage: %.1f%%".format(percent)
//
//                // Update Net if enabled and data changed
//                if (changed && isCoverageOverlayEnabled) {
//                    val newVerts = coverageManager.getScannedVertices()
//                    greenNetNode.updateMesh(newVerts)
//                }
//            }
//        }
    }

    override fun onPhotoTaken(position: Vector3, rotation: Quaternion) {
        // 1. Immediately show the Toast on the Main (UI) thread so it feels responsive
        runOnUiThread {
            Toast.makeText(this@MainActivity, "Photo Captured! Updating mesh...", Toast.LENGTH_SHORT).show()
        }

        // 2. Push the heavy mesh math to a background thread
        mainScope.launch(Dispatchers.Default) {
            val forward = Quaternion.rotateVector(rotation, Vector3(0f, 0f, -1f))

            // This calculates coverage for thousands of triangles in the background
            val changed = coverageManager.updateCoverage(position, forward)
            val percent = coverageManager.getCoveragePercentage()

            // Prepare the heavy mesh data in the background
            val coverageData = if (changed && isCoverageOverlayEnabled) {
                coverageManager.getCoverageData()
            } else null

            // 3. Switch back to the Main thread ONLY to update the UI and ARCore Scene
            launch(Dispatchers.Main) {
                coveragePercentText.text = "Coverage: %.1f%%".format(percent)

                if (coverageData != null) {
                    coverageMeshNode.updateMesh(coverageData)
                }
            }
        }
    }

    fun displayDistance(result: ProjectionResult?) {
        if (result != null) {
            val distance = result.distanceToTarget
            distanceTextView.text = getString(R.string.distance_format, distance)
            distanceTextView.visibility = View.VISIBLE

            // Check condition and set color
            if (distance != null) {
                if (distance < 2.5) {
                    // Set to semi-transparent green
                    distanceTextView.setBackgroundColor(Color.parseColor("#8000C853")) // 50% alpha green
                    distanceTextView.setTextColor(Color.WHITE)
                } else {
                    // Set back to default semi-transparent black
                    distanceTextView.setBackgroundColor(Color.parseColor("#80000000")) // 50% alpha black
                    distanceTextView.setTextColor(Color.WHITE)
                }
            }
        } else {
            // No target (currentTarget is null)
            distanceTextView.text = getString(R.string.distance_not_available)
            distanceTextView.visibility = View.VISIBLE // Or .GONE if you prefer
            // Reset to default colors
            distanceTextView.setBackgroundColor(Color.parseColor("#80000000"))
            distanceTextView.setTextColor(Color.WHITE)
        }
    }

    private fun setupViewSwapping() {
        // The overlay catches clicks for whatever view is currently in the bottom-right
        viewSwapOverlay.setOnClickListener {
            swapViews()
        }
    }

    private fun swapViews() {
        isSceneViewPrimary = !isSceneViewPrimary

        // 1. Hide temporarily to force Android to re-draw the hardware Surface layers
        primaryFpvWidget?.visibility = View.INVISIBLE
        sceneView.visibility = View.INVISIBLE

        // Define Layout Parameters
        val primaryParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val margin = dpToPx(16f)
        val secondaryParams = FrameLayout.LayoutParams(dpToPx(250f), dpToPx(150f)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = margin
            rightMargin = margin
        }

        if (isSceneViewPrimary) {
            sceneView.layoutParams = primaryParams
            primaryFpvWidget?.layoutParams = secondaryParams

            // FPV is Secondary: Push SceneView to background, pull FPV to overlay layer
            setSurfaceViewOverlay(sceneView, false)
            setSurfaceViewOverlay(primaryFpvWidget, true)

            sceneView.bringToFront()
            primaryFpvWidget?.bringToFront()
        } else {
            primaryFpvWidget?.layoutParams = primaryParams
            sceneView.layoutParams = secondaryParams

            // SceneView is Secondary: Push FPV to background, pull SceneView to overlay layer
            setSurfaceViewOverlay(primaryFpvWidget, false)
            setSurfaceViewOverlay(sceneView, true)

            primaryFpvWidget?.bringToFront()
            sceneView.bringToFront()
        }

        // 2. Show them again to apply the new Z-Order
        primaryFpvWidget?.visibility = View.VISIBLE
        sceneView.visibility = View.VISIBLE

        // 3. Keep UI elements on the absolute top
        restoreUiZOrder()
    }

    /**
     * Helper: Sets the hardware z-order of a SurfaceView.
     */
    private fun setSurfaceViewOverlay(view: View?, isOverlay: Boolean) {
        val surfaceView = findSurfaceView(view)
        surfaceView?.setZOrderMediaOverlay(isOverlay)
    }

    /**
     * Helper: Recursively searches a ViewGroup to find its underlying SurfaceView.
     * (DJI widgets wrap their SurfaceViews inside FrameLayouts).
     */
    private fun findSurfaceView(view: View?): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findSurfaceView(child)
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * Re-stacks the UI elements on top of the SurfaceViews to prevent them from disappearing.
     */
    private fun restoreUiZOrder() {
        val uiElevation = 20f

        // Ensure the tap overlay stays directly over the secondary view
        viewSwapOverlay.elevation = uiElevation
        viewSwapOverlay.bringToFront()

        // Bring the top-left coverage UI to the front
        findViewById<View>(R.id.control_container).apply {
            elevation = uiElevation
            bringToFront()
        }

        // Bring the bottom-left next button to the front
        nextButton.apply {
            elevation = uiElevation
            bringToFront()
        }

        // Bring the Waypoint arrows/lines to the front
        waypointWidget?.apply {
            elevation = uiElevation
            bringToFront()
        }

        // Bring the Waypoint distance text to the front
        distanceTextView.apply {
            elevation = uiElevation
            bringToFront()
        }
    }

    // Helper to convert density-independent pixels to physical pixels
    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }
}