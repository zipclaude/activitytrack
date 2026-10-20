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

	/* name required; numbers optional (issue #44), stored normalised; e-mail optional */
	suspend fun saveContact(c: ContactEntity, numbers: List<String>): Long = db.withTransaction {
		val name = c.name.trim().ifEmpty { throw ValidationException(Reason.BLANK_NAME) }
		val phones = numbers.map(PhoneNumbers::normalize).filter { it.trimStart('+').length >= 3 }.distinct()
		val email = c.email?.trim()?.ifEmpty { null }
		if (email != null && !EMAIL.matches(email))
			throw ValidationException(Reason.INVALID_VALUE)
		val clean = c.copy(name = name, email = email)
		val id = if (c.id == 0L) dao.insertContact(clean) else c.id.also { dao.updateContact(clean) }
		dao.clearPhones(id)
		dao.insertPhones(phones.map { ContactPhoneEntity(id, it) })
		id
	}

	suspend fun deleteContact(c: ContactEntity) = dao.deleteContact(c)

	/* The contact a number belongs to, matching with or without the international prefix. */
	suspend fun contactOf(number: String): ContactWithPhones? =
		dao.allContacts().firstOrNull { c -> c.phones.any { PhoneNumbers.same(it.number, number) } }

	private companion object {
		val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
	}

	/* true when new (the same call is recorded once) */
	suspend fun record(call: CallLogEntity): Boolean = dao.insertCall(call) != -1L
}
