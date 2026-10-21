/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import it.infraconsulting.activitytrack.domain.settings.AppSettings
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolicyParserTest {
	private fun policy(body: String) = """{ "format": "activitytrack-policy", "schema": 1$body }"""

	private fun valid(body: String): Policy = assertIs<PolicyResult.Valid>(PolicyParser.parse(policy(body)), "expected valid").policy

	private fun errors(text: String): List<PolicyError> = assertIs<PolicyResult.Invalid>(PolicyParser.parse(text)).errors

	@Test
	fun emptyPolicyIsTheAppAsShipped() {
		val p = valid("")
		assertEquals(Policy.NONE, p)
		assertTrue(Feature.entries.all { p.enabled(it) })
	}

	@Test
	fun fullExample() {
		val p = valid(
			""",
			"id": "acme", "version": "3",
			"features": { "planning": false, "concurrentEntries": false },
			"fields": {
				"company": { "label": { "it": "Dipartimento", "en": "Department" }, "colors": { "invalid": "#C62828", "valid": "#2E7D32" } },
				"client": { "label": { "en": "Project" } },
				"km": { "visible": false },
				"role": { "required": true }
			},
			"settings": { "editableTimes": { "value": false, "locked": true }, "roundingWorkMin": 30, "compactDisplay": { "hidden": true } },
			"behaviours": { "askTravelDone": false, "minDurationSec": 0 },
			"integrations": { "antares": { "plugin": "antares.json" } },
			"entries": { "source": "todosOnly" },
			"masterData": { "source": "importOnly" },
			"export": { "formats": ["csv"] },
			"ui": { "language": "it" }
			""",
		)
		assertEquals("acme", p.id)
		assertFalse(p.enabled(Feature.PLANNING))
		assertTrue(p.enabled(Feature.TRAVEL))
		assertEquals("Dipartimento", p.field(Field.COMPANY).labelFor("it"))
		assertEquals("Department", p.field(Field.COMPANY).labelFor("de"))
		assertEquals(0xFFC62828, p.field(Field.COMPANY).invalidColor)
		assertEquals("Project", p.field(Field.CLIENT).labelFor("it"))
		assertFalse(p.field(Field.KM).visible)
		assertEquals(true, p.field(Field.ROLE).required)
		assertEquals(EntrySource.TODOS_ONLY, p.entrySource)
		assertEquals(setOf("csv"), p.exportFormats)
		assertFalse(p.behaviours.askTravelDone)
		assertTrue(p.settings.getValue("compactDisplay").fixed)

		/* import writes every value, every later read only the fixed ones */
		val user = AppSettings(editableTimes = true)
		val imported = p.applyTo(user)
		assertFalse(imported.editableTimes)
		assertEquals(Duration.ofMinutes(30), imported.rounding.work.quantum)
		val chosen = imported.copy(editableTimes = true, rounding = user.rounding)
		val enforced = p.enforce(chosen)
		assertFalse(enforced.editableTimes)
		assertEquals(user.rounding, enforced.rounding)
	}

	@Test
	fun syntaxErrorHasItsLine() {
		val e = errors("{\n  \"format\": \"activitytrack-policy\",\n  \"schema\": 1,,\n}").single()
		assertEquals(3, e.line)
		assertTrue(e.message.startsWith("syntax error"))
	}

	@Test
	fun unknownKeysAndBadValuesAreAllReported() {
		val text = policy(
			""",
			"featurs": {},
			"features": { "planing": false, "travel": "no" },
			"fields": { "km": { "visibel": false }, "notes": { "colors": { "valid": "green" } } },
			"settings": { "backupHour": 25, "editableTimes": { "value": 1 }, "nope": true },
			"integrations": { "Antares": {}, "nit": { "plugin": "../x.json" } }
			""",
		)
		val paths = errors(text).map { it.path }.toSet()
		assertEquals(
			setOf(
				"featurs", "features.planing", "features.travel", "fields.km.visibel", "fields.notes.colors.valid",
				"settings.backupHour.value", "settings.editableTimes.value", "settings.nope", "integrations.Antares", "integrations.nit.plugin",
			),
			paths,
		)
	}

	@Test
	fun errorsCarryTheLineOfTheirKey() {
		val text = policy(",\n\"fields\": {\n  \"km\": {\n    \"visibel\": false\n  }\n}")
		assertEquals(4, errors(text).single().line)
	}

	@Test
	fun formatAndSchemaAreChecked() {
		assertEquals(setOf("format", "schema"), errors("{}").map { it.path }.toSet())
		assertEquals("schema", errors("""{ "format": "activitytrack-policy", "schema": 2 }""").single().path)
	}

	@Test
	fun essentialFeaturesAndFieldsStay() {
		val paths = errors(
			policy(
				""",
				"features": { "entries": false, "dayReport": false, "autoBackup": false },
				"fields": { "description": { "visible": false }, "times": { "enabled": false }, "client": { "required": false } }
				""",
			),
		).map { it.path }.toSet()
		assertEquals(
			setOf("features.entries", "features.dayReport", "features.autoBackup", "fields.description", "fields.times", "fields.client.required"),
			paths,
		)
	}

	@Test
	fun clientMayGoOnlyWhileTheCompanyStays() {
		/* a freelancer without companies keeps the client; a client-less use keeps the company, now required */
		valid(""", "fields": { "company": { "visible": false } }""")
		valid(""", "fields": { "client": { "visible": false } }""")
		assertEquals("fields.client", errors(policy(""", "fields": { "client": { "visible": false }, "company": { "visible": false } }""")).first().path)
		assertEquals("fields.company.required", errors(policy(""", "fields": { "client": { "visible": false }, "company": { "required": false } }""")).single().path)
	}

	@Test
	fun coherence() {
		assertEquals("fields.km.required", errors(policy(""", "fields": { "km": { "visible": false, "required": true } }""")).single().path)
		assertEquals("entries.source", errors(policy(""", "features": { "todos": false }, "entries": { "source": "todosOnly" }""")).single().path)
		assertEquals("masterData.source", errors(policy(""", "features": { "import": false }, "masterData": { "source": "importOnly" }""")).single().path)
		valid(""", "features": { "import": false }, "masterData": { "source": "importOnly" }, "integrations": { "antares": { "plugin": "antares.json" } }""")
		assertEquals("export.formats", errors(policy(""", "export": { "formats": [] }""")).single().path)
	}

	@Test
	fun fingerprintIsSha256Hex() {
		assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", PolicyParser.fingerprint(ByteArray(0)))
	}
}
