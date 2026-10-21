/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.ExternalRefKind
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentSource

/*
 * Conventions:
 * - instants are stored as UTC epoch milliseconds (suffix _utc_ms);
 * - local times of day are stored as minutes since midnight (suffix _min);
 * - local dates are stored as epoch days (suffix _epoch_day);
 * - enums are stored by name (Room built-in converter);
 * - attachment paths are relative to the user-selected data directory.
 */

/* Personal places; company and client locations are sites. */
enum class PlaceKind { HOME, OTHER }

enum class AttachmentKind { PHOTO, AUDIO, RECEIPT, FILE }

enum class AbsenceKind { FULL_DAY, LATE_ENTRY, EARLY_EXIT }

@Entity(
	tableName = "company",
	indices = [Index(value = ["name"], unique = true), Index(value = ["external_code"], unique = true)],
)
data class CompanyEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val name: String,
	val archived: Boolean = false,
	/* card band and agenda colour (M12), 0xRRGGBB; null = none chosen */
	val color: Int? = null,
	/* code in the management system (M4 interchange key) */
	@ColumnInfo(name = "external_code") val externalCode: String? = null,
	/* connector plugin of this company and its plain settings (JSON); secrets live in the keystore */
	@ColumnInfo(name = "connector_id") val connectorId: String? = null,
	@ColumnInfo(name = "connector_config") val connectorConfig: String? = null,
	/* manual list order (M16); null = never placed, after the placed ones */
	@ColumnInfo(name = "sort_position") val position: Int? = null,
)

@Entity(
	tableName = "company_site",
	foreignKeys = [
		ForeignKey(
			entity = CompanyEntity::class,
			parentColumns = ["id"],
			childColumns = ["company_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("company_id")],
)
data class CompanySiteEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "company_id") val companyId: Long,
	val label: String,
	val lat: Double,
	val lon: Double,
	@ColumnInfo(name = "radius_m") val radiusM: Int = 100,
	val address: String? = null,
)

@Entity(
	tableName = "client",
	foreignKeys = [
		ForeignKey(
			entity = CompanyEntity::class,
			parentColumns = ["id"],
			childColumns = ["company_id"],
			onDelete = ForeignKey.RESTRICT,
		),
	],
	indices = [
		Index(value = ["company_id", "name"], unique = true),
		Index(value = ["company_id", "external_code"], unique = true),
	],
)
data class ClientEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "company_id") val companyId: Long,
	val name: String,
	val archived: Boolean = false,
	/* band colour when one company is shown (M12), 0xRRGGBB; null = none */
	val color: Int? = null,
	@ColumnInfo(name = "external_code") val externalCode: String? = null,
	/* manual list order (M16); null = never placed, after the placed ones */
	@ColumnInfo(name = "sort_position") val position: Int? = null,
)

@Entity(
	tableName = "client_site",
	foreignKeys = [
		ForeignKey(
			entity = ClientEntity::class,
			parentColumns = ["id"],
			childColumns = ["client_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("client_id")],
)
data class ClientSiteEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "client_id") val clientId: Long,
	val label: String,
	val lat: Double,
	val lon: Double,
	@ColumnInfo(name = "radius_m") val radiusM: Int = 100,
	val address: String? = null,
)

@Entity(tableName = "place")
data class PlaceEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val kind: PlaceKind,
	val label: String,
	val lat: Double,
	val lon: Double,
	@ColumnInfo(name = "radius_m") val radiusM: Int = 100,
)

@Entity(tableName = "role", indices = [Index(value = ["name"], unique = true)])
data class RoleEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val name: String,
	val archived: Boolean = false,
	/* manual list order (M16); null = never placed, after the placed ones */
	@ColumnInfo(name = "sort_position") val position: Int? = null,
)

@Entity(
	tableName = "activity_preset",
	foreignKeys = [
		ForeignKey(
			entity = RoleEntity::class,
			parentColumns = ["id"],
			childColumns = ["default_role_id"],
			onDelete = ForeignKey.SET_NULL,
		),
		ForeignKey(
			entity = CompanyEntity::class,
			parentColumns = ["id"],
			childColumns = ["company_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("default_role_id"), Index("company_id")],
)
data class ActivityPresetEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val text: String,
	@ColumnInfo(name = "default_role_id") val defaultRoleId: Long? = null,
	@ColumnInfo(name = "company_id") val companyId: Long? = null,
	/* manual list order (M16); null = never placed, after the placed ones */
	@ColumnInfo(name = "sort_position") val position: Int? = null,
	/* last time it was picked for an entry (M16 "last use" order) */
	@ColumnInfo(name = "last_used_utc_ms") val lastUsedUtcMs: Long? = null,
)

/*
 * Planned activity, typically from a company's management system (M4, owner
 * decision 2026-10-17). Entries started from it link back via todo_id.
 */
@Entity(
	tableName = "todo",
	foreignKeys = [
		ForeignKey(
			entity = CompanyEntity::class,
			parentColumns = ["id"],
			childColumns = ["company_id"],
			onDelete = ForeignKey.RESTRICT,
		),
		ForeignKey(
			entity = ClientEntity::class,
			parentColumns = ["id"],
			childColumns = ["client_id"],
			onDelete = ForeignKey.RESTRICT,
		),
	],
	indices = [
		Index(value = ["uid"], unique = true),
		Index(value = ["company_id", "external_key"], unique = true),
		Index("client_id"),
		Index(value = ["done", "due_epoch_day"]),
	],
)
data class TodoEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val uid: String,
	@ColumnInfo(name = "company_id") val companyId: Long,
	@ColumnInfo(name = "client_id") val clientId: Long? = null,
	/* key in the management system; null for to-dos created in the app */
	@ColumnInfo(name = "external_key") val externalKey: String? = null,
	val title: String,
	val notes: String? = null,
	/* external references, one per line */
	val refs: String = "",
	@ColumnInfo(name = "due_epoch_day") val dueEpochDay: Long? = null,
	@ColumnInfo(name = "due_time_min") val dueTimeMin: Int? = null,
	val priority: Int? = null,
	val done: Boolean = false,
	@ColumnInfo(name = "created_utc_ms") val createdUtcMs: Long,
	@ColumnInfo(name = "updated_utc_ms") val updatedUtcMs: Long,
	/* planned slot (M9): local day and start, both or neither; duration optional */
	@ColumnInfo(name = "plan_epoch_day") val planEpochDay: Long? = null,
	@ColumnInfo(name = "plan_start_min") val planStartMin: Int? = null,
	@ColumnInfo(name = "duration_min") val durationMin: Int? = null,
	/* planned start (UTC ms) whose reminder was shown */
	@ColumnInfo(name = "plan_reminded_utc_ms") val planRemindedUtcMs: Long? = null,
	/* ignored (M10) instead of deleted: kept struck through, restorable, never re-created by imports */
	@ColumnInfo(name = "ignored_utc_ms") val ignoredUtcMs: Long? = null,
	/* entry fields of the company's plugin pre-filled by this imported to-do (issue #48), JSON object */
	@ColumnInfo(name = "plugin_fields") val pluginFields: String? = null,
)

@Entity(
	tableName = "time_entry",
	foreignKeys = [
		ForeignKey(
			entity = CompanyEntity::class,
			parentColumns = ["id"],
			childColumns = ["company_id"],
			onDelete = ForeignKey.RESTRICT,
		),
		ForeignKey(
			entity = ClientEntity::class,
			parentColumns = ["id"],
			childColumns = ["client_id"],
			onDelete = ForeignKey.RESTRICT,
		),
		ForeignKey(
			entity = RoleEntity::class,
			parentColumns = ["id"],
			childColumns = ["role_id"],
			onDelete = ForeignKey.RESTRICT,
		),
		ForeignKey(
			entity = TodoEntity::class,
			parentColumns = ["id"],
			childColumns = ["todo_id"],
			onDelete = ForeignKey.SET_NULL,
		),
		ForeignKey(
			entity = ContactEntity::class,
			parentColumns = ["id"],
			childColumns = ["contact_id"],
			onDelete = ForeignKey.SET_NULL,
		),
	],
	indices = [
		Index("company_id"), Index("client_id"), Index("role_id"), Index("status"), Index("todo_id"), Index("contact_id"),
		Index(value = ["uid"], unique = true),
	],
)
data class TimeEntryEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	/* stable identity across devices and exports (M4); null only before migration 2 -> 3 filled it */
	val uid: String? = null,
	@ColumnInfo(name = "todo_id") val todoId: Long? = null,
	/* the client's person the work was for (issue #44), optional */
	@ColumnInfo(name = "contact_id") val contactId: Long? = null,
	/*
	 * Company may be known before the client (company site match, copy from
	 * the previous entry). When client_id is set, company_id must equal the
	 * client's company (enforced by the repository).
	 */
	@ColumnInfo(name = "company_id") val companyId: Long?,
	/* null while a travel was started without a known destination */
	@ColumnInfo(name = "client_id") val clientId: Long?,
	@ColumnInfo(name = "role_id") val roleId: Long? = null,
	val activity: String? = null,
	val status: EntryStatus,
	/* open segment kind, non-null only when status == ACTIVE */
	val mode: SegmentKind? = null,
	val locality: String? = null,
	@ColumnInfo(name = "location_kind") val locationKind: LocationKind? = null,
	val notes: String? = null,
	@ColumnInfo(name = "zone_id") val zoneId: String,
	@ColumnInfo(name = "last_confirmed_utc_ms") val lastConfirmedUtcMs: Long? = null,
	@ColumnInfo(name = "unanswered_reminders") val unansweredReminders: Int = 0,
	/* indicative end chosen at start (M22): "finished?" asked then; cleared once asked */
	@ColumnInfo(name = "planned_end_utc_ms") val plannedEndUtcMs: Long? = null,
	/* values of the company plugin's entry fields (issue #48), JSON object */
	@ColumnInfo(name = "plugin_fields") val pluginFields: String? = null,
	@ColumnInfo(name = "created_utc_ms") val createdUtcMs: Long,
	@ColumnInfo(name = "updated_utc_ms") val updatedUtcMs: Long,
)

@Entity(
	tableName = "segment",
	foreignKeys = [
		ForeignKey(
			entity = TimeEntryEntity::class,
			parentColumns = ["id"],
			childColumns = ["entry_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index(value = ["entry_id", "start_utc_ms"]), Index("end_utc_ms")],
)
data class SegmentEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "entry_id") val entryId: Long,
	val kind: SegmentKind,
	@ColumnInfo(name = "start_utc_ms") val startUtcMs: Long,
	@ColumnInfo(name = "end_utc_ms") val endUtcMs: Long? = null,
	@ColumnInfo(name = "zone_id") val zoneId: String,
	val source: SegmentSource = SegmentSource.TRACKED,
	@ColumnInfo(name = "km_estimated") val kmEstimated: Double? = null,
	@ColumnInfo(name = "km_declared") val kmDeclared: Double? = null,
	/* description of a pause, e.g. "Telefonata Banca" for one made by a call (issue #28) */
	val note: String? = null,
)

/* Coordinates are displayed only; they are never exported. */
@Entity(
	tableName = "location_sample",
	foreignKeys = [
		ForeignKey(
			entity = TimeEntryEntity::class,
			parentColumns = ["id"],
			childColumns = ["entry_id"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = SegmentEntity::class,
			parentColumns = ["id"],
			childColumns = ["segment_id"],
			onDelete = ForeignKey.SET_NULL,
		),
	],
	indices = [Index(value = ["entry_id", "ts_utc_ms"]), Index("segment_id")],
)
data class LocationSampleEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "entry_id") val entryId: Long,
	@ColumnInfo(name = "segment_id") val segmentId: Long? = null,
	@ColumnInfo(name = "ts_utc_ms") val tsUtcMs: Long,
	val lat: Double,
	val lon: Double,
	@ColumnInfo(name = "accuracy_m") val accuracyM: Float?,
	val provider: String?,
)

@Entity(
	tableName = "attachment",
	foreignKeys = [
		ForeignKey(
			entity = TimeEntryEntity::class,
			parentColumns = ["id"],
			childColumns = ["entry_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("entry_id")],
)
data class AttachmentEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "entry_id") val entryId: Long,
	val kind: AttachmentKind,
	@ColumnInfo(name = "rel_path") val relPath: String,
	val mime: String,
	val transcript: String? = null,
	@ColumnInfo(name = "created_utc_ms") val createdUtcMs: Long,
)

@Entity(
	tableName = "expense",
	foreignKeys = [
		ForeignKey(
			entity = TimeEntryEntity::class,
			parentColumns = ["id"],
			childColumns = ["entry_id"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = SegmentEntity::class,
			parentColumns = ["id"],
			childColumns = ["segment_id"],
			onDelete = ForeignKey.SET_NULL,
		),
		ForeignKey(
			entity = AttachmentEntity::class,
			parentColumns = ["id"],
			childColumns = ["attachment_id"],
			onDelete = ForeignKey.SET_NULL,
		),
	],
	indices = [Index("entry_id"), Index("segment_id"), Index("attachment_id")],
)
data class ExpenseEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "entry_id") val entryId: Long,
	@ColumnInfo(name = "segment_id") val segmentId: Long? = null,
	/* EUR cents */
	@ColumnInfo(name = "amount_cents") val amountCents: Long,
	val description: String,
	@ColumnInfo(name = "attachment_id") val attachmentId: Long? = null,
	@ColumnInfo(name = "created_utc_ms") val createdUtcMs: Long,
)

@Entity(
	tableName = "external_ref",
	foreignKeys = [
		ForeignKey(
			entity = TimeEntryEntity::class,
			parentColumns = ["id"],
			childColumns = ["entry_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("entry_id"), Index("value")],
)
data class ExternalRefEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "entry_id") val entryId: Long,
	val kind: ExternalRefKind,
	val value: String,
)

/* A tag of an entry (backlog 13): lower case, without '#', no spaces. */
@Entity(
	tableName = "entry_tag",
	primaryKeys = ["entry_id", "tag"],
	foreignKeys = [
		ForeignKey(
			entity = TimeEntryEntity::class,
			parentColumns = ["id"],
			childColumns = ["entry_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("tag")],
)
data class EntryTagEntity(
	@ColumnInfo(name = "entry_id") val entryId: Long,
	val tag: String,
)

@Entity(tableName = "absence", indices = [Index("date_epoch_day")])
data class AbsenceEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "date_epoch_day") val dateEpochDay: Long,
	val kind: AbsenceKind,
	/* entry time for LATE_ENTRY, exit time for EARLY_EXIT, null for FULL_DAY */
	@ColumnInfo(name = "time_min") val timeMin: Int? = null,
)

/* A manual day off (holiday not in the national calendar, leave): no day-start prompt. */
@Entity(tableName = "day_off")
data class DayOffEntity(
	@PrimaryKey @ColumnInfo(name = "date_epoch_day") val dateEpochDay: Long,
	val note: String? = null,
)

/* Working hours; several intervals per day of week (1 = Monday ... 7 = Sunday). */
@Entity(tableName = "work_schedule", indices = [Index("day_of_week")])
data class WorkScheduleEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "day_of_week") val dayOfWeek: Int,
	@ColumnInfo(name = "start_min") val startMin: Int,
	@ColumnInfo(name = "end_min") val endMin: Int,
)

/* Lunch break per day of week (e.g. 12:30-14:00); pause reminders are muted in it. */
@Entity(tableName = "lunch_break", indices = [Index(value = ["day_of_week"], unique = true)])
data class LunchBreakEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "day_of_week") val dayOfWeek: Int,
	@ColumnInfo(name = "start_min") val startMin: Int,
	@ColumnInfo(name = "end_min") val endMin: Int,
)

/*
 * A person of a client (issue #28): calls from or to their numbers are
 * recognised. A contact belongs to one client, a client has many.
 */
@Entity(
	tableName = "contact",
	foreignKeys = [
		ForeignKey(entity = ClientEntity::class, parentColumns = ["id"], childColumns = ["client_id"], onDelete = ForeignKey.CASCADE),
	],
	indices = [Index("client_id")],
)
data class ContactEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "client_id") val clientId: Long,
	val name: String,
	/* optional (issue #44: for future use) */
	val email: String? = null,
	/* key in the management system of an imported contact (issue #48) */
	@ColumnInfo(name = "external_key") val externalKey: String? = null,
)

/* A number of a contact, as dialled (digits and a leading +). */
@Entity(
	tableName = "contact_phone",
	primaryKeys = ["contact_id", "number"],
	foreignKeys = [
		ForeignKey(entity = ContactEntity::class, parentColumns = ["id"], childColumns = ["contact_id"], onDelete = ForeignKey.CASCADE),
	],
	indices = [Index("number")],
)
data class ContactPhoneEntity(
	@ColumnInfo(name = "contact_id") val contactId: Long,
	val number: String,
)

enum class CallDirection { INCOMING, OUTGOING }

/* A recognised call with a contact (issue #28): the calls of a client and their length. */
@Entity(
	tableName = "call_log",
	foreignKeys = [
		ForeignKey(entity = ContactEntity::class, parentColumns = ["id"], childColumns = ["contact_id"], onDelete = ForeignKey.CASCADE),
		ForeignKey(entity = ClientEntity::class, parentColumns = ["id"], childColumns = ["client_id"], onDelete = ForeignKey.CASCADE),
	],
	indices = [Index("contact_id"), Index("client_id"), Index(value = ["start_utc_ms", "contact_id"], unique = true)],
)
data class CallLogEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "contact_id") val contactId: Long,
	@ColumnInfo(name = "client_id") val clientId: Long,
	val direction: CallDirection,
	@ColumnInfo(name = "start_utc_ms") val startUtcMs: Long,
	@ColumnInfo(name = "end_utc_ms") val endUtcMs: Long,
)

/*
 * A consumable left at clients (issue #41), in the master data: the
 * description, unit of measure and unit cost proposed when it is used;
 * [companyId] null = for every company.
 */
@Entity(
	tableName = "material",
	foreignKeys = [
		ForeignKey(entity = CompanyEntity::class, parentColumns = ["id"], childColumns = ["company_id"], onDelete = ForeignKey.SET_NULL),
	],
	indices = [Index("company_id")],
)
data class MaterialEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val name: String,
	val unit: String? = null,
	/* EUR cents */
	@ColumnInfo(name = "unit_cost_cents") val unitCostCents: Long? = null,
	@ColumnInfo(name = "company_id") val companyId: Long? = null,
	val archived: Boolean = false,
)

/*
 * A consumable left with an entry (issue #41): description required,
 * quantity, unit and unit cost optional; copied from the master data when
 * picked there, so a later price change leaves it as recorded.
 */
@Entity(
	tableName = "entry_material",
	foreignKeys = [
		ForeignKey(entity = TimeEntryEntity::class, parentColumns = ["id"], childColumns = ["entry_id"], onDelete = ForeignKey.CASCADE),
		ForeignKey(entity = MaterialEntity::class, parentColumns = ["id"], childColumns = ["material_id"], onDelete = ForeignKey.SET_NULL),
	],
	indices = [Index("entry_id"), Index("material_id")],
)
data class EntryMaterialEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "entry_id") val entryId: Long,
	@ColumnInfo(name = "material_id") val materialId: Long? = null,
	val description: String,
	val quantity: Double? = null,
	val unit: String? = null,
	@ColumnInfo(name = "unit_cost_cents") val unitCostCents: Long? = null,
	@ColumnInfo(name = "created_utc_ms") val createdUtcMs: Long,
) {
	/* quantity × unit cost; without a quantity one unit; null without a cost */
	val totalCents: Long? get() = unitCostCents?.let { Math.round(it * (quantity ?: 1.0)) }
}

/*
 * Every policy.json handled (issue #48): applied or refused, newest last.
 * The policy in force is the latest applied row's text; the first signed
 * applied row's CN is the one later policies must be signed with.
 */
@Entity(tableName = "policy_record")
data class PolicyRecordEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "handled_utc_ms") val handledUtcMs: Long,
	val fingerprint: String,
	val applied: Boolean,
	@ColumnInfo(name = "policy_id") val policyId: String? = null,
	@ColumnInfo(name = "policy_version") val policyVersion: String? = null,
	@ColumnInfo(name = "signer_cn") val signerCn: String? = null,
	/* the policy.json applied, as read */
	val text: String? = null,
	/* the reasons of a refusal, one per line */
	val errors: String? = null,
)

/*
 * An item of a plugin's remote list (issue #48): services, projects,
 * activity types… kept for the entry form. [parentId] "" at the top level;
 * the same id may appear under several parents (an activity type of two
 * services). Inactive items stay for the entries that use them.
 */
@Entity(
	tableName = "remote_item",
	primaryKeys = ["company_id", "plugin_id", "list", "parent_id", "id"],
	foreignKeys = [
		ForeignKey(entity = CompanyEntity::class, parentColumns = ["id"], childColumns = ["company_id"], onDelete = ForeignKey.CASCADE),
	],
)
data class RemoteItemEntity(
	@ColumnInfo(name = "company_id") val companyId: Long,
	@ColumnInfo(name = "plugin_id") val pluginId: String,
	val list: String,
	@ColumnInfo(name = "parent_id") val parentId: String,
	val id: String,
	val name: String,
	@ColumnInfo(name = "client_code") val clientCode: String? = null,
	val kind: String? = null,
	val active: Boolean = true,
	@ColumnInfo(name = "sort_order") val order: Int? = null,
	/* the extra values of the descriptor, JSON object */
	val extra: String = "{}",
)
