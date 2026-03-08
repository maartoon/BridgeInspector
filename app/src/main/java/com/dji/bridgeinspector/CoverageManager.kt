package com.dji.bridgeinspector

import android.content.Context
import android.util.Log
import com.google.ar.sceneform.math.Vector3
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.acos

data class Triangle(
    val v1: Vector3,
    val v2: Vector3,
    val v3: Vector3,
    val normal: Vector3,
    val center: Vector3,
    val area: Float,
    var scanCount: Int = 0 // Changed from boolean to track redundancy
)

class CoverageManager(private val context: Context, private val objFileName: String) {

    private val TAG = "CoverageManager"
    val triangles = ArrayList<Triangle>()

    private val MAX_SCAN_DISTANCE = 50.0f
    private val FOV_RAD = Math.toRadians(80.0).toFloat()
    private val MAX_INCLINATION_RAD = Math.toRadians(60.0).toFloat()

    private var totalArea = 0f
    private var scannedArea = 0f

    init {
        loadObj()
    }

    fun updateCoverage(dronePos: Vector3, droneForward: Vector3): Boolean {
        var changed = false
        val droneForwardNorm = droneForward.normalized()

        for (tri in triangles) {
            val vecToTri = Vector3.subtract(tri.center, dronePos)
            val dist = vecToTri.length()

            if (dist > MAX_SCAN_DISTANCE) continue

            val vecToTriNorm = vecToTri.normalized()
            val angleToTarget = acos(Vector3.dot(droneForwardNorm, vecToTriNorm))

            if (abs(angleToTarget) > (FOV_RAD / 2f)) continue

            val negVecToTri = vecToTriNorm.negated()
            val inclination = acos(Vector3.dot(tri.normal, negVecToTri))

            if (inclination > MAX_INCLINATION_RAD) continue

            // Only add to scanned area the FIRST time it's covered
            if (tri.scanCount == 0) {
                scannedArea += tri.area
            }

            // Increment the redundancy count
            tri.scanCount++
            changed = true
        }
        return changed
    }

    fun getCoveragePercentage(): Float {
        if (totalArea == 0f) return 0f
        return (scannedArea / totalArea) * 100f
    }

    /**
     * Groups triangles by their redundancy level (0 to 6+) and returns their vertices.
     */
    fun getCoverageData(): Map<Int, List<Vector3>> {
        val groupedVerts = mutableMapOf<Int, MutableList<Vector3>>()

        // Initialize lists for levels 0 through 6
        for (i in 0..6) groupedVerts[i] = ArrayList()

        for (tri in triangles) {
            val level = tri.scanCount.coerceAtMost(6) // Cap at 6
            groupedVerts[level]?.apply {
                add(tri.v1)
                add(tri.v2)
                add(tri.v3)
            }
        }
        return groupedVerts
    }

    private fun loadObj() {
        try {
            val vertices = ArrayList<Vector3>()
            val stream = context.assets.open(objFileName)
            val reader = BufferedReader(InputStreamReader(stream))

            reader.forEachLine { line ->
                val tokens = line.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) return@forEachLine

                when (tokens[0]) {
                    "v" -> {
                        val x = tokens[1].toFloat()
                        val y = tokens[2].toFloat()
                        val z = tokens[3].toFloat()
                        vertices.add(Vector3(x, y, z))
                    }
                    "f" -> {
                        val i1 = tokens[1].split("/")[0].toInt() - 1
                        val i2 = tokens[2].split("/")[0].toInt() - 1
                        val i3 = tokens[3].split("/")[0].toInt() - 1

                        val v1 = vertices[i1]
                        val v2 = vertices[i2]
                        val v3 = vertices[i3]

                        val edge1 = Vector3.subtract(v2, v1)
                        val edge2 = Vector3.subtract(v3, v1)
                        val normal = Vector3.cross(edge1, edge2).normalized()
                        val center = Vector3.add(Vector3.add(v1, v2), v3).scaled(1f/3f)
                        val area = Vector3.cross(edge1, edge2).length() * 0.5f

                        val t = Triangle(v1, v2, v3, normal, center, area)
                        triangles.add(t)
                        totalArea += area
                    }
                }
            }
            Log.i(TAG, "Loaded mesh with ${triangles.size} triangles. Total Area: $totalArea")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading OBJ", e)
        }
    }
}