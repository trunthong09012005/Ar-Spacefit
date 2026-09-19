package com.example.ar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vec3(x * scalar, y * scalar, z * scalar)

    fun distanceTo(other: Vec3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

data class PolygonFace(
    val vertices: List<Vec3>,
    val baseColor: Color,
    val normal: Vec3
)

data class Mesh3D(
    val faces: List<PolygonFace>
)

data class PlacedFurniture(
    val id: Long,
    val name: String,
    val modelType: String,
    var posX: Float, // in world units (1 unit = 100cm / 1m)
    var posY: Float, // elevation from floor
    var posZ: Float, // depth
    var rotationY: Float, // in degrees
    var widthCm: Float,
    var heightCm: Float,
    var depthCm: Float,
    val color: Color,
    val material: String = "Tiêu chuẩn",
    val modelParameters: ModelParameters = ModelParameters.defaults(modelType)
) {
    // Generate scaled, translated and rotated mesh
    fun getTransformedMesh(): Mesh3D {
        val rawMesh = ModelGeometryFactory.createModel(modelType, color, modelParameters)
        val rad = Math.toRadians(rotationY.toDouble()).toFloat()
        val cosR = cos(rad)
        val sinR = sin(rad)

        // Scale factors: 1 unit in ModelGeometry is ~1m (100cm)
        val sx = widthCm / 100f
        val sy = heightCm / 100f
        val sz = depthCm / 100f

        val transformedFaces = rawMesh.faces.map { face ->
            val newVerts = face.vertices.map { v ->
                // Scale
                val sxv = v.x * sx
                val syv = v.y * sy
                val szv = v.z * sz

                // Rotate around Y
                val rx = sxv * cosR - szv * sinR
                val rz = sxv * sinR + szv * cosR
                val ry = syv

                // Translate
                Vec3(rx + posX, ry + posY, rz + posZ)
            }

            // Rotate normal
            val nx = face.normal.x * cosR - face.normal.z * sinR
            val nz = face.normal.x * sinR + face.normal.z * cosR
            val ny = face.normal.y

            PolygonFace(newVerts, face.baseColor, Vec3(nx, ny, nz))
        }

        return Mesh3D(transformedFaces)
    }
}

data class Camera3D(
    var yaw: Float = 35f, // rotation around Y axis in degrees
    var pitch: Float = 28f, // tilt down towards floor in degrees
    var distance: Float = 4.2f, // camera distance from center target
    var target: Vec3 = Vec3(0f, 0.4f, 0f) // look-at target
) {
    fun project(point: Vec3, viewportWidth: Float, viewportHeight: Float): ProjectedPoint {
        // Translate relative to target
        val rel = point - target

        // Rotate yaw around Y
        val radYaw = Math.toRadians(yaw.toDouble()).toFloat()
        val cosY = cos(radYaw)
        val sinY = sin(radYaw)
        val x1 = rel.x * cosY + rel.z * sinY
        val y1 = rel.y
        val z1 = -rel.x * sinY + rel.z * cosY

        // Rotate pitch around X
        val radPitch = Math.toRadians(pitch.toDouble()).toFloat()
        val cosP = cos(radPitch)
        val sinP = sin(radPitch)
        val x2 = x1
        val y2 = y1 * cosP - z1 * sinP
        val z2 = y1 * sinP + z1 * cosP

        // Camera offset along Z
        val camZ = z2 + distance
        val clampedZ = if (camZ < 0.2f) 0.2f else camZ

        // Perspective projection
        val fovFactor = (viewportWidth.coerceAtLeast(viewportHeight)) * 0.85f
        val screenX = viewportWidth / 2f + (x2 / clampedZ) * fovFactor
        val screenY = viewportHeight / 2f - (y2 / clampedZ) * fovFactor

        return ProjectedPoint(Offset(screenX, screenY), camZ)
    }
}

data class ProjectedPoint(val offset: Offset, val depth: Float)

object ModelGeometryFactory {

    fun createModel(
        type: String,
        baseColor: Color,
        parameters: ModelParameters = ModelParameters.defaults(type)
    ): Mesh3D {
        val safe = parameters.sanitized()
        val mesh = when (type.uppercase()) {
            "SOFA" -> createSofa(baseColor, safe)
            "CHAIR" -> createChair(baseColor, safe)
            "TABLE" -> createTable(baseColor, safe)
            "BED" -> createBed(baseColor)
            "LAMP" -> createLamp(baseColor)
            "WARDROBE" -> createWardrobe(baseColor, safe)
            "PLANT" -> createPlant(baseColor)
            else -> createBox(baseColor)
        }
        // Every template must occupy exactly 1×1×1 before applying measured dimensions.
        // Otherwise visual bounds disagree with the SpaceFit collision footprint.
        val vertices = mesh.faces.flatMap { it.vertices }
        val minX = vertices.minOf { it.x }
        val minY = vertices.minOf { it.y }
        val minZ = vertices.minOf { it.z }
        val extentX = (vertices.maxOf { it.x } - minX).coerceAtLeast(0.001f)
        val extentY = (vertices.maxOf { it.y } - minY).coerceAtLeast(0.001f)
        val extentZ = (vertices.maxOf { it.z } - minZ).coerceAtLeast(0.001f)
        return Mesh3D(mesh.faces.map { face ->
            face.copy(vertices = face.vertices.map {
                Vec3((it.x - minX) / extentX - 0.5f, (it.y - minY) / extentY, (it.z - minZ) / extentZ - 0.5f)
            })
        })
    }

    private fun createBox(baseColor: Color): Mesh3D {
        val faces = mutableListOf<PolygonFace>()
        // Unit box centered at X,Z=0, Y from 0 to 1
        val w = 0.5f
        val h = 1.0f
        val d = 0.5f

        // Bottom: y=0, Top: y=h
        val p000 = Vec3(-w, 0f, -d)
        val p100 = Vec3(w, 0f, -d)
        val p101 = Vec3(w, 0f, d)
        val p001 = Vec3(-w, 0f, d)

        val p010 = Vec3(-w, h, -d)
        val p110 = Vec3(w, h, -d)
        val p111 = Vec3(w, h, d)
        val p011 = Vec3(-w, h, d)

        // Top face
        faces.add(PolygonFace(listOf(p010, p110, p111, p011), baseColor, Vec3(0f, 1f, 0f)))
        faces.add(PolygonFace(listOf(p001, p101, p100, p000), baseColor.copy(alpha = 0.7f), Vec3(0f, -1f, 0f)))
        // Front face (towards positive Z)
        faces.add(PolygonFace(listOf(p001, p101, p111, p011), baseColor.copy(alpha = 0.95f), Vec3(0f, 0f, 1f)))
        // Back face (towards negative Z)
        faces.add(PolygonFace(listOf(p100, p000, p010, p110), baseColor.copy(alpha = 0.8f), Vec3(0f, 0f, -1f)))
        // Left face
        faces.add(PolygonFace(listOf(p000, p001, p011, p010), baseColor.copy(alpha = 0.85f), Vec3(-1f, 0f, 0f)))
        // Right face
        faces.add(PolygonFace(listOf(p101, p100, p110, p111), baseColor.copy(alpha = 0.9f), Vec3(1f, 0f, 0f)))

        return Mesh3D(faces)
    }

    private fun addCuboid(
        faces: MutableList<PolygonFace>,
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        color: Color
    ) {
        val p000 = Vec3(minX, minY, minZ)
        val p100 = Vec3(maxX, minY, minZ)
        val p101 = Vec3(maxX, minY, maxZ)
        val p001 = Vec3(minX, minY, maxZ)

        val p010 = Vec3(minX, maxY, minZ)
        val p110 = Vec3(maxX, maxY, minZ)
        val p111 = Vec3(maxX, maxY, maxZ)
        val p011 = Vec3(minX, maxY, maxZ)

        // Top
        faces.add(PolygonFace(listOf(p010, p110, p111, p011), color, Vec3(0f, 1f, 0f)))
        // Bottom is visible when inspecting a model from below.
        faces.add(PolygonFace(listOf(p001, p101, p100, p000), color.copy(alpha = 0.7f), Vec3(0f, -1f, 0f)))
        // Front
        faces.add(PolygonFace(listOf(p001, p101, p111, p011), color.copy(alpha = 0.92f), Vec3(0f, 0f, 1f)))
        // Back
        faces.add(PolygonFace(listOf(p100, p000, p010, p110), color.copy(alpha = 0.75f), Vec3(0f, 0f, -1f)))
        // Left
        faces.add(PolygonFace(listOf(p000, p001, p011, p010), color.copy(alpha = 0.82f), Vec3(-1f, 0f, 0f)))
        // Right
        faces.add(PolygonFace(listOf(p101, p100, p110, p111), color.copy(alpha = 0.88f), Vec3(1f, 0f, 0f)))
    }

    private fun createSofa(baseColor: Color, parameters: ModelParameters): Mesh3D {
        val faces = mutableListOf<PolygonFace>()
        val woodColor = Color(0xFF5D4037)

        // 4 Wooden legs
        val legH = 0.15f
        val legR = 0.04f
        addCuboid(faces, -0.45f, 0f, -0.32f, -0.45f + legR, legH, -0.32f + legR, woodColor)
        addCuboid(faces, 0.45f - legR, 0f, -0.32f, 0.45f, legH, -0.32f + legR, woodColor)
        addCuboid(faces, -0.45f, 0f, 0.32f - legR, -0.45f + legR, legH, 0.32f, woodColor)
        addCuboid(faces, 0.45f - legR, 0f, 0.32f - legR, 0.45f, legH, 0.32f, woodColor)

        // Main seat cushion base
        val seatY = parameters.seatHeightRatio
        addCuboid(faces, -0.5f, legH, -0.35f, 0.5f, seatY, 0.35f, baseColor)

        // Backrest
        addCuboid(faces, -0.5f, seatY, -0.35f, 0.5f, 1f, -0.18f, baseColor.copy(alpha = 0.95f))

        // Optional armrests
        if (parameters.hasArmrests) {
            addCuboid(faces, -0.5f, legH, -0.35f, -0.38f, seatY + 0.2f, 0.35f, baseColor.copy(alpha = 0.9f))
            addCuboid(faces, 0.38f, legH, -0.35f, 0.5f, seatY + 0.2f, 0.35f, baseColor.copy(alpha = 0.9f))
        }

        return Mesh3D(faces)
    }

    private fun createChair(baseColor: Color, parameters: ModelParameters): Mesh3D {
        val faces = mutableListOf<PolygonFace>()
        val legColor = Color(0xFF2B2D42)

        // 4 Legs
        val legH = parameters.seatHeightRatio - 0.07f
        val w = 0.035f
        addCuboid(faces, -0.22f, 0f, -0.22f, -0.22f + w, legH, -0.22f + w, legColor)
        addCuboid(faces, 0.22f - w, 0f, -0.22f, 0.22f, legH, -0.22f + w, legColor)
        addCuboid(faces, -0.22f, 0f, 0.22f - w, -0.22f + w, legH, 0.22f, legColor)
        addCuboid(faces, 0.22f - w, 0f, 0.22f - w, 0.22f, legH, 0.22f, legColor)

        // Seat
        addCuboid(faces, -0.26f, legH, -0.26f, 0.26f, legH + 0.07f, 0.26f, baseColor)

        // Backrest support posts & backrest panel
        addCuboid(faces, -0.24f, legH + 0.07f, -0.26f, -0.19f, 1f, -0.22f, legColor)
        addCuboid(faces, 0.19f, legH + 0.07f, -0.26f, 0.24f, 1f, -0.22f, legColor)
        addCuboid(faces, -0.25f, 0.7f, -0.25f, 0.25f, 1f, -0.21f, baseColor)
        if (parameters.hasArmrests) {
            for (x in listOf(-0.26f, 0.22f)) {
                addCuboid(faces, x, legH + 0.07f, 0.16f, x + 0.04f, legH + 0.3f, 0.2f, legColor)
                addCuboid(faces, x, legH + 0.27f, -0.26f, x + 0.04f, legH + 0.33f, 0.24f, baseColor)
            }
        }

        return Mesh3D(faces)
    }

    private fun createTable(baseColor: Color, parameters: ModelParameters): Mesh3D {
        val faces = mutableListOf<PolygonFace>()
        val legColor = Color(0xFF3A3A3A)

        // Tabletop
        val topThick = parameters.topThicknessRatio
        val topY = 1f - topThick
        addCuboid(faces, -0.5f, topY, -0.4f, 0.5f, topY + topThick, 0.4f, baseColor)

        // 4 Legs
        val legW = 0.05f
        addCuboid(faces, -0.45f, 0f, -0.35f, -0.45f + legW, topY, -0.35f + legW, legColor)
        addCuboid(faces, 0.45f - legW, 0f, -0.35f, 0.45f, topY, -0.35f + legW, legColor)
        addCuboid(faces, -0.45f, 0f, 0.35f - legW, -0.45f + legW, topY, 0.35f, legColor)
        addCuboid(faces, 0.45f - legW, 0f, 0.35f - legW, 0.45f, topY, 0.35f, legColor)

        return Mesh3D(faces)
    }

    private fun createBed(baseColor: Color): Mesh3D {
        val faces = mutableListOf<PolygonFace>()
        val frameColor = Color(0xFF4A3E3D)
        val sheetColor = Color(0xFFF0F4F8)
        val pillowColor = Color(0xFFE2E8F0)

        // Bed frame base
        addCuboid(faces, -0.5f, 0f, -0.6f, 0.5f, 0.25f, 0.6f, frameColor)

        // Headboard
        addCuboid(faces, -0.5f, 0.25f, -0.6f, 0.5f, 0.95f, -0.5f, frameColor.copy(alpha = 0.9f))

        // Mattress & duvet
        addCuboid(faces, -0.46f, 0.25f, -0.5f, 0.46f, 0.45f, 0.58f, sheetColor)
        addCuboid(faces, -0.46f, 0.35f, -0.1f, 0.46f, 0.47f, 0.58f, baseColor)

        // Pillows
        addCuboid(faces, -0.42f, 0.45f, -0.45f, -0.05f, 0.52f, -0.25f, pillowColor)
        addCuboid(faces, 0.05f, 0.45f, -0.45f, 0.42f, 0.52f, -0.25f, pillowColor)

        return Mesh3D(faces)
    }

    private fun createLamp(baseColor: Color): Mesh3D {
        val faces = mutableListOf<PolygonFace>()
        val metalColor = Color(0xFF2B2D42)
        val shadeColor = baseColor

        // Base disc
        addCuboid(faces, -0.18f, 0f, -0.18f, 0.18f, 0.03f, 0.18f, metalColor)

        // Tall pole
        addCuboid(faces, -0.02f, 0.03f, -0.02f, 0.02f, 1.25f, 0.02f, metalColor)

        // Lampshade
        addCuboid(faces, -0.22f, 1.15f, -0.22f, 0.22f, 1.45f, 0.22f, shadeColor)

        return Mesh3D(faces)
    }

    private fun createWardrobe(baseColor: Color, parameters: ModelParameters): Mesh3D {
        val faces = mutableListOf<PolygonFace>()
        val handleColor = Color(0xFFD4AF37) // Gold handle

        // Main cabinet body
        addCuboid(faces, -0.45f, 0f, -0.3f, 0.45f, 1.6f, 0.3f, baseColor)

        for (door in 1 until parameters.doorCount) {
            val x = -0.45f + 0.9f * door / parameters.doorCount
            addCuboid(faces, x - 0.005f, 0.05f, 0.3f, x + 0.005f, 1.55f, 0.305f, Color.DarkGray)
        }
        for (door in 0 until parameters.doorCount) {
            val x = -0.45f + 0.9f * (door + 0.75f) / parameters.doorCount
            addCuboid(faces, x - 0.01f, 0.8f, 0.3f, x + 0.01f, 0.95f, 0.33f, handleColor)
        }

        return Mesh3D(faces)
    }

    private fun createPlant(baseColor: Color): Mesh3D {
        val faces = mutableListOf<PolygonFace>()
        val potColor = Color(0xFFEDEDE9)
        val greenLeaf = baseColor
        val darkLeaf = Color(baseColor.red * 0.6f, baseColor.green * 0.6f, baseColor.blue * 0.6f)

        // Ceramic Pot
        addCuboid(faces, -0.18f, 0f, -0.18f, 0.18f, 0.32f, 0.18f, potColor)

        // Plant stem and foliage layers
        addCuboid(faces, -0.03f, 0.32f, -0.03f, 0.03f, 0.75f, 0.03f, darkLeaf)
        addCuboid(faces, -0.28f, 0.55f, -0.28f, 0.28f, 0.95f, 0.28f, greenLeaf)
        addCuboid(faces, -0.22f, 0.85f, -0.22f, 0.22f, 1.15f, 0.22f, greenLeaf.copy(alpha = 0.95f))

        return Mesh3D(faces)
    }
}
