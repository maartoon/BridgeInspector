package com.dji.bridgeinspector

import android.content.Context
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.*
import java.util.concurrent.CompletableFuture

class DynamicGreenMesh(private val context: Context) : Node() {

    private var greenMaterial: Material? = null

    init {
        // Initialize a transparent green material
        MaterialFactory.makeTransparentWithColor(context, Color(0.0f, 1.0f, 0.0f, 0.5f))
            .thenAccept { mat -> greenMaterial = mat }
    }

    fun updateMesh(vertices: List<Vector3>) {
        if (greenMaterial == null || vertices.isEmpty()) return

        // Define the mesh data
        // Sceneform needs a flat list of position data
        val submesh = RenderableDefinition.Submesh.builder()
            .setTriangleIndices(IntArray(vertices.size) { it }.toList()) // 0, 1, 2, ...
            .setMaterial(greenMaterial)
            .build()

        val def = RenderableDefinition.builder()
            .setVertices(vertices.map { Vertex.builder().setPosition(it).build() })
            .setSubmeshes(listOf(submesh))
            .build()

        // Create the ModelRenderable on the background thread to avoid UI lag
        ModelRenderable.builder()
            .setSource(def)
            .build()
            .thenAccept { renderable ->
                // Apply to this node
                this.renderable = renderable
            }
            .exceptionally {
                null
            }
    }
}