#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Writes docs/samples/activity-migration.json: the `before` tables of every case are written
# here by hand, the `after` tables are produced by scripts/activity_migration.py and were
# reviewed row by row against docs/ACTIVITY-MODEL.md section 6 when the file was made.
#
#   python3 -I scripts/make-migration-cases.py            # rewrites the JSON
#
# Uids are readable and unique across the cases ("c05-..."): scripts/legacy-fixtures.py
# merges the cases into one database.

import json
import os
import sys
from datetime import date, datetime
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from activity_migration import migrate, normalize  # noqa: E402

ROME = ZoneInfo("Europe/Rome")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "samples", "activity-migration.json")

ABOUT = (
	"Data migration DB 21 -> 22 (docs/ACTIVITY-MODEL.md section 6, 3.1, 5). Every implementation runs these cases: "
	"migrate(before, zone) must equal `after` once both are normalized. `before` holds the DB 21 tables `time_entry`, "
	"`segment`, `external_ref`, `todo`, `absence`, `work_schedule`, `lunch_break`, `day_off` (a missing table is empty; a missing column is null, "
	"except todo.kind = 'TASK', todo.done = 0, todo.refs = '', time_entry.unanswered_reminders = 0, time_entry.needs_review = 0, segment.uid = ''). "
	"Booleans are 0/1, times are epoch ms, days epoch days, minutes are minutes of the local day (work_schedule.day_of_week: 1 = Monday). "
	"`zone` is the zone for the rows created from a to-do or a legacy absence (the todo table has none). "
	"`after` holds `time_entry`, `segment`, `external_ref` (the only tables that change; `todo` and `absence` are gone, the others are untouched) in normal form: "
	"no `id`; segment and external_ref rows name their activity by `entry_uid` instead of `entry_id`; columns that are null are omitted; "
	"`plugin_fields` is the parsed JSON object; rows sorted by (entry_uid, start_utc_ms, uid, value) - compare as unordered sets. "
	"New time_entry columns always present: kind, priority (and status). Old segments get planned = 0. "
	"New uids: uuid5(6f1c2a52-6b1e-5a8e-9d7e-0a5c1e7d1000, '<to-do uid>/<suffix>') with suffixes abs/<yyyy-mm-dd>/<n> (n from 0 in the day), "
	"ref/<i> (i = index among the to-do's distinct non-blank refs lines), link/<entry uid> (the UUID reference of the other entries). "
	"Choices not in the spec are listed in the header of scripts/activity_migration.py (CHOICE)."
)

DAY0 = date(1970, 1, 1)


def ed(s):
	return (date.fromisoformat(s) - DAY0).days


def L(s, hhmm):
	d = date.fromisoformat(s)
	h, m = map(int, hhmm.split(":"))
	return int(datetime(d.year, d.month, d.day, h, m, tzinfo=ROME).timestamp() * 1000)


def sched(extra=()):
	rows = [{"day_of_week": d, "start_min": 540, "end_min": 1080} for d in range(1, 6)] + list(extra)
	return rows


def lunch():
	return [{"day_of_week": d, "start_min": 750, "end_min": 840} for d in range(1, 6)]


T0 = L("2026-11-02", "09:00")  # a Monday


class Case:
	def __init__(self, name, zone="Europe/Rome", **tables):
		self.name, self.zone, self.before = name, zone, {k: v for k, v in tables.items() if v}


def entry(i, uid, status="CLOSED", todo=None, **kw):
	r = {"id": i, "uid": uid, "status": status, "zone_id": "Europe/Rome", "created_utc_ms": T0 + i * 1000, "updated_utc_ms": T0 + 3600000 + i}
	if todo is not None:
		r["todo_id"] = todo
	r.update(kw)
	return r


def seg(i, entry_id, start, end, kind="WORK", uid=None):
	return {"id": i, "entry_id": entry_id, "kind": kind, "start_utc_ms": start, "end_utc_ms": end, "zone_id": "Europe/Rome",
		"source": "TRACKED", "uid": uid or f"seg-{i}"}


def todo(i, uid, title, **kw):
	r = {"id": i, "uid": uid, "title": title, "refs": "", "done": 0, "created_utc_ms": T0, "updated_utc_ms": T0 + 1000}
	r.update(kw)
	return r


cases = []
add = cases.append

# ---- entries (step 1) ------------------------------------------------------------------------
add(Case("entry ACTIVE with an open WORK segment becomes RUNNING",
	time_entry=[entry(1, "c01-e1", "ACTIVE", mode="WORK", activity="Aggiornamento server", company_id=1, client_id=2, notes="n", plugin_fields='{"k":"v"}')],
	segment=[seg(1, 1, L("2026-11-02", "09:00"), None)]))
add(Case("entry SUSPENDED becomes RUNNING, no mode",
	time_entry=[entry(1, "c02-e1", "SUSPENDED", activity="Pranzo di lavoro")],
	segment=[seg(1, 1, L("2026-11-02", "09:00"), L("2026-11-02", "10:00"))]))
add(Case("entry TO_COMPLETE becomes CLOSED at its last segment end",
	time_entry=[entry(1, "c03-e1", "TO_COMPLETE", activity="Da completare")],
	segment=[seg(1, 1, L("2026-11-02", "09:00"), L("2026-11-02", "10:00")), seg(2, 1, L("2026-11-02", "10:00"), L("2026-11-02", "11:30"), "TRAVEL")]))
add(Case("entry CLOSED without segments closes at its last update",
	time_entry=[entry(1, "c04-e1", "CLOSED", activity="Chiamata")]))

# ---- to-dos without entries (step 3, first bullet) --------------------------------------------
add(Case("todo without entries, open, with plan, due, key, refs, plugin fields",
	todo=[todo(1, "c05-t1", "Rinnovo certificati", company_id=2, client_id=3, external_key="INC0012345", notes="note",
		refs="INC0012345\nhttps://example.org/t/42", due_epoch_day=ed("2026-11-20"), due_time_min=1020, priority=2,
		plan_epoch_day=ed("2026-11-18"), plan_start_min=600, duration_min=60, plan_reminded_utc_ms=L("2026-11-18", "09:50"),
		plugin_fields='{"a":"1","b":"2"}', color=123, plan_end_epoch_day=None)]))
add(Case("todo without entries, done",
	todo=[todo(1, "c06-t1", "Verifica backup", done=1, done_utc_ms=T0 + 5000, priority=3, refs="  \n")]))
add(Case("todo without entries, ignored",
	todo=[todo(1, "c07-t1", "Verifica UPS", ignored_utc_ms=T0 + 7000, priority=1)]))

# ---- to-dos with entries ---------------------------------------------------------------------
add(Case("todo done with one entry: the entry absorbs it (title becomes the description)",
	time_entry=[entry(1, "c08-e1", "CLOSED", todo=1, company_id=4)],
	segment=[seg(1, 1, L("2026-11-03", "09:00"), L("2026-11-03", "12:00"))],
	todo=[todo(1, "c08-t1", "Sostituzione firewall", done=1, done_utc_ms=T0 + 9000, company_id=5, client_id=6, external_key="K-1",
		notes="dal todo", refs="JIRA-42", due_epoch_day=ed("2026-11-10"), priority=1, plan_epoch_day=ed("2026-11-03"), plan_start_min=540,
		duration_min=180, plan_reminded_utc_ms=T0, plugin_fields='{"x":"todo"}')]))
add(Case("todo done with one entry that has its own description, notes and company: the entry wins",
	time_entry=[entry(1, "c09-e1", "CLOSED", todo=1, activity="Cambio firewall", notes="mie note", company_id=4, client_id=7, plugin_fields='{"x":"entry","y":"e"}')],
	segment=[seg(1, 1, L("2026-11-03", "09:00"), L("2026-11-03", "12:00"))],
	todo=[todo(1, "c09-t1", "Sostituzione firewall", done=1, done_utc_ms=T0 + 9000, company_id=5, client_id=6, notes="dal todo",
		priority=4, plugin_fields='{"x":"todo","z":"t"}')]))

three_segs = [seg(1, 1, L("2026-11-05", "09:00"), L("2026-11-05", "10:00")), seg(2, 2, L("2026-11-04", "09:00"), L("2026-11-04", "10:00")),
	seg(3, 3, L("2026-11-06", "09:00"), L("2026-11-06", "10:00"))]
add(Case("todo done with three entries: the earliest by first segment start absorbs, the others get a UUID ref",
	time_entry=[entry(1, "c10-e1", todo=1, activity="uno"), entry(2, "c10-e2", todo=1, activity="due"), entry(3, "c10-e3", todo=1, activity="tre")],
	segment=three_segs,
	todo=[todo(1, "c10-t1", "Progetto", done=1, done_utc_ms=T0 + 9000, priority=2, refs="PRJ-7")]))
tie = [seg(1, 1, L("2026-11-04", "09:00"), L("2026-11-04", "10:00")), seg(2, 2, L("2026-11-04", "09:00"), L("2026-11-04", "10:00")),
	seg(3, 3, L("2026-11-04", "09:00"), L("2026-11-04", "10:00"))]
add(Case("todo done with three entries, tie on first start: created_utc_ms decides",
	time_entry=[entry(1, "c11-e1", todo=1, created_utc_ms=T0 + 300), entry(2, "c11-e2", todo=1, created_utc_ms=T0 + 100),
		entry(3, "c11-e3", todo=1, created_utc_ms=T0 + 200)],
	segment=tie, todo=[todo(1, "c11-t1", "Progetto", done=1, done_utc_ms=T0 + 9000)]))
add(Case("todo done with three entries, tie on start and created: uid decides",
	time_entry=[entry(1, "c12-eC", todo=1, created_utc_ms=T0), entry(2, "c12-eA", todo=1, created_utc_ms=T0),
		entry(3, "c12-eB", todo=1, created_utc_ms=T0)],
	segment=tie, todo=[todo(1, "c12-t1", "Progetto", done=1, done_utc_ms=T0 + 9000)]))
add(Case("todo open with a running entry (also the earliest): it absorbs the to-do and stays RUNNING; the closed one gets a ref",
	time_entry=[entry(1, "c13-e1", "ACTIVE", todo=1, mode="TRAVEL", activity=""), entry(2, "c13-e2", "CLOSED", todo=1, activity="prima")],
	segment=[seg(1, 1, L("2026-11-04", "09:00"), None, "TRAVEL"), seg(2, 2, L("2026-11-05", "09:00"), L("2026-11-05", "10:00"))],
	todo=[todo(1, "c13-t1", "Installazione", due_epoch_day=ed("2026-11-30"), priority=5, external_key="W-9")]))
add(Case("todo open, the running entry is not the earliest: the earliest (closed) still absorbs, the running one gets the ref",
	time_entry=[entry(1, "c14-e1", "CLOSED", todo=1, activity="vecchia"), entry(2, "c14-e2", "SUSPENDED", todo=1, activity="in corso")],
	segment=[seg(1, 1, L("2026-11-04", "09:00"), L("2026-11-04", "10:00")), seg(2, 2, L("2026-11-05", "09:00"), L("2026-11-05", "10:00"))],
	todo=[todo(1, "c14-t1", "Lavoro lungo", priority=3)]))
add(Case("todo done with a running entry: absorbed, stays RUNNING",
	time_entry=[entry(1, "c15-e1", "ACTIVE", todo=1, mode="WORK")],
	segment=[seg(1, 1, L("2026-11-05", "09:00"), None)],
	todo=[todo(1, "c15-t1", "Riaperta", done=1, done_utc_ms=T0 + 9000)]))
add(Case("todo open with all entries closed: it stays its own TODO, each entry gets a UUID ref",
	time_entry=[entry(1, "c16-e1", "CLOSED", todo=1, activity="a"), entry(2, "c16-e2", "TO_COMPLETE", todo=1, activity="b")],
	segment=[seg(1, 1, L("2026-11-04", "09:00"), L("2026-11-04", "10:00")), seg(2, 2, L("2026-11-05", "09:00"), L("2026-11-05", "10:00"))],
	external_ref=[{"id": 1, "entry_id": 1, "kind": "TICKET", "value": "INC-1", "uid": "ref-1"}],
	todo=[todo(1, "c16-t1", "Da finire", refs="INC-1\nINC-2", priority=2, plan_epoch_day=ed("2026-11-20"), due_epoch_day=ed("2026-11-25"),
		notes="resta", company_id=1)]))
add(Case("todo ignored with entries all closed: stays TODO activity, deleted",
	time_entry=[entry(1, "c17-e1", "CLOSED", todo=1)],
	segment=[seg(1, 1, L("2026-11-04", "09:00"), L("2026-11-04", "10:00"))],
	todo=[todo(1, "c17-t1", "Ignorata", ignored_utc_ms=T0 + 4000)]))
add(Case("entry with a todo_id that matches no to-do is a plain entry",
	time_entry=[entry(1, "c18-e1", "CLOSED", todo=99, activity="orfana")],
	segment=[seg(1, 1, L("2026-11-04", "09:00"), L("2026-11-04", "10:00"))]))

# ---- refs and plugin fields -----------------------------------------------------------------
add(Case("refs text becomes external_ref rows classified URL, UUID, TICKET, OTHER; blank lines and duplicates dropped; the entry's own ref kept once",
	time_entry=[entry(1, "c19-e1", "CLOSED", todo=1, activity="x")],
	segment=[seg(1, 1, L("2026-11-04", "09:00"), L("2026-11-04", "10:00"))],
	external_ref=[{"id": 1, "entry_id": 1, "kind": "TICKET", "value": "INC0012345", "uid": "c19-r1"}],
	todo=[todo(1, "c19-t1", "Rif", done=1, done_utc_ms=T0 + 9000,
		refs="  https://example.org/a?b=1 \n\nINC0012345\n{123e4567-e89b-12d3-a456-426614174000}\n#1234\nJIRA-42\nordine cliente\nINC0012345\nftp://host/x\nPRJ 7\nabc")]))
add(Case("plugin_fields: the entry's values win over the to-do's; null and invalid JSON count as empty",
	time_entry=[entry(1, "c20-e1", "CLOSED", todo=1, activity="x", plugin_fields='{"b":"entry","c":"entry"}'),
		entry(2, "c20-e2", "CLOSED", todo=2, activity="y", plugin_fields=None),
		entry(3, "c20-e3", "CLOSED", todo=3, activity="z", plugin_fields="not json"),
		entry(4, "c20-e4", "CLOSED", todo=4, activity="w", plugin_fields='{"only":"entry"}')],
	segment=[seg(i, i, L("2026-11-04", "09:00"), L("2026-11-04", "10:00")) for i in range(1, 5)],
	todo=[todo(1, "c20-t1", "A", done=1, done_utc_ms=T0, plugin_fields='{"a":"todo","b":"todo"}'),
		todo(2, "c20-t2", "B", done=1, done_utc_ms=T0, plugin_fields='{"a":"todo"}'),
		todo(3, "c20-t3", "C", done=1, done_utc_ms=T0, plugin_fields='{"a":"todo"}'),
		todo(4, "c20-t4", "D", done=1, done_utc_ms=T0, plugin_fields=None)]))
add(Case("entry without description gets the to-do's title; a blank one counts as none",
	time_entry=[entry(1, "c21-e1", "CLOSED", todo=1, activity=None), entry(2, "c21-e2", "CLOSED", todo=2, activity="   ")],
	segment=[seg(1, 1, L("2026-11-04", "09:00"), L("2026-11-04", "10:00")), seg(2, 2, L("2026-11-04", "09:00"), L("2026-11-04", "10:00"))],
	todo=[todo(1, "c21-t1", "Titolo uno", done=1, done_utc_ms=T0), todo(2, "c21-t2", "Titolo due", done=1, done_utc_ms=T0)]))
add(Case("priorities: null, 1..5, 7, 0, -1 (P1 -> 5 ... P5 -> 1, empty -> 0, above 5 -> 1, below 1 -> 0)",
	todo=[todo(i + 1, f"c22-t{i + 1}", f"prio {p}", priority=p) for i, p in enumerate([None, 1, 2, 3, 4, 5, 7, 0, -1])]))

# ---- absences --------------------------------------------------------------------------------
add(Case("absence to-do, one whole working day with a lunch break: two segments",
	todo=[todo(1, "c23-t1", "Assenza", kind="ABSENCE", plan_epoch_day=ed("2026-11-18"), done=1, done_utc_ms=T0 + 100, notes="dentista", company_id=2, refs="X\nY",
		priority=3, external_key="zz")],
	work_schedule=sched(), lunch_break=lunch()))
add(Case("absence over Thu-Tue across a weekend and a day off: only working days, two segments each",
	todo=[todo(1, "c24-t1", "Ferie", kind="ABSENCE", plan_epoch_day=ed("2026-11-19"), plan_end_epoch_day=ed("2026-11-24"), done=1)],
	work_schedule=sched(), lunch_break=lunch(), day_off=[{"date_epoch_day": ed("2026-11-23"), "note": "ponte", "uid": "c24-d1"}]))
add(Case("absence without lunch break on that weekday: one segment; days without schedule give none",
	todo=[todo(1, "c25-t1", "Assenza", kind="ABSENCE", plan_epoch_day=ed("2026-11-20"), plan_end_epoch_day=ed("2026-11-22"))],
	work_schedule=sched()))
add(Case("absence with start and duration (permesso): one segment, the end date is ignored",
	todo=[todo(1, "c26-t1", "Permesso", kind="ABSENCE", plan_epoch_day=ed("2026-11-18"), plan_start_min=600, duration_min=90, plan_end_epoch_day=ed("2026-11-19"))],
	work_schedule=sched(), lunch_break=lunch()))
add(Case("absence with a start and no duration: a zero-length segment",
	todo=[todo(1, "c27-t1", "Permesso", kind="ABSENCE", plan_epoch_day=ed("2026-11-18"), plan_start_min=600)],
	work_schedule=sched()))
dst_sun = [{"day_of_week": 7, "start_min": 60, "end_min": 300}]
add(Case("absence on the spring DST change (Europe/Rome 2026-03-29, 01:00-05:00 on a Sunday schedule is 3 hours)",
	todo=[todo(1, "c28-t1", "Assenza", kind="ABSENCE", plan_epoch_day=ed("2026-03-28"), plan_end_epoch_day=ed("2026-03-30")),
		todo(2, "c28-t2", "Permesso", kind="ABSENCE", plan_epoch_day=ed("2026-03-29"), plan_start_min=150, duration_min=60)],
	work_schedule=sched(dst_sun), lunch_break=lunch()))
add(Case("absence on the autumn DST change (Europe/Rome 2026-10-25, 01:00-05:00 on a Sunday schedule is 5 hours; 02:30 is the first one)",
	todo=[todo(1, "c29-t1", "Assenza", kind="ABSENCE", plan_epoch_day=ed("2026-10-24"), plan_end_epoch_day=ed("2026-10-26")),
		todo(2, "c29-t2", "Permesso", kind="ABSENCE", plan_epoch_day=ed("2026-10-25"), plan_start_min=150, duration_min=60)],
	work_schedule=sched(dst_sun), lunch_break=lunch()))
add(Case("absence kind from the title: the four labels, four languages, case and spaces ignored; anything else is ABSENCE",
	todo=[todo(i + 1, f"c30-t{i + 1}", t, kind="ABSENCE", plan_epoch_day=ed("2026-11-18")) for i, t in enumerate(
		["Ferie", "Krankheit", "Dentista", " permesso ", "ENFERMEDAD", "Sick leave", "vacaciones", "Abwesenheit", "Holiday", "Freistellung",
			"Malattia", "Urlaub", "Ausencia", "Assenza", "Leave", "Permiso", "Absence", "Sick", "", "Ferie estive"])],
	work_schedule=sched(), lunch_break=lunch()))
add(Case("absence to-do without a plan: the activity stays, with no segment; title blank means no description",
	todo=[todo(1, "c31-t1", "", kind="ABSENCE", done=1, done_utc_ms=T0 + 50)],
	work_schedule=sched()))
add(Case("legacy absence rows: FULL_DAY, LATE_ENTRY, EARLY_EXIT",
	absence=[{"id": 1, "date_epoch_day": ed("2026-11-18"), "kind": "FULL_DAY", "uid": "c32-a1"},
		{"id": 2, "date_epoch_day": ed("2026-11-19"), "kind": "LATE_ENTRY", "time_min": 600, "uid": "c32-a2"},
		{"id": 3, "date_epoch_day": ed("2026-11-20"), "kind": "EARLY_EXIT", "time_min": 960, "uid": "c32-a3"}],
	work_schedule=sched(), lunch_break=lunch()))
add(Case("legacy absence rows that missed nothing or are unknown are dropped; one on a day off keeps its activity without segment",
	absence=[{"id": 1, "date_epoch_day": ed("2026-11-18"), "kind": "LATE_ENTRY", "time_min": 500, "uid": "c33-a1"},
		{"id": 2, "date_epoch_day": ed("2026-11-18"), "kind": "EARLY_EXIT", "time_min": 1100, "uid": "c33-a2"},
		{"id": 3, "date_epoch_day": ed("2026-11-18"), "kind": "LATE_ENTRY", "uid": "c33-a3"},
		{"id": 4, "date_epoch_day": ed("2026-11-18"), "kind": "SOMETHING", "uid": "c33-a4"},
		{"id": 5, "date_epoch_day": ed("2026-11-23"), "kind": "FULL_DAY", "uid": "c33-a5"}],
	work_schedule=sched(), lunch_break=lunch(), day_off=[{"date_epoch_day": ed("2026-11-23"), "uid": "c33-d1"}]))
add(Case("everything together: ids of new rows follow the old ones",
	time_entry=[entry(7, "c34-e1", "ACTIVE", todo=3, mode="WORK")],
	segment=[seg(9, 7, L("2026-11-05", "09:00"), None)],
	todo=[todo(3, "c34-t3", "Aperta", priority=1), todo(5, "c34-t5", "Ferie", kind="ABSENCE", plan_epoch_day=ed("2026-11-18")),
		todo(8, "c34-t8", "Libera", done=1, done_utc_ms=T0, refs="INC-9")],
	absence=[{"id": 2, "date_epoch_day": ed("2026-11-19"), "kind": "FULL_DAY", "uid": "c34-a2"}],
	work_schedule=sched(), lunch_break=lunch()))
add(Case("an ignored absence to-do goes to the bin",
	todo=[todo(1, "c35-t1", "Ferie", kind="ABSENCE", plan_epoch_day=ed("2026-11-18"), ignored_utc_ms=T0 + 3000)],
	work_schedule=sched(), lunch_break=lunch()))
add(Case("external_key unique with the company: the earliest created activity keeps it, the others lose it",
	time_entry=[entry(1, "c36-e1", "CLOSED", todo=2, company_id=1)],
	segment=[seg(1, 1, L("2026-11-03", "09:00"), L("2026-11-03", "10:00"))],
	todo=[todo(1, "c36-t1", "Senza azienda", external_key="K-7", created_utc_ms=T0 + 100),
		todo(2, "c36-t2", "Assorbita", external_key="K-8", company_id=2, done=1, done_utc_ms=T0 + 9000, created_utc_ms=T0 + 200),
		todo(3, "c36-t3", "Stessa chiave", external_key="K-7", created_utc_ms=T0 + 50),
		todo(4, "c36-t4", "Stessa chiave, dopo", external_key="K-8", company_id=1, created_utc_ms=T0 + 5)]))


def build():
	doc = {"about": ABOUT, "zone": "Europe/Rome", "cases": []}
	for c in cases:
		after = normalize(migrate(c.before, c.zone))
		doc["cases"].append({"name": c.name, "zone": c.zone, "before": c.before, "after": after})
	return doc


def dump(doc):
	# one row per line, so the file reviews and diffs well
	def rows(rs, ind):
		return "[" + ("\n" + ind + "\t" + (",\n" + ind + "\t").join(json.dumps(r, ensure_ascii=False, sort_keys=True) for r in rs) + "\n" + ind if rs else "") + "]"
	def tables(t, ind):
		return "{" + ("\n" + ind + "\t" + (",\n" + ind + "\t").join(json.dumps(k) + ": " + rows(v, ind + "\t") for k, v in t.items()) + "\n" + ind if t else "") + "}"
	out = ["{", "\t\"about\": " + json.dumps(doc["about"], ensure_ascii=False) + ",", "\t\"zone\": " + json.dumps(doc["zone"]) + ",", "\t\"cases\": ["]
	items = []
	for c in doc["cases"]:
		items.append("\t\t{\n\t\t\t\"name\": " + json.dumps(c["name"], ensure_ascii=False) + ",\n\t\t\t\"zone\": " + json.dumps(c["zone"]) +
			",\n\t\t\t\"before\": " + tables(c["before"], "\t\t\t") + ",\n\t\t\t\"after\": " + tables(c["after"], "\t\t\t") + "\n\t\t}")
	out.append(",\n".join(items))
	out += ["\t]", "}", ""]
	return "\n".join(out)


if __name__ == "__main__":
	text = dump(build())
	json.loads(text)
	open(OUT, "w").write(text)
	print(f"{len(cases)} cases -> {os.path.normpath(OUT)}")
