#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Screenshots of the manual (owner, 2026-10-19), from the debug app on a
# connected phone, with invented sample data and the app language of each
# manual:
#
#   python3 -I scripts/screenshots.py [it|en ...]
#
# The debug app's database is saved first and put back at the end. Needs
# adb (ANDROID_HOME or ~/Android/Sdk) and an unlocked phone.

import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SDK = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk"))
ADB = [os.path.join(SDK, "platform-tools", "adb")]
PKG = "it.infraconsulting.activitytrack.debug"
MAIN = PKG + "/it.infraconsulting.activitytrack.MainActivity"
DBS = ["activitytrack.db", "activitytrack.db-wal", "activitytrack.db-shm"]

LABELS = {
	"it": dict(new="Nuova attività", end="Fine \\(facoltativa\\)", agenda="Agenda", day="Giorno", report="Report", more="Altro",
		todos="Da fare", md="Anagrafiche", clients="Clienti", manual="Manuale", settings="Impostazioni", running="Farmacia Centrale",
		contacts="Contatti", calls="Chiamate"),
	"en": dict(new="New activity", end="End \\(optional\\)", agenda="Agenda", day="Day", report="Report", more="More",
		todos="To-dos", md="Master data", clients="Clients", manual="Manual", settings="Settings", running="Farmacia Centrale",
		contacts="Contacts", calls="Calls"),
}


def adb(*a, **kw):
	return subprocess.run(ADB + list(a), capture_output=True, timeout=60, **kw)


def sh(*a):
	return adb("shell", *a).stdout.decode()


def nodes():
	x = adb("exec-out", "uiautomator", "dump", "/dev/tty").stdout.decode(errors="replace")
	x = x[x.index("<"):x.rindex(">") + 1]
	for n in ET.fromstring(x).iter("node"):
		b = [int(v) for v in re.findall(r"\d+", n.get("bounds", ""))]
		if len(b) == 4:
			yield n, b


def find(pattern, index=0):
	pat = re.compile(pattern)
	hits = [b for n, b in nodes() if pat.search(n.get("text") or n.get("content-desc") or "")]
	if len(hits) <= index:
		raise SystemExit(f"not on screen: {pattern}")
	return hits[index]


def tap(pattern, index=0, wait=1.8):
	x1, y1, x2, y2 = find(pattern, index)
	sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
	time.sleep(wait)


def back(n=1):
	for _ in range(n):
		sh("input", "keyevent", "4")
		time.sleep(0.8)


def shot(lang, name):
	out = os.path.join(ROOT, "docs/manual", lang, "img", name + ".png")
	os.makedirs(os.path.dirname(out), exist_ok=True)
	with open(out, "wb") as f:
		f.write(adb("exec-out", "screencap", "-p").stdout)
	print(out)


def restart():
	sh("am", "force-stop", PKG)
	sh("am", "start", "-n", MAIN)
	time.sleep(4)


def put_db(local):
	sh("am", "force-stop", PKG)
	sh("run-as", PKG, "rm", "-f", *[f"databases/{d}" for d in DBS])
	adb("push", local, "/data/local/tmp/activitytrack.db")
	sh(f"run-as {PKG} cp /data/local/tmp/activitytrack.db databases/activitytrack.db")
	sh("rm", "/data/local/tmp/activitytrack.db")


def capture(lang):
	t = LABELS[lang]
	sh("cmd", "locale", "set-app-locales", PKG, "--locales", {"it": "it-IT", "en": "en-US"}[lang])
	restart()
	shot(lang, "home")
	tap(t["new"])
	shot(lang, "start")
	tap(t["end"])
	# a range on the grid: from the third cell of the first row to the eighth of the second
	rows = [b for n, b in nodes() if re.fullmatch(r"\d\d:00", n.get("text") or "")]
	(lx1, ly1, lx2, ly2), (_, my1, _, my2) = rows[0], rows[1]
	width = int(sh("wm", "size").split()[-1].split("x")[0])
	cell = (width - lx2 - 40) / 12
	x = lambda c: int(lx2 + 20 + cell * (c + 0.5))
	sh("input", "swipe", str(x(3)), str((ly1 + ly2) // 2), str(x(7)), str((my1 + my2) // 2), "1500")
	time.sleep(1)
	shot(lang, "time-grid")
	back(2)
	time.sleep(1.5)	# the Home must be settled, or the tap gets lost in the transition
	tap(f"^{t['running']}$", wait=3)
	shot(lang, "entry")
	back()
	tap(f"^{t['agenda']}$")
	tap(f"^{t['day']}$")
	shot(lang, "agenda-day")
	tap(f"^{t['report']}$")
	shot(lang, "report")
	tap(f"^{t['more']}$")
	tap(f"^{t['todos']}$")
	shot(lang, "todos")
	back()
	tap(f"^{t['md']}$")
	tap(f"^{t['clients']}$")
	tap(f"^{t['manual']}$")
	shot(lang, "order")
	# the client's calls at the bottom of its screen, then the contacts (issue #28)
	tap(f"^{t['running']}$")
	for _ in range(3):
		sh("input", "swipe", "600", "2200", "600", "700", "400")
		time.sleep(0.8)
	shot(lang, "client-calls")
	back(2)
	tap(f"^{t['contacts']}$")
	shot(lang, "contacts")
	back(2)
	tap(f"^{t['settings']}$")
	shot(lang, "settings")
	back()


def main():
	langs = sys.argv[1:] or ["it", "en"]
	with tempfile.TemporaryDirectory() as tmp:
		saved = os.path.join(tmp, "saved")
		os.makedirs(saved)
		sh("am", "force-stop", PKG)
		for d in DBS:
			data = adb("exec-out", "run-as", PKG, "cat", f"databases/{d}").stdout
			if data:
				open(os.path.join(saved, d), "wb").write(data)
		sample = os.path.join(tmp, "sample.db")
		subprocess.run([sys.executable, "-I", os.path.join(ROOT, "scripts/sample-db.py"), sample], check=True)
		try:
			for lang in langs:
				put_db(sample)
				capture(lang)
		finally:
			sh("am", "force-stop", PKG)
			sh("run-as", PKG, "rm", "-f", *[f"databases/{d}" for d in DBS])
			for d in DBS:
				p = os.path.join(saved, d)
				if os.path.exists(p):
					adb("push", p, f"/data/local/tmp/{d}")
					sh(f"run-as {PKG} cp /data/local/tmp/{d} databases/{d}")
					sh("rm", f"/data/local/tmp/{d}")
			sh("cmd", "locale", "set-app-locales", PKG, "--locales", "")
			print("debug data restored")


if __name__ == "__main__":
	main()
