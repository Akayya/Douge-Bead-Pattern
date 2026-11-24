package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbCleanupPolicyTest {

    private val live = setOf("aaa111", "bbb222")

    private fun thumb(id: String, suffix: String) = "$id-29x29-mard221-fp-$suffix.png"

    @Test
    fun emptyDirDeletesNothing() {
        assertEquals(emptyList<String>(), ThumbCleanupPolicy.namesToDelete(emptyList(), live))
    }

    @Test
    fun oneFilePerLivePatternIsKept() {
        val entries = listOf(thumb("aaa111", "h1") to 100L, thumb("bbb222", "h2") to 200L)
        assertEquals(emptyList<String>(), ThumbCleanupPolicy.namesToDelete(entries, live))
    }

    @Test
    fun orphanFilesAreDeleted() {
        val entries = listOf(
            thumb("aaa111", "h1") to 100L,
            thumb("gone99", "h1") to 300L,
            thumb("gone99", "h2") to 400L,
        )
        assertEquals(
            listOf(thumb("gone99", "h1"), thumb("gone99", "h2")),
            ThumbCleanupPolicy.namesToDelete(entries, live),
        )
    }

    @Test
    fun staleFingerprintsKeepOnlyNewest() {
        val entries = listOf(
            thumb("aaa111", "old") to 100L,
            thumb("aaa111", "new") to 300L,
            thumb("aaa111", "mid") to 200L,
        )
        assertEquals(
            listOf(thumb("aaa111", "old"), thumb("aaa111", "mid")),
            ThumbCleanupPolicy.namesToDelete(entries, live),
        )
    }

    @Test
    fun timestampTieKeepsLexicographicallyFirst() {
        val entries = listOf(thumb("aaa111", "b") to 100L, thumb("aaa111", "a") to 100L)
        assertEquals(
            listOf(thumb("aaa111", "b")),
            ThumbCleanupPolicy.namesToDelete(entries, live),
        )
    }

    @Test
    fun malformedNamesAreDeleted() {
        val entries = listOf("junk.png" to 100L, "nohyphen" to 200L, thumb("aaa111", "h1") to 300L)
        assertEquals(
            listOf("junk.png", "nohyphen"),
            ThumbCleanupPolicy.namesToDelete(entries, live),
        )
    }

    @Test
    fun emptyLiveSetDeletesEverything() {
        val entries = listOf(thumb("aaa111", "h1") to 100L, thumb("bbb222", "h2") to 200L)
        assertEquals(entries.map { it.first }, ThumbCleanupPolicy.namesToDelete(entries, emptySet()))
    }
}