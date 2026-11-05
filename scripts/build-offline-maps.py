#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Makes offline map files for a phone without ActivityTrack Web (issue #155,
# docs/OFFLINE-MAPS.md): copy them into the `maps` folder of the data folder and
# import them in Settings → Location → Offline maps. (With a server, its Mappe page
# does the same work: ActivityTrack Web, maps/pmtiles.py.)
#
# For each area of a regions file it extracts the OpenStreetMap vector basemap of
# Protomaps (needs the `pmtiles` program, https://github.com/protomaps/go-pmtiles),
# drops the heavy layers (the green areas are ~85 % of the bytes; they are not needed
# to read a track on a map) and rewrites the PMTiles file.
#
#   python3 -I scripts/build-offline-maps.py build scripts/offline-maps.json out/ --pmtiles ~/bin/pmtiles
#   python3 -I scripts/build-offline-maps.py strip in.pmtiles out.pmtiles
#   python3 -I scripts/build-offline-maps.py verify out/*.pmtiles
#
# Standard library only. Data: © OpenStreetMap contributors (ODbL), Protomaps.

import argparse
import datetime
import gzip
import json
import math
import os
import re
import struct
import subprocess
import sys
import urllib.request

DEFAULT_DROP = ("landuse", "pois", "buildings", "natural", "landcover")
ATTRIBUTION = "© OpenStreetMap contributors · Protomaps"
ROOT_DIR_MAX = 16384 - 127	# header and root directory must fit the first 16 KiB, which clients fetch at once


def read_varint(b, i):
	r = s = 0
	while True:
		c = b[i]
		i += 1
		r |= (c & 0x7F) << s
		if not c & 0x80:
			return r, i
		s += 7


def write_varint(n):
	out = bytearray()
	while True:
		c = n & 0x7F
		n >>= 7
		if n:
			out.append(c | 0x80)
		else:
			out.append(c)
			return bytes(out)


def decompress(data, kind):
	if kind == 2:
		return gzip.decompress(data)
	if kind in (0, 1):
		return data
	sys.exit("unsupported compression %d (only none and gzip)" % kind)


class PMTiles:
	"""A PMTiles v3 file read whole into memory (the files are a few MB)."""

	def __init__(self, path):
		self.d = open(path, "rb").read()
		if self.d[:7] != b"PMTiles" or self.d[7] != 3:
			sys.exit("%s: not a PMTiles v3 file" % path)
		(self.root_off, self.root_len, self.meta_off, self.meta_len, self.leaf_off, self.leaf_len,
		 self.data_off, self.data_len) = struct.unpack_from("<8Q", self.d, 8)
		self.int_comp, self.tile_comp, self.tile_type, self.min_zoom, self.max_zoom = self.d[97], self.d[98], self.d[99], self.d[100], self.d[101]
		self.bounds = struct.unpack_from("<4i", self.d, 102)
		self.centre_zoom = self.d[118]
		self.centre = struct.unpack_from("<2i", self.d, 119)
		self.metadata = json.loads(decompress(self.d[self.meta_off:self.meta_off + self.meta_len], self.int_comp) or b"{}")

	def directory(self, off, ln):
		b = decompress(self.d[off:off + ln], self.int_comp)
		n, i = read_varint(b, 0)
		ids, runs, lens, offs = [], [], [], []
		last = 0
		for _ in range(n):
			v, i = read_varint(b, i)
			last += v
			ids.append(last)
		for _ in range(n):
			v, i = read_varint(b, i)
			runs.append(v)
		for _ in range(n):
			v, i = read_varint(b, i)
			lens.append(v)
		end = 0
		for k in range(n):
			v, i = read_varint(b, i)
			o = end if v == 0 and k > 0 else v - 1
			offs.append(o)
			end = o + lens[k]
		return list(zip(ids, runs, lens, offs))

	def entries(self):
		"""Every tile entry (id, run length, length, offset), leaf directories followed."""
		out = []

		def walk(ents):
			for tid, run, ln, off in ents:
				if run > 0:
					out.append((tid, run, ln, off))
				else:
					walk(self.directory(self.leaf_off + off, ln))

		walk(self.directory(self.root_off, self.root_len))
		return sorted(out)

	def tile(self, off, ln):
		return decompress(self.d[self.data_off + off:self.data_off + off + ln], self.tile_comp)


def fields(b):
	"""The top-level fields of a protobuf message: (number, wire type, value)."""
	i = 0
	while i < len(b):
		k, i = read_varint(b, i)
		f, w = k >> 3, k & 7
		if w == 0:
			v, i = read_varint(b, i)
		elif w == 2:
			ln, i = read_varint(b, i)
			v = b[i:i + ln]
			i += ln
		elif w == 1:
			v = b[i:i + 8]
			i += 8
		elif w == 5:
			v = b[i:i + 4]
			i += 4
		else:
			raise ValueError("wire type %d" % w)
		yield f, w, v


def layer_name(layer):
	for f, _, v in fields(layer):
		if f == 1:
			return v.decode()
	return ""


def strip_layers(tile, drop):
	"""The tile without the layers in [drop]: layers are independent blocks, so they are copied or left out."""
	out = bytearray()
	for f, w, v in fields(tile):
		if f == 3 and layer_name(v) in drop:
			continue
		if w == 2:
			out += write_varint(f << 3 | w) + write_varint(len(v)) + v
		elif w == 0:
			out += write_varint(f << 3 | w) + write_varint(v)
		else:
			raise ValueError("unexpected wire type %d in a tile" % w)
	return bytes(out)


def serialize_directory(entries):
	"""Entries (id, run length, length, offset), by tile id, in the directory format of the spec."""
	b = bytearray(write_varint(len(entries)))
	last = 0
	for tid, _, _, _ in entries:
		b += write_varint(tid - last)
		last = tid
	for _, run, _, _ in entries:
		b += write_varint(run)
	for _, _, ln, _ in entries:
		b += write_varint(ln)
	prev_end = None
	for _, _, ln, off in entries:
		b += write_varint(0 if prev_end is not None and off == prev_end else off + 1)
		prev_end = off + ln
	return gzip.compress(bytes(b), 9, mtime=0)


def write_pmtiles(path, src, drop):
	"""[src] without the layers in [drop], in a new file; returns (tiles, layers dropped)."""
	tiles, entries, blob = {}, [], bytearray()
	for tid, run, ln, off in src.entries():
		key = (off, ln)
		if key not in tiles:
			raw = strip_layers(src.tile(off, ln), drop)
			z = gzip.compress(raw, 9, mtime=0)
			tiles[key] = (len(blob), len(z))
			blob += z
		o, n = tiles[key]
		entries.append((tid, run, n, o))
	root = serialize_directory(entries)
	if len(root) > ROOT_DIR_MAX:
		sys.exit("the root directory is too large (%d bytes): extract a lower max zoom or a smaller area" % len(root))
	meta = dict(src.metadata)
	meta["vector_layers"] = [v for v in meta.get("vector_layers", []) if v.get("id") not in drop]
	meta["attribution"] = ATTRIBUTION
	meta.pop("tilestats", None)
	meta_z = gzip.compress(json.dumps(meta, separators=(",", ":")).encode(), 9, mtime=0)
	head = bytearray(127)
	head[0:7] = b"PMTiles"
	head[7] = 3
	root_off = 127
	meta_off = root_off + len(root)
	data_off = meta_off + len(meta_z)
	struct.pack_into("<8Q", head, 8, root_off, len(root), meta_off, len(meta_z), data_off, 0, data_off, len(blob))
	addressed = sum(run for _, run, _, _ in entries)
	struct.pack_into("<3Q", head, 72, addressed, len(entries), len(tiles))
	head[96], head[97], head[98], head[99] = 1, 2, 2, 1	# clustered, gzip directories, gzip tiles, MVT
	head[100], head[101] = src.min_zoom, src.max_zoom
	struct.pack_into("<4i", head, 102, *src.bounds)
	head[118] = src.centre_zoom
	struct.pack_into("<2i", head, 119, *src.centre)
	with open(path, "wb") as f:
		f.write(head + root + meta_z + blob)
	return len(entries)


def verify(path):
	"""Reads every tile back: a valid MVT, the layers present, nothing a viewer would choke on."""
	pm = PMTiles(path)
	layers = {}
	ents = pm.entries()
	for tid, run, ln, off in ents:
		for f, w, v in fields(pm.tile(off, ln)):
			if f == 3:
				name = layer_name(v)
				layers[name] = layers.get(name, 0) + len(v)
	size = os.path.getsize(path)
	print("%s: %d tiles, z%d-%d, %.2f MB, layers %s" % (
		os.path.basename(path), len(ents), pm.min_zoom, pm.max_zoom, size / 1e6,
		", ".join("%s %d%%" % (k, 100 * v // max(1, sum(layers.values()))) for k, v in sorted(layers.items(), key=lambda kv: -kv[1]))))
	return pm


def bbox(lat, lon, radius_km):
	dlat = radius_km / 111.32
	dlon = radius_km / (111.32 * math.cos(math.radians(lat)))
	return [round(lon - dlon, 4), round(lat - dlat, 4), round(lon + dlon, 4), round(lat + dlat, 4)]


def latest_source():
	"""The newest daily build of Protomaps: today or one of the last days."""
	for back in range(0, 8):
		day = (datetime.date.today() - datetime.timedelta(days=back)).strftime("%Y%m%d")
		url = "https://build.protomaps.com/%s.pmtiles" % day
		try:
			with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=30):
				return url
		except Exception:
			continue
	sys.exit("no daily build of Protomaps found")


def build(args):
	cfg = json.load(open(args.regions))
	source = cfg.get("source") or latest_source()
	maxzoom = int(cfg.get("maxzoom", 10))
	drop = set(cfg.get("drop", DEFAULT_DROP))
	os.makedirs(args.out, exist_ok=True)
	total = 0
	for r in cfg["regions"]:
		if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", r["id"]):
			sys.exit("bad region id %r" % r["id"])
		box = bbox(r["lat"], r["lon"], r.get("radiusKm", 100))
		raw = os.path.join(args.out, r["id"] + ".extract.tmp")
		out = os.path.join(args.out, r["id"] + ".pmtiles")
		print("== %s %s z<=%d" % (r["id"], box, maxzoom), flush=True)
		subprocess.run([args.pmtiles, "extract", source, raw, "--bbox=%s" % ",".join(map(str, box)), "--maxzoom=%d" % maxzoom], check=True)
		write_pmtiles(out, PMTiles(raw), drop)
		os.remove(raw)
		verify(out)
		total += os.path.getsize(out)
	print("%d maps, %.1f MB in all, in %s" % (len(cfg["regions"]), total / 1e6, args.out))


def main():
	ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0] if __doc__ else None)
	sub = ap.add_subparsers(dest="cmd", required=True)
	b = sub.add_parser("build", help="extract and strip the areas of a regions file")
	b.add_argument("regions")
	b.add_argument("out")
	b.add_argument("--pmtiles", default="pmtiles", help="the pmtiles program (default: from PATH)")
	s = sub.add_parser("strip", help="drop layers from a PMTiles file")
	s.add_argument("src")
	s.add_argument("dst")
	s.add_argument("--drop", default=",".join(DEFAULT_DROP))
	v = sub.add_parser("verify", help="read every tile of the files back")
	v.add_argument("files", nargs="+")
	a = ap.parse_args()
	if a.cmd == "build":
		build(a)
	elif a.cmd == "strip":
		write_pmtiles(a.dst, PMTiles(a.src), set(filter(None, a.drop.split(","))))
		verify(a.dst)
	else:
		for f in a.files:
			verify(f)


if __name__ == "__main__":
	main()
