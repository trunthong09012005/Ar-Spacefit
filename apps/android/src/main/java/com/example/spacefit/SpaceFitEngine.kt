package com.example.spacefit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Physical description of the rectangular room used by the layout simulator.
 * The coordinate origin is at the centre of the room.
 */
data class RoomSpec(
    val widthCm: Float,
    val depthCm: Float,
    val minimumClearanceCm: Float = 60f,
    val heightCm: Float = 280f,
    val openings: List<RoomOpening> = emptyList()
) {
    init {
        require(widthCm.isFinite() && widthCm > 0f) { "Room width must be a positive finite value" }
        require(depthCm.isFinite() && depthCm > 0f) { "Room depth must be a positive finite value" }
        require(heightCm.isFinite() && heightCm > 0f)
        require(minimumClearanceCm.isFinite() && minimumClearanceCm >= 0f) {
            "Minimum clearance must be a non-negative finite value"
        }
    }
}

/** A furniture footprint on the floor. Positions are expressed in centimetres. */
data class FurnitureFootprint(
    val id: Long,
    val name: String,
    val centerXcm: Float,
    val centerZcm: Float,
    val widthCm: Float,
    val depthCm: Float,
    val rotationDegrees: Float = 0f,
    val heightCm: Float = 0f
) {
    init {
        require(centerXcm.isFinite() && centerZcm.isFinite()) { "Furniture position must be finite" }
        require(widthCm.isFinite() && widthCm > 0f) { "Furniture width must be a positive finite value" }
        require(depthCm.isFinite() && depthCm > 0f) { "Furniture depth must be a positive finite value" }
        require(rotationDegrees.isFinite()) { "Furniture rotation must be finite" }
        require(heightCm.isFinite() && heightCm >= 0f)
    }
}

data class SpaceFitResult(
    val score: Int,
    val isInsideRoom: Boolean,
    val hasCollision: Boolean,
    val minimumWallClearanceCm: Float,
    val occupancyPercent: Float,
    val messages: List<String>,
    val doorBlockagePercent: Float = 0f,
    val hasWindowObstruction: Boolean = false,
    val nearestFurnitureClearanceCm: Float = Float.POSITIVE_INFINITY
) {
    val isSuitable: Boolean get() = isInsideRoom && !hasCollision && doorBlockagePercent <= 0.01f && !hasWindowObstruction && score >= 70
}

/**
 * Deterministic spatial evaluation. AI may explain this result, but must never
 * override collision, boundary, clearance, or area calculations.
 */
object SpaceFitEngine {
    fun evaluate(
        room: RoomSpec,
        selected: FurnitureFootprint,
        otherFurniture: List<FurnitureFootprint>
    ): SpaceFitResult {
        val selectedBounds = selected.toAxisAlignedBounds()
        val halfRoomWidth = room.widthCm / 2f
        val halfRoomDepth = room.depthCm / 2f

        val leftClearance = selectedBounds.left + halfRoomWidth
        val rightClearance = halfRoomWidth - selectedBounds.right
        val topClearance = selectedBounds.top + halfRoomDepth
        val bottomClearance = halfRoomDepth - selectedBounds.bottom
        val wallClearance = min(min(leftClearance, rightClearance), min(topClearance, bottomClearance))
        val insideRoom = wallClearance >= -0.001f && selected.heightCm <= room.heightCm

        val collision = otherFurniture
            .asSequence()
            .filter { it.id != selected.id }
            .any { FloorGeometry.overlaps(selected.corners(), it.corners()) }
        val neighbours = otherFurniture.filter { it.id != selected.id }
        val neighbourClearance = neighbours.minOfOrNull { FloorGeometry.distance(selected.corners(), it.corners()) } ?: Float.POSITIVE_INFINITY
        val doorBlockage = room.openings.filter { it.kind == "DOOR" }.maxOfOrNull { opening ->
            val zone = opening.reservedZone(room).corners()
            FloorGeometry.intersectionArea(selected.corners(), zone) / FloorGeometry.area(zone) * 100f
        } ?: 0f
        val windowBlocked = room.openings.filter { it.kind == "WINDOW" && selected.heightCm > it.sillCm }.any {
            FloorGeometry.overlaps(selected.corners(), it.reservedZone(room).corners())
        }

        val roomArea = room.widthCm * room.depthCm
        // Rotation changes the axis-aligned bounds but not the physical floor area.
        val itemArea = selected.widthCm * selected.depthCm
        val occupancyPercent = (itemArea / roomArea * 100f).coerceAtLeast(0f)

        val boundaryPoints = if (insideRoom) 40f else 0f
        val clearanceRatio = if (!insideRoom || room.minimumClearanceCm == 0f) {
            if (insideRoom) 1f else 0f
        } else {
            (min(wallClearance, neighbourClearance) / room.minimumClearanceCm).coerceIn(0f, 1f)
        }
        val clearancePoints = 25f * clearanceRatio
        val collisionPoints = if (collision) 0f else 20f
        val occupancyPoints = when {
            occupancyPercent <= 25f -> 15f
            occupancyPercent >= 60f -> 0f
            else -> 15f * (60f - occupancyPercent) / 35f
        }

        val messages = buildList {
            if (!insideRoom) {
                add("Vật thể đang vượt ra ngoài ranh giới phòng.")
            } else {
                add("Vật thể nằm hoàn toàn trong phòng.")
            }
            if (collision) {
                add("Phát hiện va chạm với một vật thể khác.")
            } else {
                add("Không phát hiện va chạm.")
            }
            if (doorBlockage > 0.01f) add("Đồ vật chắn %.1f%% vùng mở cửa quy ước.".format(doorBlockage))
            if (windowBlocked) add("Đồ vật cao đang che vùng cửa sổ.")
            if (!collision && neighbourClearance < room.minimumClearanceCm) add("Khoảng cách tới đồ khác chỉ %.1f cm; khuyến nghị %.0f cm.".format(neighbourClearance, room.minimumClearanceCm))
            if (selected.heightCm > room.heightCm) add("Đồ vật cao hơn trần phòng.")
            if (insideRoom && wallClearance < room.minimumClearanceCm) {
                add("Nên chừa ít nhất ${room.minimumClearanceCm.toInt()} cm đến tường hoặc lối đi.")
            }
            if (occupancyPercent > 40f) {
                add("Vật thể chiếm nhiều diện tích phòng; nên cân nhắc kích thước nhỏ hơn.")
            }
        }

        return SpaceFitResult(
            score = (boundaryPoints + clearancePoints + collisionPoints + occupancyPoints - if (doorBlockage > 0.01f || windowBlocked) 25f else 0f)
                .toInt()
                .coerceIn(0, 100),
            isInsideRoom = insideRoom,
            hasCollision = collision,
            minimumWallClearanceCm = wallClearance,
            occupancyPercent = occupancyPercent,
            messages = messages,
            doorBlockagePercent = doorBlockage.coerceIn(0f,100f),
            hasWindowObstruction = windowBlocked,
            nearestFurnitureClearanceCm = neighbourClearance
        )
    }

    private data class Bounds(
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val depth: Float get() = bottom - top

        fun overlaps(other: Bounds): Boolean =
            left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    private fun FurnitureFootprint.toAxisAlignedBounds(): Bounds {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val rotatedWidth = abs(widthCm * cos(radians)).toFloat() +
            abs(depthCm * sin(radians)).toFloat()
        val rotatedDepth = abs(widthCm * sin(radians)).toFloat() +
            abs(depthCm * cos(radians)).toFloat()
        return Bounds(
            left = centerXcm - rotatedWidth / 2f,
            right = centerXcm + rotatedWidth / 2f,
            top = centerZcm - rotatedDepth / 2f,
            bottom = centerZcm + rotatedDepth / 2f
        )
    }
}
