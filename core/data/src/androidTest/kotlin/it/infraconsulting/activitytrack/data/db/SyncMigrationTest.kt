/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/* Issue #111 on the device's SQLite: Room validates the migrated schema against 16.json. */
@RunWith(AndroidJUnit4::class)
class SyncMigrationTest {
	@get:Rule
	val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ActivityTrackDatabase::class.java)

	@Test
	fun migrate15To16() {
		helper.createDatabase("sync-migration", 15).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'Acme', 0)")
			db.execSQL("INSERT INTO client (id, company_id, name, archived) VALUES (1, 1, 'Banca', 0)")
			db.execSQL(
				"INSERT INTO time_entry (id, uid, client_id, company_id, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) " +
					"VALUES (1, '0123456789abcdef0123456789abcdef', 1, 1, 'CLOSED', 'Europe/Rome', 0, 0, 0)",
			)
			db.execSQL("INSERT INTO day_off (date_epoch_day) VALUES (20000)")
		}
		helper.runMigrationsAndValidate("sync-migration", 16, true, ActivityTrackDatabase.MIGRATION_15_16).use { db ->
			db.query("SELECT uid FROM time_entry").use { c ->
				c.moveToFirst()
				assertEquals("01234567-89ab-cdef-0123-456789abcdef", c.getString(0))
			}
			db.query("SELECT uid FROM day_off").use { c ->
				c.moveToFirst()
				assertEquals(SyncIds.dayOff(20000), c.getString(0))
			}
			db.query("SELECT uid FROM client").use { c ->
				c.moveToFirst()
				assertTrue(SyncIds.isCanonical(c.getString(0)))
			}
			val present = HashSet<String>()
			db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) present += c.getString(0) }
			db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync_%'").use { c ->
				c.moveToFirst()
				/* the tables of schema 16; later ones get theirs when they exist */
				assertEquals(SyncSchema.triggers(present).size, c.getInt(0))
			}
		}
	}
}
