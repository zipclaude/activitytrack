#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Invented sample data for the manual's screenshots (owner, 2026-10-19: no
# real data in the documentation). Builds a Room database of the current
# schema version, around "now" in Europe/Rome:
#
#   python3 -I scripts/sample-db.py <out.db>
#
# then copy it into the debug app's databases/ (see scripts/screenshots.sh).

import glob
import json
import os
import re
import sqlite3
import sys
import uuid
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

ROME = ZoneInfo("Europe/Rome")
ROOT = os.path.join(os.path.dirname(__file__), "..")


def schema():
	# the version the app declares, not the newest file (another branch may have left one)
	src = open(os.path.join(ROOT, "core/data/src/main/kotlin/it/infraconsulting/activitytrack/data/db/ActivityTrackDatabase.kt")).read()
	version = re.search(r"const val VERSION = (\d+)", src).group(1)
	files = glob.glob(os.path.join(ROOT, f"core/data/schemas/*/{version}.json"))
	return json.load(open(files[0]))["database"]


def ms(d: date, hh: int, mm: int) -> int:
	return int(datetime.combine(d, time(hh, mm), ROME).timestamp() * 1000)


def main(out):
	s = schema()
	if os.path.exists(out):
		os.remove(out)
	db = sqlite3.connect(out)
	for e in s["entities"]:
		db.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
		for i in e.get("indices", []):
			db.execute(i["createSql"].replace("${TABLE_NAME}", e["tableName"]))
	for v in s.get("views", []):
		db.execute(v["createSql"].replace("${VIEW_NAME}", v["viewName"]))
	for q in s["setupQueries"]:
		db.execute(q)
	db.execute(f"PRAGMA user_version = {s['version']}")

	now = datetime.now(ROME)
	today = now.date()
	nowms = int(now.timestamp() * 1000)

	# master data: invented companies, clients, roles, presets
	companies = [(1, "Studio Rossi Srl", 0x3D7BEA), (2, "Alfa Sistemi", 0x2E9D4E), (3, "Comune di Valdora", 0xE8862A)]
	db.executemany("INSERT INTO company (id, name, archived, color) VALUES (?, ?, 0, ?)", companies)
	clients = [
		(1, 1, "Farmacia Centrale"), (2, 1, "Hotel Belvedere"), (3, 2, "Officine Bassi"),
		(4, 2, "Cantina Monte Verde"), (5, 3, "Ufficio Tributi"), (6, 3, "Biblioteca civica"),
	]
	db.executemany("INSERT INTO client (id, company_id, name, archived) VALUES (?, ?, ?, 0)", clients)
	db.executemany("INSERT INTO role (id, name, archived) VALUES (?, ?, 0)", [(1, "Sistemista"), (2, "Consulente"), (3, "Formatore")])
	db.executemany(
		"INSERT INTO activity_preset (id, text, default_role_id, company_id) VALUES (?, ?, ?, ?)",
		[(1, "Aggiornamento server", 1, None), (2, "Verifica backup", 1, None), (3, "Formazione utenti", 3, 3)],
	)
	db.executemany(
		"INSERT INTO client_site (client_id, label, lat, lon, radius_m, address) VALUES (?, ?, ?, ?, 100, ?)",
		[(1, "Sede", 46.0703, 11.1210, "Via Roma 12"), (3, "Stabilimento", 46.0410, 11.1500, "Zona industriale 4")],
	)
	db.execute("INSERT INTO place (kind, label, lat, lon, radius_m) VALUES ('HOME', 'Casa', 46.0600, 11.1300, 100)")
	for dow in range(1, 6):
		db.execute("INSERT INTO work_schedule (day_of_week, start_min, end_min) VALUES (?, 540, 1080)", (dow,))
		db.execute("INSERT INTO lunch_break (day_of_week, start_min, end_min) VALUES (?, 750, 840)", (dow,))

	entry_id = [0]

	def entry(day, client, company, activity, role, status, mode, segs, tags=(), notes=None, todo=None):
		entry_id[0] += 1
		eid = entry_id[0]
		created = min(ms(day, *s[1]) for s in segs)
		db.execute(
			"INSERT INTO time_entry (id, uid, todo_id, company_id, client_id, role_id, activity, status, mode, location_kind, notes, "
			"zone_id, last_confirmed_utc_ms, unanswered_reminders, created_utc_ms, updated_utc_ms) "
			"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLIENT', ?, 'Europe/Rome', ?, 0, ?, ?)",
			# only the running one is updated now: the Home shows it in the big card
			(eid, uuid.uuid4().hex, todo, company, client, role, activity, status, mode, notes, nowms, created,
				nowms if status == "ACTIVE" else created),
		)
		for kind, start, end, km in segs:
			db.execute(
				"INSERT INTO segment (entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source, km_estimated) "
				"VALUES (?, ?, ?, ?, 'Europe/Rome', 'TRACKED', ?)",
				(eid, kind, ms(day, *start), None if end is None else ms(day, *end), km),
			)
		for t in tags:
			db.execute("INSERT INTO entry_tag (entry_id, tag) VALUES (?, ?)", (eid, t))
		return eid

	# past working days: closed activities with travel, work and pause
	day = today
	back = 0
	while back < 6:
		day -= timedelta(days=1)
		if day.weekday() >= 5:
			continue
		back += 1
		c = clients[back % len(clients)]
		entry(day, c[0], c[1], ["Aggiornamento server", "Verifica backup", "Riunione di avanzamento", "Formazione utenti"][back % 4],
			1 + back % 3, "CLOSED", None,
			[("TRAVEL", (8, 30), (9, 0), 18.4), ("WORK", (9, 0), (12, 45), None), ("PAUSE", (12, 45), (13, 30), None),
				("WORK", (13, 30), (16, 15), None), ("TRAVEL", (16, 15), (16, 45), 18.4)],
			tags=("remoto",) if back % 2 else ("urgente",))
		d2 = clients[(back + 2) % len(clients)]
		entry(day, d2[0], d2[1], "Assistenza telefonica", 2, "CLOSED", None, [("WORK", (17, 0), (17, 40), None)])

	# today: a closed morning call, a suspended job, a running activity
	# relative to now (5-minute steps), all before the running one, whatever the time of the shots
	start = max(now - timedelta(minutes=95), datetime.combine(today, time(2, 0), ROME))
	start = start.replace(minute=start.minute - start.minute % 5, second=0, microsecond=0)
	hm = lambda t: (t.hour, t.minute)
	entry(today, 4, 2, "Configurazione rete", 1, "CLOSED", None,
		[("WORK", hm(start - timedelta(minutes=110)), hm(start - timedelta(minutes=65)), None)], tags=("remoto",))
	entry(today, 6, 3, "Installazione stampanti", 1, "SUSPENDED", None,
		[("WORK", hm(start - timedelta(minutes=55)), hm(start - timedelta(minutes=10)), None)])
	travel_end = start + timedelta(minutes=25)
	# a call of another client paused it for a few minutes (issue #28)
	call_start = travel_end + timedelta(minutes=30)
	call_end = call_start + timedelta(minutes=6)
	running = entry(today, 1, 1, "Aggiornamento server", 1, "ACTIVE", "WORK",
		[("TRAVEL", hm(start), hm(travel_end), 12.7), ("WORK", hm(travel_end), hm(call_start), None),
			("PAUSE", hm(call_start), hm(call_end), None), ("WORK", hm(call_end), None, None)],
		tags=("manutenzione",))
	db.execute("UPDATE segment SET note = 'Telefonata Hotel Belvedere' WHERE entry_id = ? AND kind = 'PAUSE'", (running,))
	db.execute("UPDATE time_entry SET last_confirmed_utc_ms = ? WHERE id = ?", (ms(today, travel_end.hour, travel_end.minute), running))
	db.execute("INSERT INTO external_ref (entry_id, kind, value) VALUES (?, 'TICKET', 'INC-2041')", (running,))
	db.execute("INSERT INTO expense (entry_id, amount_cents, description, created_utc_ms) VALUES (?, 450, 'Parcheggio', ?)", (running, nowms))

	# to-dos: planned soon, due tomorrow, overdue, ignored, open
	def todo(tid, company, client, title, plan_day=None, plan_min=None, due_day=None, priority=None, ignored=False, dur=None):
		db.execute(
			"INSERT INTO todo (id, uid, company_id, client_id, title, refs, due_epoch_day, priority, done, created_utc_ms, updated_utc_ms, "
			"plan_epoch_day, plan_start_min, duration_min, ignored_utc_ms) VALUES (?, ?, ?, ?, ?, '', ?, ?, 0, ?, ?, ?, ?, ?, ?)",
			(tid, uuid.uuid4().hex, company, client, title, due_day.toordinal() - date(1970, 1, 1).toordinal() if due_day else None,
				priority, nowms, nowms, plan_day.toordinal() - date(1970, 1, 1).toordinal() if plan_day else None, plan_min, dur,
				nowms if ignored else None),
		)
	soon = now + timedelta(hours=2)
	todo(1, 2, 3, "Rinnovo certificati", today if soon.date() == today else soon.date(), soon.hour * 60 // 30 * 30 % 1440, priority=1, dur=60)
	todo(2, 1, 2, "Sostituzione firewall", due_day=today + timedelta(days=1), priority=2)
	todo(3, 3, 5, "Report trimestrale", due_day=today - timedelta(days=1), priority=1)
	todo(4, 2, 4, "Inventario hardware", today + timedelta(days=3), 600, dur=120)
	todo(5, 1, 1, "Verifica UPS", ignored=True)
	# contacts of the clients and the calls recognised with them (issue #28)
	contacts = [(1, 1, "Marco Bianchi", ["+393331234567"]), (2, 2, "Laura Conti", ["0461234567", "+393477654321"]),
		(3, 1, "Ufficio acquisti", ["0461987654"]), (4, 5, "Giulia Ferri", ["+393409876543"])]
	for cid, client, name, numbers in contacts:
		db.execute("INSERT INTO contact (id, client_id, name) VALUES (?, ?, ?)", (cid, client, name))
		db.executemany("INSERT INTO contact_phone (contact_id, number) VALUES (?, ?)", [(cid, n) for n in numbers])
	calls = [(1, 1, "INCOMING", 1, (10, 12), 254), (3, 1, "OUTGOING", 2, (15, 40), 95), (1, 1, "OUTGOING", 4, (11, 5), 612),
		(2, 2, "INCOMING", 0, None, 360)]
	for contact, client, direction, ago, at, seconds in calls:
		t = call_start if at is None else datetime.combine(today - timedelta(days=ago), time(*at), ROME)
		begin = int(t.timestamp() * 1000)
		db.execute(
			"INSERT INTO call_log (contact_id, client_id, direction, start_utc_ms, end_utc_ms) VALUES (?, ?, ?, ?, ?)",
			(contact, client, direction, begin, begin + seconds * 1000),
		)
	db.commit()
	db.close()
	print(out)


if __name__ == "__main__":
	main(sys.argv[1])
