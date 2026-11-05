# Offline maps (issue #155)

The track map shows the movements over the OpenStreetMap tiles. An **offline map**
is a basic map (roads, rivers, lakes, borders, place names) kept in the app, a few MB
for 100 km around a town. It is shown instead of the tiles when those are switched off
(Settings → Online services) or the phone has no network, and it asks nothing of the
network while it is shown.

## Why files of our own

`tile.openstreetmap.org` forbids offline use: no bulk download, no prefetch, no
"download for offline" button, or the app is blocked. So the app never downloads from
it. An offline map is a **vector PMTiles file** of OpenStreetMap data (ODbL), cut by
area, with the heavy layers dropped (the green areas are 85 % of the bytes; a track does
not need them): about 2.3 MB for 100 km around Trento to zoom 10 (about 1:400 000),
4.9 MB to zoom 11 (about 1:200 000).

## Where the maps come from

Any number of maps can be on the phone, each for an area; the track map uses the one
that covers the track.

1. **From ActivityTrack Web.** An administrator chooses the areas and the zoom on the
   server's *Mappe* page (ActivityTrack Web, `docs/OFFLINE-MAPS.md`) and switches on
   *Rendi disponibili le mappe offline ai telefoni*. A phone paired with that server
   receives them with the sync: after a sync the app compares `mapsRev` (in `sync/head`)
   with the one it kept; if it changed it reads `GET maps`, downloads the maps it lacks or
   whose SHA-256 changed, and deletes the server's maps that are no longer on offer. Downloads
   wait for an unmetered network (Wi-Fi). The file is checked against the size and the SHA-256
   of the catalog and against its PMTiles header; a mismatch is discarded and tried again at the
   next sync.
2. **From the data folder.** Without a server, copy `.pmtiles` files into the **`maps`** folder
   of the data folder (the app creates it with the others), then Settings → Location →
   Offline maps → **Import from the data folder**. A file is accepted if it is a PMTiles v3
   archive of vector tiles, gzip or uncompressed, whole, up to 64 MB; anything else is skipped
   and counted. Imported maps stay until deleted; the server's sync never touches them.
   The file name becomes the name of the map.

Settings → Location → Offline maps lists the maps with their origin and a *Delete* button for
each. Deleting removes the app's copy only: a file still in the `maps` folder is imported again by the
next import, so remove it from the folder as well if it must not come back. `offlineMapsEnabled` (setting and policy key) switches the whole feature off: nothing is
fetched, imported or shown.

## Making the files by hand

`scripts/build-offline-maps.py` (standard library only, with the
[`pmtiles`](https://github.com/protomaps/go-pmtiles) program) makes the files from the
Protomaps daily build, as the server does:

```sh
python3 -I scripts/build-offline-maps.py build scripts/offline-maps.json out/ --pmtiles ~/bin/pmtiles
```

For each area of `scripts/offline-maps.json` (centre and radius, 100 km by default; 23 areas of
Italy as an example) it extracts the basemap to `maxzoom` (10 by default), drops `landuse`, `pois`,
`buildings`, `natural` and `landcover`, rewrites the file and reads every tile back. `strip` and
`verify` do the two steps alone, on a file you already have (for instance the output of
`pmtiles extract`).

## In the app

- `maps/OfflineMaps.kt`: the installed maps (`files/maps/`, an index file), the sync with the
  server, the import from the folder, the checks.
- `domain/geo/OfflineMapCatalog.kt`, `PmtilesHeader.kt`: the validated catalog and the file header
  (with tests).
- `ui/track/OfflineBasemap.kt`: the style layers of the Protomaps basemap that the files keep
  (background, land, water, borders, roads by kind, places); the file is read in place with
  `pmtiles://file://<path>` (MapLibre 11.8 or later).
- Place names: Noto Sans glyphs (Latin, `assets/fonts/NotoSansRegular/`).
- Which base map: an offline map that covers the track when the OSM tiles are off or there is no
  network; the OSM tiles when allowed; otherwise no map.

## Licences and attribution

- Map data: © OpenStreetMap contributors, ODbL. Basemap schema and builds:
  [Protomaps](https://protomaps.com/). Both appear in the attribution of each file and of the map.
- Noto Sans glyphs (`app/src/main/assets/fonts/`): The Noto Project Authors, SIL Open Font
  License 1.1, text in `OFL.txt`; glyph files from `protomaps/basemaps-assets`.
