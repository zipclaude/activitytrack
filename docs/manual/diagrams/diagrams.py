#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Graphviz sources of the manual's diagrams, one definition for every
# language: python3 diagrams.py <lang> <outdir> writes <name>.dot files.
# The states and transitions follow EntryStateMachine and the to-do model.

import os
import sys

L = {
	"it": {
		"active": "In corso", "work": "Lavoro", "travel": "Viaggio", "pause": "Pausa",
		"suspended": "Sospesa", "to_complete": "Da completare", "closed": "Chiusa",
		"start": "Avvio", "cmd_work": "Lavoro / Riprendi", "cmd_travel": "Viaggio", "cmd_pause": "Pausa",
		"cmd_suspend": "Sospendi", "cmd_stop": "Stop", "cmd_complete": "Completa", "cmd_reopen": "Riapri",
		"auto_close": "Stop con cliente e descrizione\n(non da un Da fare)",
		"t_open": "Aperta", "t_planned": "Pianificata", "t_done": "Fatta", "t_ignored": "Ignorata",
		"plan": "data e ora", "unplan": "togli pianificazione", "done": "Segna come fatta", "undo": "Segna come da fare",
		"ignore": "Ignora", "restore": "Ripristina", "postpone": "Posticipa", "overdue": "in ritardo: blocco o scadenza passati",
		"company": "Azienda", "client": "Cliente", "site": "Sede", "entry": "Attività", "segment": "Tratto",
		"expense": "Spesa", "attachment": "Allegato", "ref": "Riferimento esterno", "tag": "Tag", "role": "Ruolo",
		"todo": "Da fare", "preset": "Attività predefinita", "sample": "Punto di posizione",
		"place": "Luogo (casa, altro)", "schedule": "Orario di lavoro\nPausa pranzo\nGiorni liberi", "absence": "Assenza",
		"stop": "Stop", "q_work": "In lavoro?", "now": "Ferma ora", "return": "Avvia rientro",
		"short": "Meno di un minuto?", "discard": "Scarta", "keep": "Tieni e ferma",
		"complete_fields": "Cliente e descrizione\ncompilati?", "from_todo": "Da un Da fare?",
		"yes": "sì", "no": "no", "fill": "Completa i dettagli", "todo_done": "La voce Da fare\nè conclusa?",
		"end_time": "Fine (orario modificabili)",
	},
	"en": {
		"active": "Running", "work": "Work", "travel": "Travel", "pause": "Pause",
		"suspended": "Suspended", "to_complete": "To complete", "closed": "Closed",
		"start": "Start", "cmd_work": "Work / Resume", "cmd_travel": "Travel", "cmd_pause": "Pause",
		"cmd_suspend": "Suspend", "cmd_stop": "Stop", "cmd_complete": "Complete", "cmd_reopen": "Reopen",
		"auto_close": "Stop with client and activity\n(not from a to-do)",
		"t_open": "Open", "t_planned": "Planned", "t_done": "Done", "t_ignored": "Ignored",
		"plan": "date and time", "unplan": "remove the plan", "done": "Mark as done", "undo": "Mark as to do",
		"ignore": "Ignore", "restore": "Restore", "postpone": "Postpone", "overdue": "overdue: block or due date passed",
		"company": "Company", "client": "Client", "site": "Site", "entry": "Activity", "segment": "Segment",
		"expense": "Expense", "attachment": "Attachment", "ref": "External reference", "tag": "Tag", "role": "Role",
		"todo": "To-do", "preset": "Preset activity", "sample": "Location sample",
		"place": "Place (home, other)", "schedule": "Working hours\nLunch break\nDays off", "absence": "Absence",
		"stop": "Stop", "q_work": "Working?", "now": "Stop now", "return": "Start return travel",
		"short": "Under a minute?", "discard": "Discard", "keep": "Keep and stop",
		"complete_fields": "Client and activity\nfilled in?", "from_todo": "From a to-do?",
		"yes": "yes", "no": "no", "fill": "Fill in the details", "todo_done": "Is the to-do\ndone?",
		"end_time": "End (editable times)",
	},
}

HEAD = 'digraph G {\n\tgraph [rankdir=LR, fontname="Helvetica", fontsize=11, bgcolor="white", pad=0.2];\n' \
	'\tnode [fontname="Helvetica", fontsize=11, shape=box, style="rounded,filled", fillcolor="#eef2fb", color="#5b6bd6"];\n' \
	'\tedge [fontname="Helvetica", fontsize=9, color="#555555"];\n'


def q(s):
	return '"' + s.replace('"', '\\"').replace("\n", "\\n") + '"'


def entry_states(t):
	return HEAD + f"""	start [shape=circle, label="", width=0.2, fillcolor="#333333"];
	active [label={q(t["active"] + "\n(" + t["work"] + " / " + t["travel"] + " / " + t["pause"] + ")")}, fillcolor="#d7efd9"];
	susp [label={q(t["suspended"])}, fillcolor="#fde3cc"];
	todo [label={q(t["to_complete"])}, fillcolor="#f8d4d4"];
	closed [label={q(t["closed"])}, fillcolor="#e4e6ea"];
	start -> active [label={q(t["start"])}];
	active -> susp [label={q(t["cmd_suspend"])}];
	susp -> active [label={q(t["cmd_work"] + " / " + t["cmd_travel"])}];
	active -> todo [label={q(t["cmd_stop"])}];
	susp -> todo [label={q(t["cmd_stop"])}];
	active -> closed [label={q(t["auto_close"])}, style=dashed];
	todo -> closed [label={q(t["cmd_complete"])}];
	todo -> susp [label={q(t["cmd_reopen"])}];
	closed -> susp [label={q(t["cmd_reopen"])}];
}}
"""


def modes(t):
	return HEAD + f"""	work [label={q(t["work"])}, fillcolor="#d7efd9"];
	travel [label={q(t["travel"])}, fillcolor="#d6e4fa"];
	pause [label={q(t["pause"])}, fillcolor="#fdf0c8"];
	work -> travel [label={q(t["cmd_travel"])}];
	travel -> work [label={q(t["cmd_work"])}];
	work -> pause [label={q(t["cmd_pause"])}];
	pause -> work [label={q(t["cmd_work"])}];
	travel -> pause [label={q(t["cmd_pause"])}];
	pause -> travel [label={q(t["cmd_travel"])}];
}}
"""


def todo_states(t):
	return HEAD + f"""	open [label={q(t["t_open"])}];
	planned [label={q(t["t_planned"])}, fillcolor="#d6e4fa"];
	done [label={q(t["t_done"])}, fillcolor="#e4e6ea"];
	ignored [label={q(t["t_ignored"])}, fillcolor="#f2f2f2", style="rounded,filled,dashed"];
	note [shape=note, label={q(t["overdue"])}, fillcolor="#f8d4d4", color="#c62828"];
	open -> planned [label={q(t["plan"])}];
	planned -> open [label={q(t["unplan"])}];
	planned -> planned [label={q(t["postpone"])}];
	open -> done [label={q(t["done"])}];
	planned -> done [label={q(t["done"])}];
	done -> open [label={q(t["undo"])}];
	open -> ignored [label={q(t["ignore"])}];
	planned -> ignored [label={q(t["ignore"])}];
	ignored -> open [label={q(t["restore"])}];
	note -> planned [style=dotted, arrowhead=none];
}}
"""


def entities(t):
	g = 'digraph G {\n\tgraph [rankdir=TB, fontname="Helvetica", fontsize=11, bgcolor="white", pad=0.2, nodesep=0.35, ranksep=0.5];\n' \
		'\tnode [fontname="Helvetica", fontsize=11, shape=box, style="rounded,filled", fillcolor="#eef2fb", color="#5b6bd6"];\n' \
		'\tedge [fontname="Helvetica", fontsize=9, color="#555555", arrowhead=crow, arrowtail=tee, dir=both];\n'
	n = {k: t[k] for k in ("company", "client", "site", "entry", "segment", "expense", "attachment", "ref", "tag", "role", "todo", "preset", "sample", "place", "schedule", "absence")}
	g += "".join(f"\t{k} [label={q(v)}];\n" for k, v in n.items())
	g += '\tentry [fillcolor="#d7efd9"];\n\ttodo [fillcolor="#d6e4fa"];\n'
	for a, b in (("company", "client"), ("company", "site"), ("client", "site"), ("company", "entry"), ("client", "entry"),
			("entry", "segment"), ("segment", "sample"), ("entry", "expense"), ("entry", "attachment"), ("entry", "ref"),
			("entry", "tag"), ("role", "entry"), ("todo", "entry"), ("company", "todo"), ("client", "todo"), ("role", "preset"),
			("company", "preset")):
		g += f"\t{a} -> {b};\n"
	g += '\t{ rank=same; place; schedule; absence; }\n\tplace -> schedule [style=invis];\n\tschedule -> absence [style=invis];\n}\n'
	return g


def stop_flow(t):
	return HEAD.replace("rankdir=LR", "rankdir=TB") + f"""	stop [label={q(t["stop"])}, fillcolor="#f8d4d4"];
	short [shape=diamond, label={q(t["short"])}, fillcolor="#ffffff"];
	discard [label={q(t["discard"])}, fillcolor="#e4e6ea"];
	q [shape=diamond, label={q(t["q_work"])}, fillcolor="#ffffff"];
	ask [label={q(t["now"] + " / " + t["return"] + "\\n" + t["end_time"])}];
	full [shape=diamond, label={q(t["complete_fields"])}, fillcolor="#ffffff"];
	fromtodo [shape=diamond, label={q(t["from_todo"])}, fillcolor="#ffffff"];
	closed [label={q(t["closed"])}, fillcolor="#e4e6ea"];
	tocomplete [label={q(t["to_complete"])}, fillcolor="#f8d4d4"];
	fill [label={q(t["fill"])}];
	tododone [shape=diamond, label={q(t["todo_done"])}, fillcolor="#ffffff"];
	stop -> short;
	short -> discard [label={q(t["yes"] + ": " + t["discard"])}];
	short -> q [label={q(t["no"] + " / " + t["keep"])}];
	q -> ask [label={q(t["yes"])}];
	q -> full [label={q(t["no"])}];
	ask -> full;
	full -> fromtodo [label={q(t["yes"])}];
	full -> tocomplete [label={q(t["no"])}];
	fromtodo -> closed [label={q(t["no"])}];
	fromtodo -> tocomplete [label={q(t["yes"])}];
	tocomplete -> fill -> tododone;
	tododone -> closed;
}}
"""


def main():
	lang, out = sys.argv[1], sys.argv[2]
	t = L[lang]
	os.makedirs(out, exist_ok=True)
	for name, f in (("entry-states", entry_states), ("modes", modes), ("todo-states", todo_states), ("entities", entities), ("stop-flow", stop_flow)):
		with open(os.path.join(out, name + ".dot"), "w") as fh:
			fh.write(f(t))


if __name__ == "__main__":
	main()
