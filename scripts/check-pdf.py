#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Release step (owner, issue #44): the screenshots in the manual PDFs take at
# most a quarter of a page (half the width, half the height); a full-page
# image means the page style was lost. Needs PyMuPDF.
#
#   python3 scripts/check-pdf.py build/manual/*.pdf

import sys

import pymupdf

fail = False
for path in sys.argv[1:]:
	doc = pymupdf.open(path)
	for n, page in enumerate(doc, 1):
		w, h = page.rect.width, page.rect.height
		for img in page.get_image_info():
			x0, y0, x1, y1 = img["bbox"]
			if x1 - x0 > w * 0.55 or y1 - y0 > h * 0.5:
				print(f"::error file={path}::page {n}: image {x1 - x0:.0f}x{y1 - y0:.0f} pt on a {w:.0f}x{h:.0f} pt page")
				fail = True
	print(f"{path}: {doc.page_count} pages checked")
sys.exit(1 if fail else 0)
