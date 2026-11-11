#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Old-version databases and backups for the device / restore tests of the 1.0 migration
# (docs/ACTIVITY-MODEL.md 6.1): the cases of docs/samples/activity-migration.json loaded
# into a real Room database of schema 21 (and 20), plus a backup zip in the format of
# DatabaseBackup.kt (manifest.json + activitytrack.db).
#
#   python3 -I scripts/legacy-fixtures.py [out-dir]       # default /tmp/at-wt/fixtures
#
# writes  activitytrack-v21.db  activitytrack-v21-backup.zip  activitytrack-v20.db
#         activitytrack-v20-backup.zip  (and expected-v21.json: the reference migration of the v21 data)
#
# Only the cases that share the standard working week (Mon-Fri 09:00-18:00, lunch 12:30-14:00)
# are loaded: the two DST cases carry a Sunday schedule of their own and are left out.
# Ids are offset by 1000 * case number and segment / ref uids prefixed with the case tag, so
# the cases do not collide. Companies 1-7 and clients 1-7 are created for the references.
# v20: the same data, except the to-dos of kind ABSENCE (the column does not exist yet); the legacy
# `absence` rows stay in their table. The 20 -> 21 migration turns each into an ABSENCE to-do with a
# RANDOM uid, so the v20 result of the 22 migration is checked by shape, not by uid.
# Not reproduced: the sync tables' triggers (created by the app on first open, not by Room).

import glob
import json
import os
import sqlite3
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
sys.path.insert(0, HERE)
from activity_migration import migrate, normalize  # noqa: E402

CASES = os.path.join(ROOT, "docs", "samples", "activity-migration.json")
DB_FILE_NAME = "activitytrack.db"  # ActivityTrackDatabase.FILE_NAME
DEFAULTS = {"unanswered_reminders": 0, "needs_review": 0, "done": 0, "refs": "", "uid": ""}
STANDARD_WEEK = ({(d, 540, 1080) for d in range(1, 6)}, {(d, 750, 840) for d in range(1, 6)})
OFFSET = 1000


def schema(version):
	path = glob.glob(os.path.join(ROOT, f"core/data/schemas/*/{version}.json"))[0]
	return json.load(open(path))["database"]


def standard(before):
	ws = {(r["day_of_week"], r["start_min"], r["end_min"]) for r in before.get("work_schedule", [])}
	lb = {(r["day_of_week"], r["start_min"], r["end_min"]) for r in before.get("lunch_break", [])}
	return (ws or STANDARD_WEEK[0]) == STANDARD_WEEK[0] and (lb or STANDARD_WEEK[1]) == STANDARD_WEEK[1]


def selected(v21):
	out = []
	for n, c in enumerate(json.load(open(CASES))["cases"], 1):
		b = c["before"]
		if not standard(b):
			continue
		if not v21 and any(t.get("kind") == "ABSENCE" for t in b.get("todo", [])):
			continue
		out.append((n, c))
	return out


def build(path, version):
	s = schema(version)
	if os.path.exists(path):
		os.remove(path)
	db = sqlite3.connect(path)
	cols = {e["tableName"]: [f["columnName"] for f in e["fields"]] for e in s["entities"]}
	indices = []
	for e in s["entities"]:
		db.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
		indices += [i["createSql"].replace("${TABLE_NAME}", e["tableName"]) for i in e.get("indices", [])]
	for q in s["setupQueries"]:
		db.execute(q)
	db.execute(f"PRAGMA user_version = {s['version']}")

	def insert(table, row):
		row = {**{k: v for k, v in DEFAULTS.items() if k in cols[table]}, **row}
		row = {k: v for k, v in row.items() if k in cols[table] and v is not None or (k in cols[table] and k in DEFAULTS)}
		db.execute(f"INSERT INTO `{table}` ({','.join(row)}) VALUES ({','.join('?' * len(row))})", list(row.values()))

	for i in range(1, 8):
		insert("company", {"id": i, "name": f"Azienda {i}", "archived": 0, "uid": f"company-{i}"})
		insert("client", {"id": i, "company_id": 1, "name": f"Cliente {i}", "archived": 0, "uid": f"client-{i}"})
	for d in range(1, 6):
		insert("work_schedule", {"id": d, "day_of_week": d, "start_min": 540, "end_min": 1080, "uid": f"ws-{d}"})
		insert("lunch_break", {"id": d, "day_of_week": d, "start_min": 750, "end_min": 840, "uid": f"lb-{d}"})
	days_off = {}
	for n, c in selected(version == 21):
		b, off, tag = c["before"], OFFSET * n, f"c{n:02d}"
		for r in b.get("day_off", []):
			days_off[r["date_epoch_day"]] = r
		for t in b.get("todo", []):
			t = {**t, "id": t["id"] + off}
			insert("todo", {k: v for k, v in t.items() if version == 21 or k not in ("kind", "color", "plan_end_epoch_day")})
		for e in b.get("time_entry", []):
			insert("time_entry", {**e, "id": e["id"] + off, **({"todo_id": e["todo_id"] + off} if e.get("todo_id") is not None else {})})
		for sg in b.get("segment", []):
			insert("segment", {**sg, "id": sg["id"] + off, "entry_id": sg["entry_id"] + off, "uid": f"{tag}-{sg['uid']}"})
		for r in b.get("external_ref", []):
			insert("external_ref", {**r, "id": r["id"] + off, "entry_id": r["entry_id"] + off, "uid": f"{tag}-{r['uid']}"})
		for a in b.get("absence", []):
			insert("absence", {**a, "id": a["id"] + off})
	for r in days_off.values():
		insert("day_off", r)
	for q in indices:
		db.execute(q)
	db.commit()
	db.close()


def backup(db_path, zip_path, version):
	manifest = {"app": "ActivityTrack", "appVersion": "0.9.7-beta1", "dbVersion": version,
		"createdUtc": "2026-11-11T09:00:00Z", "attachments": 0}
	with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
		z.write(db_path, DB_FILE_NAME)
		z.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")


def expected_v21(path):
	"""The reference migration of the whole v21 database (standard week), normalized, for spot checks."""
	db = sqlite3.connect(path)
	db.row_factory = sqlite3.Row
	rows = {t: [dict(r) for r in db.execute(f"SELECT * FROM `{t}`")] for t in
		("time_entry", "segment", "external_ref", "todo", "absence", "work_schedule", "lunch_break", "day_off")}
	db.close()
	return normalize(migrate(rows, "Europe/Rome"))


def main(out):
	os.makedirs(out, exist_ok=True)
	for v in (21, 20):
		db = os.path.join(out, f"activitytrack-v{v}.db")
		build(db, v)
		backup(db, os.path.join(out, f"activitytrack-v{v}-backup.zip"), v)
		print(db)
	with open(os.path.join(out, "expected-v21.json"), "w") as f:
		json.dump(expected_v21(os.path.join(out, "activitytrack-v21.db")), f, indent="\t", sort_keys=True)


if __name__ == "__main__":
	main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/at-wt/fixtures")
