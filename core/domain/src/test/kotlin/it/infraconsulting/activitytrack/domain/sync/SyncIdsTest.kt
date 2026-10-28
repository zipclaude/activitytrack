/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.sync

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/* reference values from Python's uuid.uuid5 */
class SyncIdsTest {
	@Test
	fun nameBasedMatchesTheRfcExample() {
		val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
		assertEquals("2ed6657d-e927-568b-95e1-2665a8aea6a2", SyncIds.nameBased("www.example.com", dns))
	}

	@Test
	fun dayOffAndRemoteItemAreStable() {
		assertEquals("dc717b3c-23a4-5fee-bbc9-071d59c31eeb", SyncIds.dayOff(20000))
		assertEquals("ef7f7ef4-3f03-53a4-b99b-22ad16d7c5e3", SyncIds.remoteItem("c", "p", "l", "", "i"))
		assertNotEquals(SyncIds.remoteItem("c", "p", "l", "a", "i"), SyncIds.remoteItem("c", "p", "l", "", "ai"))
	}

	@Test
	fun canonicalForms() {
		val u = "6f1c0f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f"
		assertEquals(u, SyncIds.canonical(u))
		assertEquals(u, SyncIds.canonical(u.uppercase()))
		assertEquals(u, SyncIds.canonical(u.replace("-", "")))
		assertEquals("6c8b2ea7-acd2-59bf-b5f3-b67b863ba30b", SyncIds.canonical("entry-12"))
		/* 32 hex digits with dashes in the wrong places are a key, not a UUID */
		assertNotEquals(u, SyncIds.canonical("6f1c0f4e1a2b-4c3d-8e9f-0a1b2c3d4e5f"))
		assertTrue(SyncIds.isCanonical(SyncIds.random()))
	}
}
