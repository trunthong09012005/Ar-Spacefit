package com.example.spacefit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceFitEngineTest {
    private val room = RoomSpec(widthCm = 400f, depthCm = 500f)

    @Test
    fun centredFurnitureWithClearanceIsSuitable() {
        val result = SpaceFitEngine.evaluate(
            room = room,
            selected = furniture(id = 1, width = 180f, depth = 80f),
            otherFurniture = emptyList()
        )

        assertTrue(result.isInsideRoom)
        assertFalse(result.hasCollision)
        assertTrue(result.isSuitable)
        assertEquals(100, result.score)
    }

    @Test
    fun furnitureOutsideRoomLosesBoundaryAndClearancePoints() {
        val result = SpaceFitEngine.evaluate(
            room = room,
            selected = furniture(id = 1, x = 190f, width = 100f, depth = 80f),
            otherFurniture = emptyList()
        )

        assertFalse(result.isInsideRoom)
        assertFalse(result.isSuitable)
        assertTrue(result.score < 50)
    }

    @Test
    fun collisionIsDetected() {
        val selected = furniture(id = 1, width = 120f, depth = 80f)
        val obstacle = furniture(id = 2, x = 40f, width = 100f, depth = 100f)
        val result = SpaceFitEngine.evaluate(room, selected, listOf(obstacle))

        assertTrue(result.hasCollision)
        assertFalse(result.isSuitable)
    }

    @Test
    fun touchingEdgesIsNotCollision() {
        val selected = furniture(id = 1, width = 100f, depth = 100f)
        val obstacle = furniture(id = 2, x = 100f, width = 100f, depth = 100f)
        val result = SpaceFitEngine.evaluate(room, selected, listOf(obstacle))

        assertFalse(result.hasCollision)
    }

    @Test
    fun rotationChangesRequiredFootprint() {
        val result = SpaceFitEngine.evaluate(
            room = RoomSpec(widthCm = 180f, depthCm = 400f),
            selected = furniture(id = 1, width = 220f, depth = 100f, rotation = 90f),
            otherFurniture = emptyList()
        )

        assertTrue(result.isInsideRoom)
    }

    @Test
    fun rotationDoesNotChangePhysicalOccupancy() {
        val unrotated = SpaceFitEngine.evaluate(
            room,
            furniture(id = 1, width = 160f, depth = 80f),
            emptyList()
        )
        val rotated = SpaceFitEngine.evaluate(
            room,
            furniture(id = 1, width = 160f, depth = 80f, rotation = 45f),
            emptyList()
        )

        assertEquals(unrotated.occupancyPercent, rotated.occupancyPercent, 0.001f)
    }

    @Test
    fun selectedFurnitureIsNotComparedWithItself() {
        val selected = furniture(id = 1, width = 120f, depth = 80f)
        val result = SpaceFitEngine.evaluate(room, selected, listOf(selected))

        assertFalse(result.hasCollision)
    }

    @Test
    fun invalidFurnitureDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            furniture(id = 1, width = 0f, depth = 80f)
        }
    }

    private fun furniture(
        id: Long,
        x: Float = 0f,
        z: Float = 0f,
        width: Float,
        depth: Float,
        rotation: Float = 0f
    ) = FurnitureFootprint(
        id = id,
        name = "Test furniture",
        centerXcm = x,
        centerZcm = z,
        widthCm = width,
        depthCm = depth,
        rotationDegrees = rotation
    )
}
