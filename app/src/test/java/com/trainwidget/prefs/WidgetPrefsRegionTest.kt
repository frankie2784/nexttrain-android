package com.nexttrain.prefs

import com.nexttrain.data.OdPair
import com.nexttrain.data.Region
import com.nexttrain.data.Station
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPrefsRegionTest {

    private fun pair(
        id: String = "id",
        originName: String = "Flinders Street",
        originStopId: Int = 1071,
        destinationName: String = "Southern Cross",
        destinationStopId: Int = 1181,
        region: Region = Region.VIC,
    ) = OdPair(
        id = id,
        label = "label",
        originStopId = originStopId,
        originName = originName,
        destinationStopId = destinationStopId,
        destinationName = destinationName,
        activeFrom = LocalTime.of(6, 0),
        activeTo = LocalTime.of(10, 0),
        region = region,
    )

    // ── regionFromNameOrDefault: OdPairDto migration ────────────────────────

    @Test
    fun `regionFromNameOrDefault defaults to VIC when region is absent`() {
        assertEquals(Region.VIC, regionFromNameOrDefault(null))
    }

    @Test
    fun `regionFromNameOrDefault defaults to VIC for an unrecognised value`() {
        assertEquals(Region.VIC, regionFromNameOrDefault("XX"))
    }

    @Test
    fun `regionFromNameOrDefault parses a valid saved region name`() {
        assertEquals(Region.SA, regionFromNameOrDefault("SA"))
    }

    // ── reconcileOdPairs: region-scoped station-id healing ──────────────────

    @Test
    fun `reconcileOdPairs updates stop ids that drifted within the same region`() {
        val saved = pair(originStopId = 9999, destinationStopId = 8888)
        val catalog = listOf(
            Station("Flinders Street", 1071, Region.VIC),
            Station("Southern Cross", 1181, Region.VIC),
        )

        val result = reconcileOdPairs(listOf(saved), catalog)

        assertEquals(1071, result.single().originStopId)
        assertEquals(1181, result.single().destinationStopId)
    }

    @Test
    fun `reconcileOdPairs does not cross-match a same-named station from a different region`() {
        // A VIC pair whose saved ids have drifted, but the only "Flinders Street" in the
        // catalog now belongs to SA — it must NOT be used to "fix" the VIC pair's ids.
        val saved = pair(region = Region.VIC, originStopId = 9999, destinationStopId = 8888)
        val catalog = listOf(
            Station("Flinders Street", 1071, Region.SA),
            Station("Southern Cross", 1181, Region.SA),
        )

        val result = reconcileOdPairs(listOf(saved), catalog)

        // No VIC entries in the catalog for this pair's region, so ids are left untouched.
        assertEquals(9999, result.single().originStopId)
        assertEquals(8888, result.single().destinationStopId)
    }

    @Test
    fun `reconcileOdPairs leaves already-correct pairs unchanged`() {
        val saved = pair(originStopId = 1071, destinationStopId = 1181)
        val catalog = listOf(
            Station("Flinders Street", 1071, Region.VIC),
            Station("Southern Cross", 1181, Region.VIC),
        )

        val result = reconcileOdPairs(listOf(saved), catalog)

        assertEquals(saved, result.single())
    }
}
