package dev.wonderdesk.shared

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSearchTest {
    @Test fun titlePackageAndAliasesMatchWithoutSurroundingWhitespace() {
        val app = LaunchableApp(ComponentName("dev.wonderdesk.abacus", "MainActivity"), "Abacus", null, "1", listOf("calculator"))
        assertTrue(app.matches(" ABA "))
        assertTrue(app.matches("WONDERDESK"))
        assertTrue(app.matches(" calculator "))
        assertFalse(app.matches("no such app"))
        assertTrue(app.matches("   "))
    }
    @Test fun recentSearchesDedupeAndCap() {
        assertEquals(listOf("B", "a"), updateRecentSearches(listOf("a", "b"), "  B "))
        assertEquals(listOf("c", "a", "b"), updateRecentSearches(listOf("a", "b"), "c"))
        assertEquals(listOf("a"), updateRecentSearches(listOf("a"), "   "))
        assertEquals(listOf("q0", "q1", "q2", "q3", "q4", "q5", "q6", "q7"), updateRecentSearches((1..12).map { "q$it" }, "q0"))
        assertEquals(8, updateRecentSearches((1..12).map { "q$it" }, "new").size)
    }
}
