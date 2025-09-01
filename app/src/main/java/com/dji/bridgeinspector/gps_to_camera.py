import numpy as np
import pymap3d as pm

def gps_to_local(drone_lat, drone_lon, drone_alt, target_lat, target_lon, target_alt):
    # Convert GPS to local ENU
    x, y, z = pm.geodetic2enu(target_lat, target_lon, target_alt,
                              drone_lat, drone_lon, drone_alt)
    return x, y, z

def world_to_drone(target, yaw, pitch, roll):
    """
    Transform a point from world coordinates to the drone's local coordinate system.
    Angles are in degrees.
    """
    yaw = np.radians(yaw)
    pitch = np.radians(pitch)
    roll = np.radians(roll)

    # Rotation matrices (negative angles for world-to-drone)
    R_yaw = np.array([
        [np.cos(-yaw), -np.sin(-yaw), 0],
        [np.sin(-yaw),  np.cos(-yaw), 0],
        [0, 0, 1]
    ])
    R_pitch = np.array([
        [np.cos(-pitch), 0, np.sin(-pitch)],
        [0, 1, 0],
        [-np.sin(-pitch), 0, np.cos(-pitch)]
    ])
    R_roll = np.array([
        [1, 0, 0],
        [0, np.cos(-roll), -np.sin(-roll)],
        [0, np.sin(-roll), np.cos(-roll)]
    ])
    R = R_roll @ R_pitch @ R_yaw
    target_drone = R @ np.array(target)
    return target_drone

def drone_to_camera(point_drone):
    """
    Transform a point from the drone's local coordinate system to the camera's coordinate system.
    """
    # Define the transformation matrix from drone to camera coordinates
    R_drone_to_camera = np.array([
        [0, -1, 0],  # Camera x-axis is drone -y-axis
        [0, 0, 1],  # Camera y-axis is drone z-axis
        [1, 0, 0]  # Camera z-axis is drone x-axis
    ])
    # Transform the point
    point_camera = R_drone_to_camera @ np.array(point_drone)
    return point_camera

def camera_project(point_cam, fx, fy, cx, cy):
    """
    Project a 3D point in the camera frame to 2D pixel coordinates.
    Assumes camera looks along the x-axis.
    """
    Xc, Yc, Zc = point_cam
    if Zc == 0:
        raise ValueError("Zc (depth) is zero, cannot project.")
    u = fx * Xc / Zc + cx
    v = fy * Yc / Zc + cy
    return u, v #u positive is right, v positive is down

# Example usage:
if __name__ == "__main__":
    # Test: camera facing global x direction

    yaw, pitch, roll = 100, 10, 0
    fx, fy = 800, 800
    cx, cy = 0, 0
    drone_lat, drone_lon, drone_alt = 40.105239, -88.285391, 54
    target_lat, target_lon, target_alt = 40.105439, -88.285391, 54
    target = gps_to_local(drone_lat, drone_lon, drone_alt, target_lat, target_lon, target_alt)
    point_drone = world_to_drone(target, yaw, pitch, roll)
    point_camera = drone_to_camera(point_drone)
    print("Drone frame coordinates:", point_drone)
    print("Camera frame coordinates:", point_camera)
    u, v = camera_project(point_camera, fx, fy, cx, cy)
    print(f"Pixel coordinates: (u, v) = ({u:.2f}, {v:.2f})")