package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPolicyTest {

    private val hour = 60L * 60 * 1000

    @Test
    fun firstSnapshotNeedsOnlyData() {
        assertTrue(SnapshotPolicy.shouldAutoSnapshot(1000L, null, 500L))
        assertFalse(SnapshotPolicy.shouldAutoSnapshot(1000L, null, 0L))
    }

    @Test
    fun unchangedDataNeverSnapshots() {
        val newest = 10 * hour
        assertFalse(SnapshotPolicy.shouldAutoSnapshot(newest + 100 * hour, newest, newest))
        assertFalse(SnapshotPolicy.shouldAutoSnapshot(newest + 100 * hour, newest, newest - 1))
    }

    @Test
    fun changedDataWaitsForInterval() {
        val newest = 10 * hour
        val changed = newest + hour
        assertFalse(SnapshotPolicy.shouldAutoSnapshot(newest + 5 * hour + 1, newest, changed))
        assertTrue(SnapshotPolicy.shouldAutoSnapshot(newest + 6 * hour, newest, changed))
    }

    @Test
    fun clockSkewDoesNotForceSnapshot() {
        val newest = 10 * hour
        assertFalse(SnapshotPolicy.shouldAutoSnapshot(newest - 1000, newest, newest + 1))
    }

    @Test
    fun pruneKeepsNewestAndProtected() {
        val names = (0 until 6).map { "snap-${6 - it}" }
        assertEquals(listOf("snap-1"), SnapshotPolicy.namesToDelete(names))
        assertEquals(emptyList<String>(), SnapshotPolicy.namesToDelete(names, protect = "snap-1"))

        val older = (0 until 7).map { "snap-${7 - it}" }
        assertEquals(
            listOf("snap-2"),
            SnapshotPolicy.namesToDelete(older, protect = "snap-1"),
        )
    }
}
