/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.EntryDetails
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.PastRequest
import it.infraconsulting.activitytrack.data.repo.PluginRepository
import it.infraconsulting.activitytrack.data.repo.StartRequest
import it.infraconsulting.activitytrack.data.repo.TodoRequest
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.isActive
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.data.repo.OnRunning
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.policy.EntrySource
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.domain.policy.Policy
import it.infraconsulting.activitytrack.domain.start.ClientChoice
import it.infraconsulting.activitytrack.domain.start.ClientPicker
import it.infraconsulting.activitytrack.domain.time.latestAt
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.common.parseDate
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/* How a Lavoro activity begins (ACTIVITY-MODEL.md 3, creation): now, from a time already passed, or a past one recorded whole. */
enum class StartWhen { NOW, STARTED, PAST }

data class StartForm(
	val ready: Boolean = false,
	val start: StartWhen = StartWhen.NOW,
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
	/* "Iniziata alle" "hh:mm" */
	val startTime: String = "",
	/* the day of a past activity, "yyyy-mm-dd"; blank = today */
	val date: String = "",
	/* "Tratti": before the start (Adesso, Già iniziata) or the whole activity (Passata) */
	val segments: List<SegDraft> = emptyList(),
	val notes: String = "",
	/* external references, one per line */
	val refs: String = "",
	/* the management system's fields as JSON, kept by the screen's form */
	val pluginFields: String? = null,
	/* what the to-do being started already had */
	val pluginInitial: String? = null,
) {
	val hasClient: Boolean get() = clientId != null
}

/* A recognised call to record (issue #28): rounded start and end, contact name. */
data class CallPrefill(val startMs: Long, val endMs: Long, val contact: String)

/* A running entry the user must decide about before the new one starts. */
data class RunningEntry(val entry: TimeEntryEntity, val title: String)

/*
 * "Lavoro" (creation dialog of the work activity). Adesso and Già iniziata
 * start the activity with an open segment of the kind of the button pressed
 * ([requestStart]); Passata saves a closed one ([savePast]).
 */
class StartViewModel(
	private val entries: EntryRepository,
	private val master: MasterDataRepository,
	private val pluginData: PluginRepository,
	/* picked outside the app (widget, shortcut): wins over every proposal */
	private val pickedClientId: Long?,
	/* started from a to-do: company, client and activity come from it */
	private val todoId: Long?,
	private val position: suspend () -> GeoPoint?,
	editableTimes: Flow<Boolean> = flowOf(false),
	/* the policy in force: the fields it makes required (issue #48) */
	private val policy: () -> Policy = { Policy.NONE },
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
	private val decisions = HashMap<Long, OnRunning>()
	private var onStarted: () -> Unit = {}
	private var kind = SegmentKind.WORK

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
			todoId?.let { entries.find(it) }?.let { t ->
				val refs = entries.externalRefValues(t.id).joinToString("\n")
				_form.update {
					it.copy(
						companyId = t.companyId, clientId = t.clientId ?: it.clientId, clientTouched = true,
						activity = t.activity.orEmpty(), activityTouched = true, roleId = t.roleId, roleTouched = t.roleId != null,
						notes = t.notes.orEmpty(), refs = refs, pluginInitial = t.pluginFields,
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

	/* Adesso, Già iniziata, Passata: what the form asks changes, what was typed for another is dropped */
	fun setStart(w: StartWhen) = _form.update { f ->
		when (w) {
			StartWhen.NOW -> f.copy(start = w, startTime = "", date = "")
			StartWhen.STARTED -> f.copy(start = w, date = "")
			StartWhen.PAST -> f.copy(start = w, startTime = "")
		}
	}

	fun setStartTime(hhmm: String) = _form.update { it.copy(startTime = hhmm) }
	fun setDate(iso: String) = _form.update { it.copy(date = iso) }
	fun setSegments(s: List<SegDraft>) = _form.update { it.copy(segments = s) }
	fun setNotes(s: String) = _form.update { it.copy(notes = s) }
	fun setRefs(s: String) = _form.update { it.copy(refs = s) }
	fun setPluginFields(json: String?) = _form.update { if (it.pluginFields == json) it else it.copy(pluginFields = json) }

	/* "Telefonata con NOME" from start to end of the call (issue #28): a past work segment; the client comes as picked */
	fun prefillCall(c: CallPrefill) {
		val zone = ZoneId.systemDefault()
		val from = Instant.ofEpochMilli(c.startMs).atZone(zone)
		val to = Instant.ofEpochMilli(c.endMs).atZone(zone)
		val start = from.hour * 60 + from.minute
		var end = to.hour * 60 + to.minute
		if (end <= start)
			end += 1440
		callActivity = c.contact
		_form.update { it.copy(start = StartWhen.PAST, date = from.toLocalDate().toString(), segments = listOf(SegDraft(SegmentKind.WORK, start, end))) }
	}

	/* applied after the client's defaults, which would otherwise replace it */
	private var callActivity: String? = null

	/* the description of an entry from a call (set by the screen in the app language) */
	var callText: (String) -> String = { "Chiamata telefonica con $it" }

	/*
	 * A start later than now (issue #37): plan it as a to-do today, start
	 * now, or take it as yesterday's (the latest occurrence, M17).
	 */
	private val _askFuture = MutableStateFlow<Int?>(null)
	val askFuture = _askFuture.asStateFlow()
	private var futureAnswered = false

	enum class FutureChoice { PLAN, NOW, YESTERDAY }

	fun answerFuture(choice: FutureChoice) {
		val min = _askFuture.value ?: return
		_askFuture.value = null
		when (choice) {
			FutureChoice.PLAN -> plan(min, LocalDate.now())
			FutureChoice.NOW -> {
				_form.update { it.copy(startTime = "") }
				requestStart(kind, onStarted)
			}
			FutureChoice.YESTERDAY -> {
				futureAnswered = true
				requestStart(kind, onStarted)
			}
		}
	}

	fun cancelFuture() {
		_askFuture.value = null
	}

	/* A planned to-do today from the form: client, description (or client), start. */
	private fun plan(startMin: Int, day: LocalDate) {
		val f = _form.value
		run(onStarted) {
			val client = f.clientId?.let { master.client(it) }
			val company = client?.companyId ?: f.companyId ?: throw ValidationException(ValidationException.Reason.CLIENT_REQUIRED)
			entries.saveTodo(
				null,
				TodoRequest(
					companyId = company, clientId = client?.id, activity = f.activity.trim().ifEmpty { client?.name.orEmpty() }, roleId = f.roleId,
					notes = f.notes, planEpochDay = day.toEpochDay(), planStartMin = startMin, pluginFields = f.pluginFields,
				),
			)
		}
	}

	/* the chosen start: a time without date is the latest one (today, or yesterday if still to come); null = now */
	private fun startMs(f: StartForm): Long? {
		if (!editableTimes.value || f.start != StartWhen.STARTED)
			return null
		val min = parseHhMm(f.startTime)?.takeIf { it < 1440 } ?: return null
		return latestAt(min, Instant.now(), ZoneId.systemDefault()).toEpochMilli()
	}

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
	fun pickNewClient(clientId: Long, companyId: Long?) {
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
		callActivity?.let { name ->
			_form.update { it.copy(activity = callText(name), activityTouched = true) }
			return
		}
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

	/* the policy's required fields and its "planned only" source; true = may go on */
	private fun checkPolicy(f: StartForm, travel: Boolean): Boolean {
		/* activities only from a TODO activity, e.g. a management system's tickets (policy entries.source, issue #48) */
		if (todoId == null && policy().entrySource == EntrySource.PLANNED_ONLY) {
			fail(ValidationException.Reason.PLANNED_ONLY)
			return false
		}
		val company = f.companyId ?: allChoices.value.firstOrNull { it.id == f.clientId }?.companyId
		policy().missingRequired(
			mapOf(
				Field.COMPANY to (company != null),
				Field.CLIENT to (f.clientId != null || travel),
				Field.ROLE to (f.roleId != null),
				Field.DESCRIPTION to f.activity.isNotBlank(),
				Field.NOTES to f.notes.isNotBlank(),
				Field.REFS to refLines(f.refs).isNotEmpty(),
			),
		)?.let {
			fail(ValidationException.Reason.POLICY_REQUIRED)
			return false
		}
		return true
	}

	/* "Lavoro" or "Viaggio": start with [kind] open, asking first about every activity running (SPEC §3.1). */
	fun requestStart(kind: SegmentKind, onDone: () -> Unit) {
		val f = _form.value
		val t = f.startTime
		if (editableTimes.value && f.start == StartWhen.STARTED && t.isNotBlank() && (parseHhMm(t) ?: 1440) >= 1440)
			return fail(ValidationException.Reason.INVALID_TIME)
		if (!checkPolicy(f, kind == SegmentKind.TRAVEL))
			return
		this.kind = kind
		onStarted = onDone
		decisions.clear()
		/* a start still to come today: ask first (issue #37) */
		val startMin = parseHhMm(t)?.takeIf { editableTimes.value && f.start == StartWhen.STARTED && it < 1440 }
		val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
		if (startMin != null && startMin > nowMin && !futureAnswered) {
			_askFuture.value = startMin
			return
		}
		futureAnswered = false
		askRunning()
	}

	private fun askRunning() {
		viewModelScope.launch {
			val clients = master.clients.first().associateBy { it.id }
			val companies = master.companies.first().associateBy { it.id }
			val all = entries.openEntries.first().filter { it.isActive() }
			/* "is the travel completed?" not asked (policy behaviours.askTravelDone, issue #48): a running travel stops */
			val active = if (policy().behaviours.askTravelDone) all else all.filter { e ->
				(e.mode != SegmentKind.TRAVEL).also { asked -> if (!asked) decisions[e.id] = OnRunning.STOP }
			}
			_pending.value = active.map { e ->
				RunningEntry(e, e.clientId?.let { clients[it]?.name } ?: e.companyId?.let { companies[it]?.name }.orEmpty())
			}
			if (active.isEmpty())
				doStart()
		}
	}

	/* Decision for the head of the queue: STOP, SUSPEND, or null = leave running. */
	fun decide(cmd: OnRunning?) {
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

	/* the segments typed before the start, on today (the day of an activity under way) */
	private fun earlier(f: StartForm) = if (!editableTimes.value) emptyList() else f.segments.map { it.toInput(LocalDate.now(), ZoneId.systemDefault()) }

	private fun doStart() {
		val f = _form.value
		run(onStarted) {
			entries.start(
				StartRequest(
					mode = kind,
					clientId = f.clientId,
					companyId = if (f.clientId == null) f.companyId else null,
					roleId = f.roleId,
					activity = f.activity,
					locationKind = f.locationKind,
					todoId = todoId,
					startUtcMs = startMs(f),
					notes = f.notes,
					pluginFields = f.pluginFields,
					earlier = earlier(f),
					externalRefs = refLines(f.refs),
				),
				HashMap(decisions),
			)
		}
	}

	/* "Salva" of a past activity: closed, with the segments typed on the chosen day. */
	fun savePast(onDone: () -> Unit) {
		val f = _form.value
		if (!checkPolicy(f, false))
			return
		val day = f.date.takeIf { it.isNotBlank() }?.let { parseDate(it) ?: return fail(ValidationException.Reason.INVALID_DATE) } ?: LocalDate.now()
		if (day.isAfter(LocalDate.now()))
			return fail(ValidationException.Reason.FUTURE_TIME)
		val zone = ZoneId.systemDefault()
		run(onDone) {
			val id = entries.createPast(
				PastRequest(
					EntryDetails(
						companyId = f.companyId, clientId = f.clientId, roleId = f.roleId, activity = f.activity, locality = null,
						locationKind = f.locationKind, notes = f.notes,
					),
					f.segments.map { it.toInput(day, zone) },
				),
			)
			f.pluginFields?.let { pluginData.setEntryFields(id, it) }
			for (r in refLines(f.refs))
				entries.addExternalRef(id, r)
		}
	}
}
