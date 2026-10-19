/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.CallLogEntity
import it.infraconsulting.activitytrack.data.db.ContactEntity
import it.infraconsulting.activitytrack.data.db.ContactPhoneEntity
import it.infraconsulting.activitytrack.data.db.ContactWithPhones
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.calls.PhoneNumbers

/* Contacts of the clients and the calls recognised with them (issue #28). */
class CallRepository(private val db: ActivityTrackDatabase) {
	private val dao = db.callDao()

	val contacts = dao.observeContacts()
	fun callsOf(clientId: Long) = dao.observeCallsOf(clientId)

	/* name required, at least one number; numbers stored normalised */
	suspend fun saveContact(c: ContactEntity, numbers: List<String>): Long = db.withTransaction {
		val name = c.name.trim().ifEmpty { throw ValidationException(Reason.BLANK_NAME) }
		val phones = numbers.map(PhoneNumbers::normalize).filter { it.trimStart('+').length >= 3 }.distinct()
		if (phones.isEmpty())
			throw ValidationException(Reason.BLANK_VALUE)
		val id = if (c.id == 0L) dao.insertContact(c.copy(name = name)) else c.id.also { dao.updateContact(c.copy(name = name)) }
		dao.clearPhones(id)
		dao.insertPhones(phones.map { ContactPhoneEntity(id, it) })
		id
	}

	suspend fun deleteContact(c: ContactEntity) = dao.deleteContact(c)

	/* The contact a number belongs to, matching with or without the international prefix. */
	suspend fun contactOf(number: String): ContactWithPhones? =
		dao.allContacts().firstOrNull { c -> c.phones.any { PhoneNumbers.same(it.number, number) } }

	/* true when new (the same call is recorded once) */
	suspend fun record(call: CallLogEntity): Boolean = dao.insertCall(call) != -1L
}
