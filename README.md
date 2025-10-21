# Bridge Inspector Guidance System

This repository contains an Android application designed to run on a DJI Remote Controller (specifically for the Mavic 3T). The purpose of this application is to provide a visual guidance system for drone operators conducting bridge inspections, guiding them along a pre-generated trajectory.

## Project Goal

The primary goal is to assist an operator with the manual flight of a complex, pre-defined path for bridge scanning. This is achieved by displaying two key pieces of information:
1.  **Target Position:** A visual overlay on the live video feed displays the location of the next inspection point.
2.  **Target Orientation:** A "digital twin" 3D view displays the *target* camera orientation (yaw, pitch, roll) the operator is required to match upon reaching the inspection point.

## Core Features

* **Live Waypoint Overlay:** Renders the current target waypoint as a circle directly onto the DJI FPV (First-Person View) video feed. The circle's radius scales with distance, diminishing as the drone approaches the target.
* **3D Digital Twin View:** A side-by-side 3D view, powered by Sceneform, displays a model of the inspection area. The camera in this 3D view is set to the *target's* defined position and orientation, providing the operator with the required capture perspective.
* **Trajectory Cycling:** A "Next Waypoint" button allows the operator to manually cycle through the list of pre-defined inspection points (`TargetViewpoint` objects).

## How It Works

The application's logic is segmented into several key components:

1.  **Data Ingestion (`LocationManager.kt`):**
    * Subscribes to the DJI V5 SDK for critical real-time data.
    * Subscribes to `KeyAircraftLocation3D` for the drone's current GPS (latitude, longitude, altitude).
    * Subscribes to `KeyGimbalAttitude` for the gimbal's current orientation (yaw, pitch, roll).

2.  **Waypoint Projection (`WaypointProjection.kt`):**
    * This component serves as the core mathematical engine, converting a 3D GPS target coordinate into a 2D pixel coordinate for the screen overlay.
    * The transformation pipeline is as follows:
        1.  **Geodetic to ECEF:** Converts the drone and target's (Lat, Lon, Alt) into Earth-Centered, Earth-Fixed (ECEF) coordinates.
        2.  **ECEF to Local NED:** Calculates the target's 3D vector relative to the drone in a local North-East-Down (NED) frame.
        3.  **World to Drone Frame:** Uses the drone's current attitude (yaw, pitch, roll) to rotate the NED vector into the drone's body-relative coordinate system.
        4.  **Drone to Camera Frame:** Applies an axis-swapping transform to convert the point from the drone's body frame to the camera's optical frame (X-right, Y-down, Z-forward).
        5.  **Camera to Screen (Projection):** Uses a standard pinhole camera model (with hardcoded intrinsics `fx`, `fy`, `cx`, `cy`) to project the 3D camera-space point onto the 2D screen, resulting in the final `(u, v)` pixel coordinates.

3.  **UI and State Management (`MainActivity.kt`):**
    * Manages the `FPVWidget` (live video), `WaypointOverlayWidget` (custom drawing view), and the `SceneView` (3D digital twin).
    * Contains the pre-defined list of `TargetViewpoint` objects that define the inspection path.
    * The **"Next Waypoint"** button:
        * Advances to the next `TargetViewpoint` in the list.
        * Sets this as the `currentTarget` in `LocationManager`.
        * Sets the `syntheticMode` to `STATIC_VIEWPOINT`.
        * Calls `locationManager.updateStaticSyntheticView()` to set the `SceneView` camera to the *target's* position and orientation.

4.  **Visual Feedback (UI Layer):**
    * **`WaypointOverlayWidget.kt`:** A custom `View` that receives a `PointF` and `radius` from `MainActivity`. Its `onDraw` method is invoked to render a red circle at the specified coordinates.
    * **`SceneView`:** The `MainActivity` implements the `SyntheticListener` interface. When `LocationManager` emits an `onCameraTransformUpdated` event, `MainActivity` updates the `sceneView.scene.camera`'s `worldPosition` and `worldRotation` accordingly.

## Key Files

* **`MainActivity.kt`**: The main application activity. Manages UI components (`FPVWidget`, `SceneView`, `WaypointOverlayWidget`), holds the waypoint list, and handles the "Next Waypoint" button logic.
* **`LocationManager.kt`**: The primary service class. Manages DJI SDK key listeners, holds the current drone and target state, and orchestrates data flow to the projection and synthetic view listeners.
* **`WaypointProjection.kt`**: A utility object containing static mathematical functions for coordinate system transformations (GPS -> ECEF -> NED -> Drone -> Camera -> Screen).
* **`WaypointOverlayWidget.kt`**: The custom Android `View` responsible for rendering the red circle overlay on top of the video feed.

## Operational Procedure

1.  Define the inspection path by populating the `targetViewpoints` list in `MainActivity.kt` with the correct (Lat, Lon, Alt, Yaw, Pitch, Roll) for each point.
2.  Build and deploy the application on a compatible DJI RC (e.g., RC Plus for Mavic 3T).
3.  Upon connection to the drone, the FPV feed will be displayed.
4.  Select the **"Next Waypoint"** button to load the initial inspection point.
5.  The display will present:
    * A red circle on the video feed, indicating the target's spatial location.
    * The 3D "Digital Twin" view, displaying the required camera orientation.
6.  Operate the drone to center the red circle in the FPV feed while simultaneously matching the drone's camera perspective to the 3D digital twin's view.
7.  Once aligned, select **"Next Waypoint"** to proceed to the subsequent inspection point.
