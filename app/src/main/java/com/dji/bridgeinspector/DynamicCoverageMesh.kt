package com.dji.bridgeinspector

import android.content.Context
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.*

class DynamicCoverageMesh(private val context: Context) : Node() {

    private val materials = arrayOfNulls<Material>(7)

    init {
        // 0: White (Uncovered surfaces - transparent so the base model is visible underneath)
        // Kept very transparent so the operator can see the building textures
        MaterialFactory.makeTransparentWithColor(context, Color(1.0f, 1.0f, 1.0f, 0.2f)).thenAccept { materials[0] = it }

        // Bold Viridis Colormap approximations (1 to 6+)
        // Alpha increased to 0.9f to prevent the model from washing out the colors
        MaterialFactory.makeTransparentWithColor(context, Color(0.267f, 0.004f, 0.329f, 0.9f)).thenAccept { materials[1] = it } // 1: Deep Purple
        MaterialFactory.makeTransparentWithColor(context, Color(0.227f, 0.325f, 0.545f, 0.9f)).thenAccept { materials[2] = it } // 2: Dark Blue/Indigo
        MaterialFactory.makeTransparentWithColor(context, Color(0.122f, 0.565f, 0.549f, 0.9f)).thenAccept { materials[3] = it } // 3: Rich Teal
        MaterialFactory.makeTransparentWithColor(context, Color(0.282f, 0.776f, 0.404f, 0.9f)).thenAccept { materials[4] = it } // 4: Vibrant Green
        MaterialFactory.makeTransparentWithColor(context, Color(0.675f, 0.867f, 0.188f, 0.9f)).thenAccept { materials[5] = it } // 5: Yellow-Green
        MaterialFactory.makeTransparentWithColor(context, Color(0.992f, 0.906f, 0.145f, 0.9f)).thenAccept { materials[6] = it } // 6+: Bright Yellow
    }

    fun updateMesh(coverageData: Map<Int, List<Vector3>>) {
        // Ensure all materials are loaded before building the mesh
        if (materials.any { it == null }) return

        val allVertices = ArrayList<Vertex>()
        val submeshes = ArrayList<RenderableDefinition.Submesh>()

        var vertexOffset = 0

        // Build a submesh for each redundancy level
        for (i in 0..6) {
            val verts = coverageData[i] ?: continue
            if (verts.isEmpty()) continue

            // Map flat indices based on our current offset in the unified vertex array
            val indices = IntArray(verts.size) { it + vertexOffset }.toList()

            allVertices.addAll(verts.map { Vertex.builder().setPosition(it).build() })

            val submesh = RenderableDefinition.Submesh.builder()
                .setTriangleIndices(indices)
                .setMaterial(materials[i])
                .build()

            submeshes.add(submesh)
            vertexOffset += verts.size
        }

        if (allVertices.isEmpty()) return

        val def = RenderableDefinition.builder()
            .setVertices(allVertices)
            .setSubmeshes(submeshes)
            .build()

        ModelRenderable.builder()
            .setSource(def)
            .build()
            .thenAccept { renderable ->
                this.renderable = renderable
            }
            .exceptionally { null }
    }
}