#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the presentation brochure (owner, 2026-10-22): four A4 pages in
# Italian, one fixed 794x1123 px block each, printed by Chromium.
#
#   scripts/build-brochure.sh      -> docs/brochure/ActivityTrack-Brochure.pdf
#
# Needs a Chromium (Debian package; no npm) and the network for the
# Google Fonts the page loads (Sora, Source Sans 3).

set -eu
cd "$(dirname "$0")/.."

CHROME=$(command -v chromium || command -v chromium-browser || command -v google-chrome || true)
[ -n "$CHROME" ] || { echo "no Chromium for the PDF" >&2; exit 1; }

# the time budget lets the web fonts load before printing
"$CHROME" --headless --disable-gpu --no-sandbox --no-pdf-header-footer \
	--virtual-time-budget=5000 \
	--print-to-pdf="docs/brochure/ActivityTrack-Brochure.pdf" \
	"file://$PWD/docs/brochure/brochure.html" 2>/dev/null
echo docs/brochure/ActivityTrack-Brochure.pdf
