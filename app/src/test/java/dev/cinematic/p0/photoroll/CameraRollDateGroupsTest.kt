package dev.cinematic.p0.photoroll

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CameraRollDateGroupsTest {
    @Test fun groupsAtLocalMidnightWithoutChangingItemOrder() {
        val first = PhotoRollItem("content://test/2", "two", addedSeconds = Instant.parse("2026-11-05T15:01:00Z").epochSecond)
        val second = PhotoRollItem("content://test/1", "one", addedSeconds = Instant.parse("2026-11-05T14:59:00Z").epochSecond)
        val groups = cameraRollDateGroups(listOf(first, second), ZoneId.of("Asia/Tokyo"))
        assertEquals(listOf("2026-11-06 FRI", "2026-11-05 THU"), groups.map { it.label })
        assertEquals(listOf(first, second), groups.flatMap { it.items })
    }
    @Test fun absentAndInvalidTimesNeverInventADate() {
        val items = listOf(0L, -1L, Long.MAX_VALUE).mapIndexed { index, time -> PhotoRollItem("content://test/$index", "image", addedSeconds = time) }
        val groups = cameraRollDateGroups(items, ZoneId.of("UTC"))
        assertEquals("日付不明", groups.single().label)
        assertEquals(items, groups.single().items)
        assertTrue(cameraRollDateGroups(emptyList()).isEmpty())
    }
}
