/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/* Each schema version must migrate to the current one keeping the data (schemas in core/data/schemas). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
	@get:Rule
	val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ActivityTrackDatabase::class.java)

	@Test
	fun v1ToV2AddsDayOff() {
		helper.createDatabase(DB, 1).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL("INSERT INTO lunch_break (day_of_week, start_min, end_min) VALUES (1, 750, 840)")
		}
		helper.runMigrationsAndValidate(DB, 2, true).use { db ->
			db.query("SELECT name FROM company WHERE id = 1").use { c ->
				c.moveToFirst()
				assertEquals("ACME", c.getString(0))
			}
			db.execSQL("INSERT INTO day_off (date_epoch_day, note) VALUES (20743, 'patrono')")
			db.query("SELECT COUNT(*) FROM day_off").use { c ->
				c.moveToFirst()
				assertEquals(1, c.getInt(0))
			}
		}
	}

	private companion object {
		const val DB = "migration-test.db"
	}
}
