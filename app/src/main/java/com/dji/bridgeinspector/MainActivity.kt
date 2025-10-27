package com.dji.bridgeinspector

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
// --- REMOVED DJI IMPORTS ---
// import dji.sdk.keyvalue.value.common.ComponentIndexType
// import dji.v5.ux.core.widget.fpv.FPVWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
// --- REMOVED ---
// import kotlinx.coroutines.launch
import android.graphics.PointF
import android.net.Uri
// --- REMOVED ---
// import android.view.View
import android.widget.Toast
import com.dji.bridgeinspector.Legacy.ScreenCoordinates
// --- REMOVED ---
// import kotlinx.coroutines.cancel
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.SceneView
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Light
// --- REMOVED ---
// import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
// --- REMOVED ---
// import com.google.ar.sceneform.rendering.ShapeFactory
import android.graphics.PixelFormat
 import android.widget.Button

// --- ADDED IMPORTS ---
import android.os.Handler
import android.os.Looper
import android.widget.VideoView
import kotlinx.coroutines.cancel


open class MainActivity : AppCompatActivity(), DroneDataListener, SyntheticListener {

    private val TAG = "MainActivity"

    // --- REPLACED ---
    private lateinit var videoManager: VideoLocalizationManager
    private var primaryVideoView: VideoView? = null
    // --- (LocationManager and FPVWidget removed) ---

    private var waypointWidget: WaypointOverlayWidget? = null
    private lateinit var sceneView: SceneView

    private lateinit var nextButton: Button
    private var currentWaypointIndex = -1

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- ADDED for playback loop ---
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var playbackRunnable: Runnable? = null

    // --- MODIFIED ---
    // !! CRITICAL: You must update this list with your WaypointPoses.
    // !! You need both the (x,y,z) position AND the (vx,vy,vz) direction.
    private val targetWaypoints: List<WaypointPose> = listOf(
        // Example:
        // WaypointPose(
        //    position = Vector3(1.5f, 7.2f, -3.0f),
        //    direction = Vector3(0f, 0f, -1f) // Look in -Z direction
        // ),
        // WaypointPose(
        //    position = Vector3(1.8f, 7.2f, -4.0f),
        //    direction = Vector3(0.1f, 0f, -0.9f) // Look slightly right
        // )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.live_feed2)

        // --- MODIFIED ---
        // 1. Initialize the new VideoLocalizationManager
        //    (Assumes your pose file is in res/raw/pose_data.txt)
        videoManager = VideoLocalizationManager(this, R.raw.pose_data)
        videoManager.listener = this
        videoManager.syntheticListener = this
        videoManager.targetWaypoints = this.targetWaypoints // Pass the new list

        // 2. Init UI
        initUI()
        initSceneView()

        // 3. Set up VideoView
        //    (Assumes your video file is in res/raw/your_video_file.mp4)
        val videoPath = "android.resource://" + packageName + "/" + R.raw.video
        primaryVideoView?.setVideoURI(Uri.parse(videoPath))
        primaryVideoView?.setOnPreparedListener { mp ->
            mp.isLooping = true
            primaryVideoView?.start()
            startPlaybackLoop() // Start our sync loop
        }

        // --- REMOVED ---
        // The mainScope.launch block for product connection is no longer needed.

         goToNextViewpoint()
    }

    private fun initUI() {
        // --- MODIFIED ---
        primaryVideoView = findViewById(R.id.video_view) // Use video_view ID
        waypointWidget = findViewById(R.id.waypoint)
        sceneView = findViewById(R.id.sceneView)

        // --- REMOVED ---
        nextButton = findViewById(R.id.next_waypoint_button)
        nextButton.setOnClickListener {
            goToNextViewpoint()
        }
    }

    private fun initSceneView() {
        // ... (This function remains unchanged) ...
        sceneView.holder.setFormat(PixelFormat.TRANSLUCENT)
        sceneView.setZOrderOnTop(true)

        sceneView.renderer?.setClearColor(com.google.ar.sceneform.rendering.Color(0.0f, 0.0f, 0.0f, 0.0f))

        ModelRenderable.builder()
            .setSource(this, Uri.parse("file:///android_asset/model/local_coord_mesh_building.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { modelRenderable ->
                val modelNode = Node()
                modelNode.renderable = modelRenderable
                sceneView.scene.addChild(modelNode)

                val light = Light.builder(Light.Type.DIRECTIONAL)
                    .setColor(com.google.ar.sceneform.rendering.Color(Color.WHITE))
                    .setIntensity(10f)
                    .build()

                val lightNode = Node()
                lightNode.light = light
                lightNode.worldPosition = Vector3(0f, 3f, 2f)
                lightNode.setLookDirection(Vector3(0f, -1f, -1f))
                sceneView.scene.addChild(lightNode)

                Log.d(TAG, "3D Model and Light loaded successfully.")
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Unable to load 3D model", throwable)
                Toast.makeText(this, "Error loading model", Toast.LENGTH_LONG).show()
                null
            }
        Log.i(TAG, "initSceneView complete")
    }

    // --- ADDED ---
    /**
     * Creates a "game loop" that runs ~30 times per second.
     * It gets the video's current time and tells the VideoLocalizationManager
     * to calculate the poses for that time.
     */
    private fun startPlaybackLoop() {
        playbackRunnable = Runnable {
            if (primaryVideoView?.isPlaying == true) {
                val currentTimeMillis = primaryVideoView!!.currentPosition
                val currentTimeSeconds = currentTimeMillis / 1000.0

                // This one call updates the 2D waypoint projection
                videoManager.updateForTime(currentTimeSeconds)
            }
            // Re-post to run again in ~33ms (for 30fps)
            playbackHandler.postDelayed(playbackRunnable!!, 33)
        }
        playbackHandler.post(playbackRunnable!!)
    }

    /**
     * Helper function to switch to next waypoint.
     * This now does TWO things:
     * 1. Tells VideoLocalizationManager which 2D waypoint to project.
     * 2. Manually snaps the 3D synthetic camera to the waypoint's pose.
     */
    private fun goToNextViewpoint() {
        // --- MODIFIED ---
        if (targetWaypoints.isEmpty()) {
            Log.e(TAG, "No target viewpoints defined.")
            return
        }

        // Advance index, wrapping around to the start
        // --- MODIFIED ---
        currentWaypointIndex = (currentWaypointIndex + 1) % targetWaypoints.size
        val newTargetPose = targetWaypoints[currentWaypointIndex]

        // 1. Tell VideoLocalizationManager to use this new target for 2D projection
        videoManager.setTargetIndex(currentWaypointIndex)

        // 2. Manually update the 3D synthetic view (Option B)
        val cameraPosition = newTargetPose.position
        val cameraRotation = Quaternion.lookRotation(newTargetPose.direction, Vector3.up())

        // Call the listener function directly to snap the camera
        onCameraTransformUpdated(cameraPosition, cameraRotation)

        // Signal success in app
        // --- MODIFIED ---
        Toast.makeText(this, "Set to viewpoint ${currentWaypointIndex + 1}/${targetWaypoints.size}", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Set target to viewpoint ${currentWaypointIndex + 1}")
    }

    override fun onResume() {
        // ... (This function remains unchanged) ...
        super.onResume()
        try {
            sceneView.resume()
            Log.d(TAG, "onResume called")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume SceneView", e)
        }
    }

    override fun onPause() {
        // ... (This function remains unchanged) ...
        super.onPause()
        sceneView.pause()
        Log.d(TAG, "onPause called")
    }

    override fun onDestroy() {
        super.onDestroy()
        // --- MODIFIED ---
        playbackHandler.removeCallbacks(playbackRunnable!!) // Stop the loop
        mainScope.cancel()
        sceneView.destroy()
        Log.d(TAG, "onDestory called")
    }

    /**
     * Receives the final calculated screen coordinates.
     * This function remains UNCHANGED.
     */
    override fun onScreenCoordinatesUpdated(coords: ScreenCoordinates?) {
        runOnUiThread {
            if (coords != null) {
                val point = PointF(coords.u.toFloat(), coords.v.toFloat())
                val radius = coords.radius.toFloat()
                waypointWidget?.update(point, radius)
                Log.d(TAG, "UI Updated with: u=${point.x}, v=${point.y}, radius=$radius")
            } else {
                waypointWidget?.update(null, 0f)
                Log.w(TAG, "Received null coordinates, hiding waypoint.")
            }
        }
    }

    /**
     * Receives the 3D camera pose.
     * This function remains UNCHANGED.
     */
    override fun onCameraTransformUpdated(position: Vector3, rotation: Quaternion) {
        runOnUiThread {
            sceneView.scene.camera.worldPosition = position
            sceneView.scene.camera.worldRotation = rotation
            Log.d(TAG, "[Synthetic] Camera position: ${position}, rotation: $rotation")
        }
    }
}