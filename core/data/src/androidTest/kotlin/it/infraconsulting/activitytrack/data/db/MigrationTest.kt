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
	val helper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		ActivityTrackDatabase::class.java,
		listOf(ActivityTrackDatabase.FillEntryUids()),
	)

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

	@Test
	fun v2ToV3AddsTodosAndEntryUids() {
		helper.createDatabase(DB, 2).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL("INSERT INTO client (id, company_id, name, archived) VALUES (1, 1, 'Bank', 0)")
			for (id in 1..2)
				db.execSQL(
					"INSERT INTO time_entry (id, company_id, client_id, status, zone_id, unanswered_reminders, " +
						"created_utc_ms, updated_utc_ms) VALUES ($id, 1, 1, 'CLOSED', 'Europe/Rome', 0, 0, 0)",
				)
		}
		helper.runMigrationsAndValidate(DB, 3, true).use { db ->
			db.query("SELECT COUNT(DISTINCT uid), COUNT(*) FROM time_entry WHERE uid IS NOT NULL").use { c ->
				c.moveToFirst()
				assertEquals(2, c.getInt(0))
				assertEquals(2, c.getInt(1))
			}
			db.execSQL("INSERT INTO todo (uid, company_id, title, refs, done, created_utc_ms, updated_utc_ms) VALUES ('t1', 1, 'Patch', '', 0, 0, 0)")
			db.execSQL("UPDATE time_entry SET todo_id = 1 WHERE id = 1")
		}
	}

	@Test
	fun v3ToV4AddsTheTodoPlan() {
		helper.createDatabase(DB, 3).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL("INSERT INTO todo (uid, company_id, title, refs, done, created_utc_ms, updated_utc_ms) VALUES ('t1', 1, 'Patch', '', 0, 0, 0)")
		}
		helper.runMigrationsAndValidate(DB, 4, true).use { db ->
			db.query("SELECT title, plan_epoch_day, plan_start_min, duration_min, plan_reminded_utc_ms FROM todo").use { c ->
				c.moveToFirst()
				assertEquals("Patch", c.getString(0))
				for (i in 1..4)
					assertEquals(true, c.isNull(i))
			}
			db.execSQL("UPDATE todo SET plan_epoch_day = 20745, plan_start_min = 540, duration_min = 60")
		}
	}

	@Test
	fun v4ToV5AddsIgnored() {
		helper.createDatabase(DB, 4).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL("INSERT INTO todo (uid, company_id, title, refs, done, created_utc_ms, updated_utc_ms) VALUES ('t1', 1, 'Patch', '', 0, 0, 0)")
		}
		helper.runMigrationsAndValidate(DB, 5, true).use { db ->
			db.query("SELECT title, ignored_utc_ms FROM todo").use { c ->
				c.moveToFirst()
				assertEquals("Patch", c.getString(0))
				assertEquals(true, c.isNull(1))
			}
		}
	}

	@Test
	fun v5ToV6AddsColours() {
		helper.createDatabase(DB, 5).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL("INSERT INTO client (id, company_id, name, archived) VALUES (1, 1, 'Bank', 0)")
		}
		helper.runMigrationsAndValidate(DB, 6, true).use { db ->
			db.query("SELECT company.color, client.color FROM company, client").use { c ->
				c.moveToFirst()
				assertEquals(true, c.isNull(0))
				assertEquals(true, c.isNull(1))
			}
			db.execSQL("UPDATE company SET color = 4029418")
		}
	}

	@Test
	fun v6ToV7AddsTags() {
		helper.createDatabase(DB, 6).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL(
				"INSERT INTO time_entry (id, uid, company_id, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) " +
					"VALUES (1, 'e1', 1, 'CLOSED', 'Europe/Rome', 0, 0, 0)",
			)
			db.execSQL("INSERT INTO segment (entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source) VALUES (1, 'WORK', 0, 60000, 'Europe/Rome', 'TRACKED')")
		}
		helper.runMigrationsAndValidate(DB, 7, true).use { db ->
			db.query("SELECT COUNT(*) FROM segment").use { c ->
				c.moveToFirst()
				assertEquals(1, c.getInt(0))
			}
			db.execSQL("INSERT INTO entry_tag (entry_id, tag) VALUES (1, 'remoto')")
		}
	}

	@Test
	fun v7ToV8AddsListOrder() {
		helper.createDatabase(DB, 7).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL("INSERT INTO activity_preset (id, text) VALUES (1, 'Patch')")
		}
		helper.runMigrationsAndValidate(DB, 8, true).use { db ->
			db.query("SELECT company.sort_position, activity_preset.sort_position, activity_preset.last_used_utc_ms FROM company, activity_preset").use { c ->
				c.moveToFirst()
				for (i in 0..2)
					assertEquals(true, c.isNull(i))
			}
		}
	}

	@Test
	fun v8ToV9AddsPlannedEnd() {
		helper.createDatabase(DB, 8).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL(
				"INSERT INTO time_entry (id, uid, company_id, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) " +
					"VALUES (1, 'e1', 1, 'CLOSED', 'Europe/Rome', 0, 0, 0)",
			)
		}
		helper.runMigrationsAndValidate(DB, 9, true).use { db ->
			db.query("SELECT planned_end_utc_ms FROM time_entry").use { c ->
				c.moveToFirst()
				assertEquals(true, c.isNull(0))
			}
		}
	}

	@Test
	fun v9ToV10AddsCallsAndPauseNotes() {
		helper.createDatabase(DB, 9).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL(
				"INSERT INTO time_entry (id, uid, company_id, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) " +
					"VALUES (1, 'e1', 1, 'CLOSED', 'Europe/Rome', 0, 0, 0)",
			)
			db.execSQL("INSERT INTO segment (id, entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source) VALUES (1, 1, 'PAUSE', 0, 60000, 'Europe/Rome', 'TRACKED')")
		}
		helper.runMigrationsAndValidate(DB, 10, true).use { db ->
			db.query("SELECT note FROM segment").use { c ->
				c.moveToFirst()
				assertEquals(true, c.isNull(0))
			}
			db.query("SELECT COUNT(*) FROM contact").use { c ->
				c.moveToFirst()
				assertEquals(0, c.getInt(0))
			}
		}
	}

	@Test
	fun v10ToV11AddsMaterialsAndContactFields() {
		helper.createDatabase(DB, 10).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL("INSERT INTO client (id, company_id, name, archived) VALUES (1, 1, 'Bank', 0)")
			db.execSQL("INSERT INTO contact (id, client_id, name) VALUES (1, 1, 'Rossi')")
			db.execSQL(
				"INSERT INTO time_entry (id, uid, company_id, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) " +
					"VALUES (1, 'e1', 1, 'CLOSED', 'Europe/Rome', 0, 0, 0)",
			)
		}
		helper.runMigrationsAndValidate(DB, 11, true).use { db ->
			db.query("SELECT contact.email, time_entry.contact_id FROM contact, time_entry").use { c ->
				c.moveToFirst()
				assertEquals(true, c.isNull(0))
				assertEquals(true, c.isNull(1))
			}
			db.execSQL("INSERT INTO material (id, name, archived) VALUES (1, 'Cavo', 0)")
			db.execSQL("INSERT INTO entry_material (entry_id, material_id, description, created_utc_ms) VALUES (1, 1, 'Cavo', 0)")
		}
	}

	@Test
	fun v11ToV12AddsPolicyAndPluginData() {
		helper.createDatabase(DB, 11).use { db ->
			db.execSQL("INSERT INTO company (id, name, archived) VALUES (1, 'ACME', 0)")
			db.execSQL("INSERT INTO client (id, company_id, name, archived) VALUES (1, 1, 'Bank', 0)")
			db.execSQL("INSERT INTO contact (id, client_id, name) VALUES (1, 1, 'Rossi')")
		}
		helper.runMigrationsAndValidate(DB, 12, true).use { db ->
			db.query("SELECT external_key FROM contact").use { c ->
				c.moveToFirst()
				assertEquals(true, c.isNull(0))
			}
			db.execSQL("INSERT INTO policy_record (handled_utc_ms, fingerprint, applied) VALUES (0, 'f', 1)")
			db.execSQL("INSERT INTO remote_item (company_id, plugin_id, list, parent_id, id, name, active, extra) VALUES (1, 'antares', 'portfolio', '', 'S1', 'Assistenza', 1, '{}')")
		}
	}

	private companion object {
		const val DB = "migration-test.db"
	}
}
