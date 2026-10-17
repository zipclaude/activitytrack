/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenericConnectorTest {
	private val sample = Interchange(
		generator = "test",
		createdUtc = "2026-10-17T10:00:00Z",
		companies = listOf(XCompany("ACME", "Acme S.p.A.")),
		clients = listOf(XClient("C-1", "ACME", "Bank & Co <HQ>"), XClient("C-2", "ACME", "Città", archived = true)),
		sites = listOf(XSite("client", "C-1", "HQ", "Via Roma 1", 45.4642, 9.19, 150)),
		roles = listOf(XRole("Sysadmin")),
		presets = listOf(XPreset("Backup check", "ACME", "Sysadmin")),
		todos = listOf(XTodo("T-9", "ACME", "C-1", "Patch servers", refs = listOf("INC0012"), dueDate = "2026-10-20", dueTime = "09:00", priority = 2)),
		entries = listOf(
			XEntry(
				key = "6f1c0f4e-0000-4000-8000-000000000001", companyKey = "ACME", clientKey = "C-1", todoKey = "T-9",
				activity = "Patched 3 servers", status = "CLOSED", zone = "Europe/Rome",
				segments = listOf(
					XSegment("TRAVEL", "2026-10-17T06:30:00Z", "2026-10-17T07:00:00Z", kmEstimated = 23.4),
					XSegment("WORK", "2026-10-17T07:00:00Z", "2026-10-17T11:00:00Z"),
				),
				expenses = listOf(XExpense("12.50", "Parking", "2026-10-17T07:01:00Z", receipt = true)),
				refs = listOf("INC0012", "https://example.org/t/1"),
			),
		),
		absences = listOf(XAbsence("2026-10-19", "LATE_ENTRY", "10:30")),
		daysOff = listOf(XDayOff("2026-12-07", "Sant'Ambrogio")),
	)

	private fun roundTrip(format: FileFormat): Interchange {
		val out = ByteArrayOutputStream()
		GenericConnector.write(format, sample, out)
		return GenericConnector.read(format, out.toByteArray().inputStream())
	}

	@Test
	fun jsonRoundTrip() = assertEquals(sample, roundTrip(FileFormat.JSON))

	@Test
	fun xmlRoundTrip() = assertEquals(sample, roundTrip(FileFormat.XML))

	@Test
	fun partialXmlWithClientsOnly() {
		val xml = """
			<activitytrack>
			  <format>activitytrack-interchange</format>
			  <version>1</version>
			  <clients>
			    <client><key>C-7</key><companyKey>ACME</companyKey><name>New client</name></client>
			  </clients>
			</activitytrack>
		""".trimIndent()
		val data = GenericConnector.read(FileFormat.XML, xml.byteInputStream())
		assertEquals(listOf(XClient("C-7", "ACME", "New client")), data.clients)
		assertTrue(data.entries.isEmpty() && data.companies.isEmpty())
	}

	@Test
	fun wrongFilesAreRejected() {
		assertFailsWith<IllegalArgumentException> {
			GenericConnector.read(FileFormat.JSON, """{"format":"other","version":1}""".byteInputStream())
		}
		assertFailsWith<IllegalArgumentException> {
			GenericConnector.read(FileFormat.JSON, """{"format":"activitytrack-interchange","version":99}""".byteInputStream())
		}
	}

	@Test
	fun xmlExternalEntitiesAreRefused() {
		val xxe = """<?xml version="1.0"?><!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]><activitytrack><format>&x;</format></activitytrack>"""
		assertFailsWith<Exception> { GenericConnector.read(FileFormat.XML, xxe.byteInputStream()) }
	}
}
