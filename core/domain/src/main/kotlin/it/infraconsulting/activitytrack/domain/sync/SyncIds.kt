/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.sync

import java.security.MessageDigest
import java.util.UUID

/*
 * Record identities for the sync with ActivityTrack Web (issue #111): every
 * synced row has a uid, a lower-case UUID with dashes, the same on every
 * node. Rows that must get the same identity on two phones without talking
 * (a day off, a plugin list item) take a name-based UUID (version 5).
 */
object SyncIds {
	/* namespace of the name-based uids; never change it */
	private val NAMESPACE: UUID = UUID.fromString("5b0c9f2e-8d0a-4c6b-9a43-2f8e1d7c6a10")

	fun random(): String = UUID.randomUUID().toString()

	/* RFC 9562 version 5: SHA-1 of namespace + name */
	fun nameBased(name: String, namespace: UUID = NAMESPACE): String {
		val sha = MessageDigest.getInstance("SHA-1")
		sha.update(bytes(namespace))
		sha.update(name.toByteArray(Charsets.UTF_8))
		val h = sha.digest()
		h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
		h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
		var msb = 0L
		var lsb = 0L
		for (i in 0..7) msb = (msb shl 8) or (h[i].toLong() and 0xff)
		for (i in 8..15) lsb = (lsb shl 8) or (h[i].toLong() and 0xff)
		return UUID(msb, lsb).toString()
	}

	/*
	 * The uid form of a key: a UUID in any spelling (upper case, without
	 * dashes, as the entries of schema 3 got) becomes canonical; any other
	 * key (an imported "entry-12") its name-based uid, so importing it again
	 * finds the same row.
	 */
	fun canonical(key: String): String {
		val k = key.trim()
		val hex = k.replace("-", "")
		if (hex.length == 32 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
			(k.length == 32 || (k.length == 36 && k[8] == '-' && k[13] == '-' && k[18] == '-' && k[23] == '-'))
		) {
			val h = hex.lowercase()
			return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
		}
		return nameBased("key:$k")
	}

	fun isCanonical(uid: String): Boolean = canonical(uid) == uid

	/* a day off is one per date: two phones marking the same day make one record */
	fun dayOff(epochDay: Long): String = nameBased("dayOff:$epochDay")

	/* a plugin list item, from its company's uid and its key in the list */
	fun remoteItem(companyUid: String, pluginId: String, list: String, parentId: String, id: String): String =
		nameBased("remoteItem:$companyUid\u0000$pluginId\u0000$list\u0000$parentId\u0000$id")

	private fun bytes(u: UUID): ByteArray {
		val b = ByteArray(16)
		var m = u.mostSignificantBits
		var l = u.leastSignificantBits
		for (i in 7 downTo 0) {
			b[i] = (m and 0xff).toByte(); m = m shr 8
			b[8 + i] = (l and 0xff).toByte(); l = l shr 8
		}
		return b
	}
}
