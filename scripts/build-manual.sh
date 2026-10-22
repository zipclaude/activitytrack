#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the user documentation (owner, 2026-10-19): for every language a
# quick start guide and the full manual, as HTML and PDF, with the
# Graphviz diagrams and the screenshots; every management-system
# integration has a guide of its own (owner), connectors/<name>.md, titled
# by its first line "<!-- title: ... -->".
#
#   scripts/build-manual.sh [version]      -> build/manual/*.pdf
#
# Needs pandoc, graphviz (dot) and a Chromium for the PDF (Debian packages;
# no npm). The diagrams are regenerated into docs/manual/<lang>/diagrams/
# (committed, so the Markdown also reads on GitHub).

set -eu
cd "$(dirname "$0")/.."

VERSION=${1:-$(sed -n 's/^Versione \([0-9][0-9.]*\).*/\1/p' docs/manual/it/manual.md | head -n 1)}
OUT=build/manual
CHROME=$(command -v chromium || command -v chromium-browser || command -v google-chrome || true)
[ -n "$CHROME" ] || { echo "no Chromium for the PDF" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"
# the style goes inside every page: no relative path that the PDF browser may not follow
{ echo "<style>"; cat docs/manual/style.css; echo "</style>"; } > "$OUT/style.html"
for lang in it en; do
	src=docs/manual/$lang
	python3 -I docs/manual/diagrams/diagrams.py "$lang" "$OUT/dot-$lang"
	mkdir -p "$src/diagrams"
	for f in "$OUT/dot-$lang"/*.dot; do
		dot -Tsvg "$f" -o "$src/diagrams/$(basename "${f%.dot}").svg"
	done
	work=$OUT/$lang
	mkdir -p "$work"
	cp -r "$src"/* "$work"/
	for doc in quickstart manual; do
		case "$lang-$doc" in
			it-quickstart) title="ActivityTrack — Guida rapida"; name=GuidaRapida ;;
			it-manual) title="ActivityTrack — Manuale"; name=Manuale ;;
			en-quickstart) title="ActivityTrack — Quick start"; name=QuickStart ;;
			en-manual) title="ActivityTrack — User manual"; name=Manual ;;
		esac
		toc=""
		[ "$doc" = manual ] && toc="--toc --toc-depth=2"
		# shellcheck disable=SC2086
		pandoc -f gfm+implicit_figures "$work/$doc.md" -s $toc -H "$OUT/style.html" \
			--metadata title="$title" --metadata subtitle="$VERSION" --metadata lang="$lang" \
			-o "$work/$doc.html"
		"$CHROME" --headless --disable-gpu --no-sandbox --no-pdf-header-footer \
			--print-to-pdf="$OUT/ActivityTrack-$name-$VERSION.pdf" "file://$PWD/$work/$doc.html" 2>/dev/null
		echo "$OUT/ActivityTrack-$name-$VERSION.pdf"
	done
	for f in "$work"/connectors/*.md; do
		[ -f "$f" ] || continue
		conn=$(basename "$f" .md)
		ctitle=$(sed -n '1s/^<!-- title: \(.*\) -->$/\1/p' "$f")
		[ "$lang" = it ] && suffix=Guida || suffix=Guide
		# capitalised name in the file name: antares -> Antares
		cname=$(printf '%s' "$conn" | sed 's/^./\U&/')
		pandoc -f gfm+implicit_figures "$f" -s -H "$OUT/style.html" \
			--metadata title="ActivityTrack — ${ctitle:-$conn}" --metadata subtitle="$VERSION" --metadata lang="$lang" \
			-o "${f%.md}.html"
		"$CHROME" --headless --disable-gpu --no-sandbox --no-pdf-header-footer \
			--print-to-pdf="$OUT/ActivityTrack-$cname-$suffix-$VERSION.pdf" "file://$PWD/${f%.md}.html" 2>/dev/null
		echo "$OUT/ActivityTrack-$cname-$suffix-$VERSION.pdf"
	done
done
