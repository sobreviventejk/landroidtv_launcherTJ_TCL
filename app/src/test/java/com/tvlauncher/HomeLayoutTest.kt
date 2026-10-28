package com.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    private val streaming = Category("c1", "Streaming")
    private val games = Category("c2", "Jogos & Diversão")

    @Test
    fun categories_round_trip_through_encoding() {
        val encoded = HomeLayout.encodeCategories(listOf(streaming, games))
        assertEquals(listOf(streaming, games), HomeLayout.decodeCategories(encoded))
    }

    @Test
    fun decode_categories_handles_empty_and_broken_input() {
        assertTrue(HomeLayout.decodeCategories(null).isEmpty())
        assertTrue(HomeLayout.decodeCategories("").isEmpty())
        // no separator, blank name and duplicated ids are dropped
        val raw = "garbage\u001Ec1\u001D   \u001Ec2\u001DJogos\u001Ec2\u001DOutro"
        assertEquals(listOf(Category("c2", "Jogos")), HomeLayout.decodeCategories(raw))
    }

    @Test
    fun assignments_round_trip_including_shortcut_ids() {
        val shortcutId = "com.app\u001Fshortcut-1"
        val map = linkedMapOf("com.netflix.ninja" to "c1", shortcutId to "c2")
        assertEquals(map, HomeLayout.decodeAssignments(HomeLayout.encodeAssignments(map)))
    }

    @Test
    fun sanitize_name_strips_control_chars_and_limits_length() {
        assertEquals("Filmes", HomeLayout.sanitizeName("  Fil\u001Dm\u001Ees \n"))
        assertEquals(HomeLayout.MAX_CATEGORY_NAME, HomeLayout.sanitizeName("x".repeat(100)).length)
        assertEquals("", HomeLayout.sanitizeName(" \u001E "))
    }

    @Test
    fun group_splits_apps_by_category_and_keeps_flat_order() {
        val flat = listOf("a", "b", "c", "d", "e")
        val assignments = mapOf("b" to "c1", "d" to "c1", "e" to "c2", "c" to "deleted-category")
        val grouped = HomeLayout.group(flat, listOf(streaming, games), assignments)

        assertEquals(listOf("a", "c"), grouped.uncategorized)
        assertEquals(listOf("b", "d"), grouped.byCategory["c1"])
        assertEquals(listOf("e"), grouped.byCategory["c2"])
        assertEquals(listOf("c1", "c2"), grouped.byCategory.keys.toList())
        assertEquals(listOf("b", "d"), grouped.groupOf("d"))
        assertEquals(listOf("a", "c"), grouped.groupOf("a"))
    }

    @Test
    fun group_keeps_empty_categories() {
        val grouped = HomeLayout.group(listOf("a"), listOf(streaming, games), emptyMap())
        assertEquals(listOf("a"), grouped.uncategorized)
        assertTrue(grouped.byCategory.getValue("c1").isEmpty())
        assertTrue(grouped.byCategory.getValue("c2").isEmpty())
    }

    @Test
    fun move_in_group_swaps_only_group_members() {
        val flat = listOf("a", "b", "c", "d", "e")
        val group = listOf("b", "d", "e") // a and c belong to another group
        // move "e" two places earlier -> e, b, d inside the group slots (1, 3, 4)
        assertEquals(
            listOf("a", "e", "c", "b", "d"),
            HomeLayout.moveInGroup(flat, group, "e", -2)
        )
        // move "b" one place later
        assertEquals(
            listOf("a", "d", "c", "b", "e"),
            HomeLayout.moveInGroup(flat, group, "b", 1)
        )
    }

    @Test
    fun move_in_group_clamps_at_the_edges_and_reports_no_change() {
        val flat = listOf("a", "b", "c")
        assertNull(HomeLayout.moveInGroup(flat, flat, "a", -1))
        assertNull(HomeLayout.moveInGroup(flat, flat, "c", 1))
        assertNull(HomeLayout.moveInGroup(flat, flat, "b", 0))
        assertNull(HomeLayout.moveInGroup(flat, flat, "zzz", 1))
        // a big jump is clamped to the end of the group
        assertEquals(listOf("b", "c", "a"), HomeLayout.moveInGroup(flat, flat, "a", 99))
        assertEquals(listOf("c", "a", "b"), HomeLayout.moveInGroup(flat, flat, "c", -99))
    }

    @Test
    fun row_offset_moves_one_grid_row_and_stops_at_the_edges() {
        // 8 apps, 5 per row: row 0 = indexes 0..4, row 1 = indexes 5..7
        assertEquals(0, HomeLayout.rowOffset(2, 8, 5, -1)) // already in first row
        assertEquals(-5, HomeLayout.rowOffset(6, 8, 5, -1))
        assertEquals(5, HomeLayout.rowOffset(1, 8, 5, 1))
        assertEquals(3, HomeLayout.rowOffset(4, 8, 5, 1)) // row below is short: stop at last app
        assertEquals(0, HomeLayout.rowOffset(7, 8, 5, 1)) // already in last row
        assertEquals(0, HomeLayout.rowOffset(0, 5, 5, 1)) // a single full row
        assertEquals(0, HomeLayout.rowOffset(9, 8, 5, 1)) // index outside the group
        assertEquals(0, HomeLayout.rowOffset(0, 8, 0, 1)) // invalid column count
    }

    @Test
    fun move_to_group_end_places_app_after_last_member() {
        val flat = listOf("a", "b", "c", "d")
        // target group is [b, c]; moving "a" there puts it after "c"
        assertEquals(
            listOf("b", "c", "a", "d"),
            HomeLayout.moveToGroupEnd(flat, "a", listOf("b", "c"))
        )
        // empty target group -> end of the list
        assertEquals(
            listOf("b", "c", "d", "a"),
            HomeLayout.moveToGroupEnd(flat, "a", emptyList())
        )
        // the app being moved is ignored if it is already listed in the target
        assertEquals(
            listOf("a", "b", "c", "d"),
            HomeLayout.moveToGroupEnd(flat, "d", listOf("b", "c", "d"))
        )
    }

    @Test
    fun moving_to_another_category_then_grouping_shows_it_last_in_that_category() {
        val flat = listOf("a", "b", "c", "d")
        val assignments = mutableMapOf("b" to "c1", "c" to "c1")
        assignments["a"] = "c1"
        val groupedAfterAssign = HomeLayout.group(flat, listOf(streaming), assignments)
        val newFlat = HomeLayout.moveToGroupEnd(flat, "a", groupedAfterAssign.byCategory.getValue("c1"))
        val regrouped = HomeLayout.group(newFlat, listOf(streaming), assignments)
        assertEquals(listOf("b", "c", "a"), regrouped.byCategory["c1"])
        assertEquals(listOf("d"), regrouped.uncategorized)
    }
}
