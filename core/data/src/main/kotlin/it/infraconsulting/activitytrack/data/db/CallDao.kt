/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/* A contact with its numbers. */
data class ContactWithPhones(
	@androidx.room.Embedded val contact: ContactEntity,
	@androidx.room.Relation(parentColumn = "id", entityColumn = "contact_id")
	val phones: List<ContactPhoneEntity>,
)

/* A call of a client with the contact's name. */
data class CallRow(
	@androidx.room.Embedded val call: CallLogEntity,
	@ColumnInfo(name = "contact_name") val contactName: String,
)

/* Contacts and recognised calls (issue #28). */
@Dao
interface CallDao {
	@androidx.room.Transaction
	@Query("SELECT * FROM contact ORDER BY name COLLATE NOCASE")
	fun observeContacts(): Flow<List<ContactWithPhones>>

	@androidx.room.Transaction
	@Query("SELECT * FROM contact")
	suspend fun allContacts(): List<ContactWithPhones>

	@Insert
	suspend fun insertContact(c: ContactEntity): Long

	@Update
	suspend fun updateContact(c: ContactEntity)

	@Delete
	suspend fun deleteContact(c: ContactEntity)

	@Query("DELETE FROM contact_phone WHERE contact_id = :contactId")
	suspend fun clearPhones(contactId: Long)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertPhones(list: List<ContactPhoneEntity>)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCall(c: CallLogEntity): Long

	@Query(
		"SELECT call_log.*, contact.name AS contact_name FROM call_log JOIN contact ON contact.id = call_log.contact_id " +
			"WHERE call_log.client_id = :clientId ORDER BY start_utc_ms DESC",
	)
	fun observeCallsOf(clientId: Long): Flow<List<CallRow>>

	@Query("SELECT * FROM contact WHERE id = :id")
	suspend fun contact(id: Long): ContactEntity?
}
