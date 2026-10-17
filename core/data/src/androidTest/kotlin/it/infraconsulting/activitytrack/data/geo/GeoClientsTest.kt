/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.geo

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/* Parsing of trimmed real answers (org.json is a platform class: device test). */
@RunWith(AndroidJUnit4::class)
class GeoClientsTest {
	@Test
	fun nominatimPrefersCityOverSuburb() {
		val json = JSONObject(
			"""{"place_id":1,"address":{"road":"Via Roma","suburb":"Mestre","city":"Venezia","county":"Venezia","country":"Italia"}}""",
		)
		assertEquals("Venezia", Nominatim.parseLocality(json))
		val village = JSONObject("""{"address":{"village":"Cortina","county":"Belluno"}}""")
		assertEquals("Cortina", Nominatim.parseLocality(village))
		assertNull(Nominatim.parseLocality(JSONObject("""{"error":"Unable to geocode"}""")))
	}

	@Test
	fun osrmLegs() {
		val json = JSONObject(
			"""{"code":"Ok","routes":[{"distance":3500.5,"legs":[{"distance":1200.2},{"distance":2300.3}]}],"waypoints":[]}""",
		)
		assertEquals(listOf(1200.2, 2300.3), Osrm.parseLegs(json, 2))
		assertNull(Osrm.parseLegs(json, 3))
		assertNull(Osrm.parseLegs(JSONObject("""{"code":"NoRoute","message":"Impossible route"}"""), 2))
	}
}
