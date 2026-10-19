/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import java.time.Instant
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import it.infraconsulting.activitytrack.domain.time.latestAt
import it.infraconsulting.activitytrack.data.repo.ValidationException
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.StartRequest
import it.infraconsulting.activitytrack.data.repo.TodoRepository
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.start.ClientChoice
import it.infraconsulting.activitytrack.domain.start.ClientPicker
import it.infraconsulting.activitytrack.ui.ActionViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StartForm(
	val ready: Boolean = false,
	val mode: SegmentKind = SegmentKind.WORK,
	val companyId: Long? = null,		/* client filter; null = all companies */
	val clientId: Long? = null,
	val roleId: Long? = null,
	val activity: String = "",
	val locationKind: LocationKind = LocationKind.OTHER,
	/* user edits win over the per-client defaults */
	val activityTouched: Boolean = false,
	val roleTouched: Boolean = false,
	/* a late position-based proposal must not override a picked company or client */
	val clientTouched: Boolean = false,
	/* chosen start "hh:mm" (M17 editable times); blank = now */
	val startTime: String = "",
) {
	val canStart: Boolean get() = ready && (clientId != null || mode == SegmentKind.TRAVEL)
}

/* A running entry the user must decide about before the new one starts. */
data class RunningEntry(val entry: TimeEntryEntity, val title: String)

class StartViewModel(
	private val entries: EntryRepository,
	private val master: MasterDataRepository,
	/* picked outside the app (widget, shortcut): wins over every proposal */
	private val pickedClientId: Long?,
	/* started from a to-do: company, client and activity come from it */
	private val todoId: Long?,
	private val todos: TodoRepository,
	private val position: suspend () -> GeoPoint?,
	editableTimes: Flow<Boolean> = flowOf(false),
) : ActionViewModel() {
	val editableTimes = editableTimes.state(false)

	private val _form = MutableStateFlow(StartForm())
	val form = _form.asStateFlow()

	val companies = master.companies.state(emptyList())
	val roles = master.roles.state(emptyList())
	val presets = master.presets.state(emptyList())
	/* colour dots in the client list (backlog 14) */
	val clientColors = master.clients.map { list -> list.mapNotNull { c -> c.color?.let { c.id to it } }.toMap() }.state(emptyMap())

	private val allChoices = combine(master.clients, entries.clientLastUse) { clients, uses ->
		val last = uses.associate { it.clientId to it.lastMs }
		clients.map { ClientChoice(it.id, it.companyId, it.name, it.archived, last[it.id]) }
	}.state(emptyList())

	/* Clients shown for the current company filter, in the Master data order (M16). */
	val clients = combine(allChoices, _form) { all, f -> ClientPicker.order(all, f.companyId, f.clientId, byLastUse = false) }
		.state(emptyList())

	/* Running entries still to be asked about (head = current dialog). */
	private val _pending = MutableStateFlow<List<RunningEntry>>(emptyList())
	val pending = _pending.asStateFlow()
	private val decisions = HashMap<Long, EntryCommand>()
	private var onStarted: () -> Unit = {}

	/*
	 * The proposal without position is shown at once; when a fix arrives
	 * (SPEC §2.1, M2) a site match refines it unless the user already
	 * picked a company or client.
	 */
	init {
		viewModelScope.launch {
			val sites = master.knownSites()
			val p = entries.startProposal(sites)
			_form.update { it.copy(ready = true, companyId = p.companyId, clientId = p.clientId, locationKind = p.locationKind) }
			p.clientId?.let { applyDefaults(it) }
			todoId?.let { todos.find(it) }?.let { t ->
				_form.update {
					it.copy(
						companyId = t.companyId, clientId = t.clientId ?: it.clientId, clientTouched = true,
						activity = t.title, activityTouched = true,
					)
				}
				t.clientId?.let { applyDefaults(it) }
				return@launch
			}
			if (pickedClientId != null) {
				master.client(pickedClientId)?.let { c ->
					_form.update { it.copy(clientId = c.id, companyId = c.companyId, clientTouched = true) }
					applyDefaults(c.id)
				}
			}
			val here = position() ?: return@launch
			val q = entries.startProposal(sites, here)
			if (q == p || _form.value.clientTouched)
				return@launch
			_form.update { it.copy(companyId = q.companyId, clientId = q.clientId, locationKind = q.locationKind) }
			q.clientId?.let { applyDefaults(it) }
		}
	}

	fun setMode(mode: SegmentKind) = _form.update { it.copy(mode = mode) }

	fun setStartTime(hhmm: String) = _form.update { it.copy(startTime = hhmm) }

	/* Changing the company filter drops a client of another company. */
	fun setCompany(companyId: Long?) = _form.update { f ->
		val keep = f.clientId?.let { id -> allChoices.value.firstOrNull { it.id == id } }
			?.takeIf { companyId == null || it.companyId == companyId }
		f.copy(companyId = companyId, clientId = keep?.id, clientTouched = true)
	}

	fun setClient(clientId: Long?) {
		val c = allChoices.value.firstOrNull { it.id == clientId }
		_form.update { it.copy(clientId = clientId, companyId = c?.companyId ?: it.companyId, clientTouched = true) }
		if (clientId != null)
			viewModelScope.launch { applyDefaults(clientId) }
	}

	/* a client just created here (M11): select it with its company */
	fun pickNewClient(clientId: Long, companyId: Long) {
		_form.update { it.copy(clientId = clientId, companyId = companyId, clientTouched = true) }
		viewModelScope.launch { applyDefaults(clientId) }
	}

	fun setActivity(text: String) = _form.update { it.copy(activity = text, activityTouched = true) }
	fun setRole(roleId: Long?) = _form.update { it.copy(roleId = roleId, roleTouched = true) }

	fun applyPreset(presetId: Long) {
		val p = presets.value.firstOrNull { it.id == presetId } ?: return
		viewModelScope.launch { master.touchPreset(p.id) }
		_form.update {
			it.copy(
				activity = p.text,
				activityTouched = true,
				roleId = if (it.roleTouched || p.defaultRoleId == null) it.roleId else p.defaultRoleId,
			)
		}
	}

	/* Activity and role of the last entry on the client (30 d), unless edited. */
	private suspend fun applyDefaults(clientId: Long) {
		val d = entries.clientDefaults(clientId) ?: return
		_form.update { f ->
			if (f.clientId != clientId)
				f
			else
				f.copy(
					activity = if (f.activityTouched) f.activity else d.activity.orEmpty(),
					roleId = if (f.roleTouched) f.roleId else d.roleId,
				)
		}
	}

	/* Start, asking first about every ACTIVE entry (SPEC §3.1). */
	fun requestStart(onDone: () -> Unit) {
		val t = _form.value.startTime
		if (t.isNotBlank() && (parseHhMm(t) ?: 1440) >= 1440)
			return fail(ValidationException.Reason.INVALID_TIME)
		onStarted = onDone
		decisions.clear()
		viewModelScope.launch {
			val clients = master.clients.first().associateBy { it.id }
			val companies = master.companies.first().associateBy { it.id }
			val active = entries.openEntries.first().filter { it.status == EntryStatus.ACTIVE }
			_pending.value = active.map { e ->
				RunningEntry(e, e.clientId?.let { clients[it]?.name } ?: e.companyId?.let { companies[it]?.name }.orEmpty())
			}
			if (active.isEmpty())
				doStart()
		}
	}

	/* Decision for the head of the queue: STOP, SUSPEND, or null = leave running. */
	fun decide(cmd: EntryCommand?) {
		val head = _pending.value.firstOrNull() ?: return
		if (cmd != null)
			decisions[head.entry.id] = cmd
		_pending.value = _pending.value.drop(1)
		if (_pending.value.isEmpty())
			doStart()
	}

	fun cancelStart() {
		_pending.value = emptyList()
		decisions.clear()
	}

	private fun doStart() {
		val f = _form.value
		run(onStarted) {
			entries.start(
				StartRequest(
					mode = f.mode,
					clientId = f.clientId,
					companyId = if (f.clientId == null) f.companyId else null,
					roleId = f.roleId,
					activity = f.activity,
					locationKind = f.locationKind,
					todoId = todoId,
					/* a time without date is the latest one: today, or yesterday if still to come */
					startUtcMs = parseHhMm(f.startTime)?.takeIf { editableTimes.value && it < 1440 }
						?.let { latestAt(it, Instant.now(), ZoneId.systemDefault()).toEpochMilli() },
				),
				HashMap(decisions),
			)
		}
	}
}
