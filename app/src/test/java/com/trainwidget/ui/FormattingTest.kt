package com.nexttrain.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun `days returns every day when all seven days selected`() {
        val days = Formatting.days((1..7).toSet())
        assertEquals("Every day", days)
    }

    @Test
    fun `days collapses Monday through Thursday into Mon–Thu`() {
        val days = Formatting.days(setOf(1, 2, 3, 4))
        assertEquals("Mon–Thu", days)
    }

    @Test
    fun `days collapses weekend wrap from Saturday to Monday into Sat–Mon`() {
        val days = Formatting.days(setOf(6, 7, 1))
        assertEquals("Sat–Mon", days)
    }

    @Test
    fun `days leaves non-consecutive days as individual abbreviations`() {
        val days = Formatting.days(setOf(1, 3, 5))
        assertEquals("Mon Wed Fri", days)
    }

    @Test
    fun `days leaves two adjacent days as range`() {
        val days = Formatting.days(setOf(2, 3))
        assertEquals("Tue–Wed", days)
    }
}
