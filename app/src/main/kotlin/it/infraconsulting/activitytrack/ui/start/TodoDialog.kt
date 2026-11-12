/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.TodoRequest
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.domain.activity.Priority
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import it.infraconsulting.activitytrack.domain.todo.alignedDue
import it.infraconsulting.activitytrack.domain.todo.startsAfterDue
import it.infraconsulting.activitytrack.ui.common.DateField
import it.infraconsulting.activitytrack.ui.common.DateTimeField
import it.infraconsulting.activitytrack.ui.common.DictationInput
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.ui.common.PolicyField
import it.infraconsulting.activitytrack.ui.common.PriorityField
import it.infraconsulting.activitytrack.ui.common.QuickClientDialog
import it.infraconsulting.activitytrack.ui.common.QuickCompanyDialog
import it.infraconsulting.activitytrack.ui.common.RangeField
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.TimeGridSheet
import it.infraconsulting.activitytrack.ui.common.WithAddButton
import it.infraconsulting.activitytrack.ui.common.companiesShown
import it.infraconsulting.activitytrack.ui.common.enabled
import it.infraconsulting.activitytrack.ui.common.fieldImportance
import it.infraconsulting.activitytrack.ui.common.fieldLabel
import it.infraconsulting.activitytrack.ui.common.fieldShown
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.lastPick
import it.infraconsulting.activitytrack.ui.common.lastPickViewModel
import it.infraconsulting.activitytrack.ui.common.parseDate
import it.infraconsulting.activitytrack.ui.common.reasonText
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/* "" -> null; anything else must parse, or the field is reported. */
private inline fun <T> optional(text: String, parse: (String) -> T?): Result<T?> =
	if (text.isBlank()) Result.success(null) else parse(text)?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(text))

/*
 * "Da fare": creates (or edits) a TODO activity (ACTIVITY-MODEL.md 3). Fields
 * in the owner's order: Azienda, Cliente, Descrizione, Data, Orario (start
 * and optional end), Scadenza, Priorità; under "Altre informazioni": Note,
 * Riferimenti esterni, Tratti (planned segments, drawn dashed); the
 * management system's fields (required ones with the visible fields).
 *
 * [initial] null = a new empty one; an entity without id = a new one with
 * these values (a slot of the agenda, a company chosen); with an id = the
 * TODO activity being edited.
 */
@Composable
fun TodoDialog(initial: TimeEntryEntity? = null, onClose: () -> Unit) {
	val c = (LocalContext.current.applicationContext as ActivityTrackApp).container
	val scope = rememberCoroutineScope()
	val zone = ZoneId.systemDefault()
	val clients by c.masterData.clients.collectAsState(emptyList())
	val companies by c.masterData.companies.collectAsState(emptyList())
	val policy = LocalPolicy.current
	var error by remember { mutableStateOf<Reason?>(null) }
	val editId = initial?.id?.takeIf { it != 0L }
	val isNew = editId == null
	var title by remember { mutableStateOf(initial?.activity.orEmpty()) }
	/* a new one starts from the company and client last chosen in a dialog (issue #106) */
	val last = lastPickViewModel()
	val (lastCompany, lastClient) = lastPick()
	var clientId by remember { mutableStateOf(initial?.clientId) }
	/* the companies may still be loading when the dialog opens (agenda): default to the first once known */
	var picked by remember { mutableStateOf(initial?.companyId) }
	/* the last pick may arrive after the first frame: taken once, if nothing was chosen meanwhile */
	var lastTaken by remember { mutableStateOf(!isNew || initial?.companyId != null) }
	LaunchedEffect(lastCompany, lastClient, clients, companies) {
		if (lastTaken || (lastCompany == null && lastClient == null))
			return@LaunchedEffect
		/* a remembered company that no longer exists (deleted, other data) must not be taken: it would break the foreign key */
		if (picked == null && companies.any { it.id == lastCompany })
			picked = lastCompany
		if (clientId == null)
			clientId = lastClient?.takeIf { id -> clients.firstOrNull { it.id == id }?.companyId == picked }
		lastTaken = companies.isNotEmpty() && (clients.isNotEmpty() || lastClient == null)
	}
	var addCompany by remember { mutableStateOf(false) }
	var addClient by remember { mutableStateOf(false) }
	/* without companies (policy, issue #48) a to-do belongs to none */
	val withCompanies = companiesShown()
	val companyId = if (withCompanies) picked?.takeIf { id -> companies.isEmpty() || companies.any { it.id == id } } ?: companies.firstOrNull()?.id else null
	var planDay by remember { mutableStateOf(initial?.planEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
	var planTime by remember { mutableStateOf(initial?.planStartMin?.let(::formatMin).orEmpty()) }
	var planEnd by remember {
		mutableStateOf(initial?.planStartMin?.let { s0 -> initial.durationMin?.let { formatMin((s0 + it).coerceAtMost(1440)) } }.orEmpty())
	}
	var planGrid by remember { mutableStateOf(false) }
	var due by remember { mutableStateOf(initial?.dueEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
	var dueTime by remember { mutableStateOf(initial?.dueTimeMin?.let(::formatMin).orEmpty()) }
	var priority by remember { mutableStateOf(Priority.of(initial?.priority ?: 0)) }
	var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
	var refs by remember { mutableStateOf("") }
	var segments by remember { mutableStateOf<List<SegDraft>>(emptyList()) }
	/* planned segments are minutes from the day of the plan, or from today without one */
	var segmentsDay by remember { mutableStateOf<LocalDate?>(null) }
	var existingRefs by remember { mutableStateOf<List<it.infraconsulting.activitytrack.data.db.ExternalRefEntity>>(emptyList()) }
	var loaded by remember { mutableStateOf(isNew) }
	LaunchedEffect(editId) {
		if (editId == null)
			return@LaunchedEffect
		val planned = c.entries.segments(editId).first().filter { it.planned }.sortedBy { it.startUtcMs }
		val base = planned.firstOrNull()?.let { java.time.Instant.ofEpochMilli(it.startUtcMs).atZone(zone).toLocalDate() }
		segmentsDay = initial?.planEpochDay?.let(LocalDate::ofEpochDay) ?: base
		segments = planned.mapNotNull { s ->
			val end = s.endUtcMs ?: return@mapNotNull null
			val (a, b) = it.infraconsulting.activitytrack.data.repo.SegmentInput(s.kind, s.startUtcMs, end).minutesFrom(segmentsDay!!, zone)
			SegDraft(s.kind, a, b)
		}
		existingRefs = c.entries.externalRefs(editId).first()
		refs = existingRefs.joinToString("\n") { it.value }
		loaded = true
	}
	var more by remember { mutableStateOf(notes.isNotBlank()) }
	/* the plan is after the due date: offer to align it before saving (issue #71) */
	var alignDue by remember { mutableStateOf<TodoRequest?>(null) }
	/* a plan needs a day: the time without it is flagged on its field after Save (M13) */
	var tried by remember { mutableStateOf(false) }
	val planDayMissing = tried && planTime.isNotBlank() && planDay.isBlank()
	val shownCompany = clientId?.let { id -> clients.firstOrNull { it.id == id }?.companyId } ?: companyId
	val form = rememberPluginForm(shownCompany, clientId, initial?.pluginFields)
	/* the day the segments belong to */
	val day = parseDate(planDay) ?: segmentsDay ?: LocalDate.now()
	val planning = enabled(Feature.PLANNING)

	fun save(req: TodoRequest) {
		scope.launch {
			try {
				val id = c.entries.saveTodo(editId, req)
				/* the references are rows: add the new ones, drop the removed */
				val wanted = refLines(refs)
				val have = if (editId == null) emptyList() else existingRefs
				for (r in have.filter { it.value !in wanted })
					c.entries.removeExternalRef(id, r.id)
				for (r in wanted.filter { w -> have.none { it.value == w } })
					c.entries.addExternalRef(id, r)
				last.remember(req.companyId, req.clientId)
				onClose()
			} catch (x: ValidationException) {
				error = x.reason
			}
		}
	}

	alignDue?.let { pending ->
		AlertDialog(
			/* outside: back to the editor, nothing saved; No: save as it is */
			onDismissRequest = { alignDue = null },
			text = {
				Text(
					stringResource(
						R.string.todo_align_due,
						LocalDate.ofEpochDay(pending.planEpochDay!!).format(DateTimeFormatter.ofPattern("EEE d/MM", LocalConfiguration.current.locales[0])),
					),
				)
			},
			confirmButton = {
				TextButton(onClick = {
					val (d, min) = alignedDue(TodoPlan.of(pending.planEpochDay, pending.planStartMin, pending.durationMin)!!, pending.dueTimeMin, zone)
					save(pending.copy(dueEpochDay = d.toEpochDay(), dueTimeMin = min))
					alignDue = null
				}) { Text(stringResource(R.string.todo_align_due_yes)) }
			},
			dismissButton = { TextButton(onClick = { save(pending); alignDue = null }) { Text(stringResource(R.string.todo_align_due_no)) } },
		)
		return
	}

	EditDialog(
		title = stringResource(if (isNew) R.string.todo_new else R.string.todo_edit),
		/* a blank title is shown on its own field */
		error = error.takeIf { it != Reason.BLANK_VALUE },
		onDismiss = onClose,
		onConfirm = confirm@{
			if (!loaded)
				return@confirm
			val company = clientId?.let { id -> clients.firstOrNull { it.id == id }?.companyId } ?: companyId
			if (company == null && withCompanies) {
				error = Reason.CLIENT_REQUIRED
				return@confirm
			}
			if (planDay.isBlank() && planTime.isNotBlank()) {
				tried = true
				return@confirm
			}
			val pd = optional(planDay) { parseDate(it)?.toEpochDay() }
			val dd = optional(due) { parseDate(it)?.toEpochDay() }
			if (pd.isFailure || dd.isFailure) {
				error = Reason.INVALID_DATE
				return@confirm
			}
			/* 24:00 is an end, not a start */
			val pt = optional(planTime) { parseHhMm(it)?.takeIf { m -> m < 1440 } }
			val dt = optional(dueTime, ::parseHhMm)
			if (pt.isFailure || dt.isFailure) {
				error = Reason.INVALID_TIME
				return@confirm
			}
			/* the end on the same day, after the start; empty = no duration (1 h growing) */
			val dur = optional(planEnd) { e -> parseHhMm(e)?.let { m -> pt.getOrNull()?.let { m - it } }?.takeIf { d -> d > 0 } }
			if (dur.isFailure) {
				error = Reason.INVALID_TIME_RANGE
				return@confirm
			}
			policy.missingRequired(
				mapOf(
					Field.CLIENT to (clientId != null), Field.NOTES to notes.isNotBlank(), Field.REFS to refLines(refs).isNotEmpty(),
					Field.TODO_PRIORITY to (priority != Priority.NONE), Field.TODO_DUE to (dd.getOrNull() != null), Field.TODO_PLAN to (pd.getOrNull() != null),
				),
			)?.let {
				error = Reason.POLICY_REQUIRED
				return@confirm
			}
			val req = TodoRequest(
				companyId = company, clientId = clientId, activity = title, notes = notes, roleId = initial?.roleId,
				dueEpochDay = dd.getOrNull(), dueTimeMin = dt.getOrNull(),
				planEpochDay = pd.getOrNull(), planStartMin = pt.getOrNull(), durationMin = dur.getOrNull(),
				priority = priority.value, planned = segments.map { it.toInput(day, zone) }, pluginFields = form?.json,
			)
			error = null
			/* the plan starts past the due date (also later the same day): ask before saving (issue #71) */
			if (startsAfterDue(TodoPlan.of(req.planEpochDay, req.planStartMin, req.durationMin), req.dueEpochDay?.let(LocalDate::ofEpochDay), req.dueTimeMin, zone)) {
				alignDue = req
				return@confirm
			}
			save(req)
		},
	) {
		if (fieldShown(Field.COMPANY)) WithAddButton(stringResource(R.string.quick_company), { addCompany = true }, masterData = true) { PolicyField(Field.COMPANY) {
			ChoiceField(
				fieldLabel(Field.COMPANY, R.string.field_company), companies.map { it.id to it.name }, shownCompany,
				importance = Importance.REQUIRED, colorOf = { id -> companies.firstOrNull { it.id == id }?.color },
			) { co ->
				picked = co
				/* a client of another company goes */
				if (clients.firstOrNull { it.id == clientId }?.companyId != co)
					clientId = null
			}
		} }
		if (fieldShown(Field.CLIENT)) WithAddButton(stringResource(R.string.quick_client), { addClient = true }, masterData = true) { PolicyField(Field.CLIENT) {
			SearchableChoiceField(
				fieldLabel(Field.CLIENT, R.string.field_client),
				listOf<Pair<Long?, String>>(null to stringResource(R.string.field_none)) +
					clients.filter { !it.archived && (shownCompany == null || it.companyId == shownCompany) }.map { it.id to it.name },
				clientId,
				importance = fieldImportance(Field.CLIENT, Importance.IMPORTANT),
				colorOf = { id -> clients.firstOrNull { it.id == id }?.color },
			) { clientId = it }
		} }
		PolicyField(Field.DESCRIPTION) {
			DictationInput(fieldLabel(Field.DESCRIPTION, R.string.todo_field_title), title, { title = it; if (error == Reason.BLANK_VALUE) error = null }, Importance.REQUIRED,
				error = if (error == Reason.BLANK_VALUE) reasonText(error!!) else null)
		}
		/* the planned slot only with planning (policy, issue #48) */
		if (planning && fieldShown(Field.TODO_PLAN)) PolicyField(Field.TODO_PLAN) {
			DateField(
				fieldLabel(Field.TODO_PLAN, R.string.field_date), planDay, { planDay = it }, clearable = true,
				error = stringResource(R.string.todo_plan_date_missing).takeIf { planDayMissing }, importance = fieldImportance(Field.TODO_PLAN, Importance.IMPORTANT),
			)
			/* one full-width field: the times stay readable (issue #47) */
			RangeField(
				stringResource(R.string.todo_plan_time_optional), planTime, planEnd, { planGrid = true }, { planTime = ""; planEnd = "" },
				importance = fieldImportance(Field.TODO_PLAN, Importance.IMPORTANT),
			)
		}
		PolicyField(Field.TODO_DUE) {
			DateTimeField(fieldLabel(Field.TODO_DUE, R.string.todo_field_due), due, dueTime, { d, t -> due = d; dueTime = t }, importance = fieldImportance(Field.TODO_DUE, Importance.OPTIONAL))
			/* planned after the due date: it would come out overdue — say it now
			 * and offer to align the two dates right here (issue #76) */
			val livePlan = TodoPlan.of(parseDate(planDay)?.toEpochDay(), parseHhMm(planTime)?.takeIf { it < 1440 }, null)
			if (startsAfterDue(livePlan, parseDate(due), parseHhMm(dueTime), zone)) {
				/* text above the button: side by side the long text squeezed the button
				 * to no width, its label wrapped letter by letter and grew very tall */
				Column(Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.todo_will_overdue), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
					TextButton(modifier = Modifier.align(Alignment.End), onClick = {
						val (d, min) = alignedDue(livePlan!!, parseHhMm(dueTime), zone)
						due = d.toString()
						dueTime = min?.let(::formatMin).orEmpty()
					}) { Text(stringResource(R.string.todo_align_due_yes)) }
				}
			}
		}
		PolicyField(Field.TODO_PRIORITY) { PriorityField(priority) { priority = it } }
		form?.let { if (it.hasRequired) PluginRows(it, required = true) }
		MoreInfoToggle(more) { more = !more }
		if (more) {
			NotesInput(notes) { notes = it }
			RefsInput(refs) { refs = it }
			if (planning)
				SegmentsEditor(
					segments,
					{
						/* the first segment of a plan without a day: today becomes the day, so it is seen */
						if (planDay.isBlank() && it.isNotEmpty() && segments.isEmpty())
							planDay = day.toString()
						segments = it
					},
					day, planned = true,
				)
			form?.let { if (it.hasOptional) PluginRows(it, required = false) }
		}
	}
	if (planGrid) {
		val a = parseHhMm(planTime)
		val b = parseHhMm(planEnd)
		TimeGridSheet(
			title = stringResource(R.string.todo_field_plan_date),
			initial = if (a != null && b != null && b > a && a < 1440) a until b else if (a != null && a < 1440) a until a + 5 else null,
			range = true,
			busy = emptyList(),
			day = parseDate(planDay) ?: LocalDate.now(),
			/* a tap picks the start only, a drag the start and end (issue #52, answer 6) */
			allowPoint = true,
			initialPoint = a != null && b == null,
			onDismiss = { planGrid = false },
		) { from, to ->
			planGrid = false
			planTime = formatMin(from)
			planEnd = if (to > from) formatMin(to) else ""
		}
	}
	if (addCompany)
		QuickCompanyDialog(onDone = { addCompany = false; picked = it; clientId = null }, onDismiss = { addCompany = false })
	if (addClient)
		QuickClientDialog(companyId, onDone = { cl, co -> addClient = false; clientId = cl; picked = co }, onDismiss = { addClient = false })
}
