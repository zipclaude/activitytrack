Fixtures of `ActivityModelFixtureTest` (DB 21 -> 22, docs/ACTIVITY-MODEL.md 6): backup zips
of schema 21 and 20 and `expected-v21.json` (the Python reference migration of the v21 data),
written by the generator from `docs/samples/activity-migration.json`. Regenerate with

    python3 -I scripts/legacy-fixtures.py /tmp/fixtures && cp /tmp/fixtures/activitytrack-v2?-backup.zip /tmp/fixtures/expected-v21.json core/data/src/test/resources/activity-migration/
