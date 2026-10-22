# Server-aggregated leaderboards

The daemon reads original `scores-*.json` archives and writes derived v2 boards.
It uses the same Java catalogue validation, rating, song grouping and tie rules
as the Android client. Original history files and legacy v1 indices are read-only
to this process. Normal client uploads continue to maintain v1 indices.

## Build and verify

With JDK 17 on PATH, run from the repository root:

```text
python tools/build_ranking_server.py
python tools/test_ranking_features.py
```

Ship the resulting jar and `tools/test-libs/json.jar` together. On Linux:

```text
java -cp ranking-server.jar:json.jar cn.gmiya.malodylink.RankingDaemon ARCHIVE_ROOT CATALOG_JSON PRIVATE_STATE --verify
```

Before starting publication, make a private backup and SHA-256 manifest of all
original histories and v1 indices. Verification compares all legacy players'
totals and existing chart scores for the current catalogue revision. It fails
closed if original archives cannot reproduce a legacy result. `--verify` never
publishes snapshots; `--once` publishes once; omitting the option starts polling.

## Service layout

Install the jars, `launch.py` and `export_catalog.py` in `/opt/malody-ranking`.
Provide the current catalogue at `/var/lib/malody-ranking/catalog.json`.
Create `/etc/malody-ranking-paths.json` outside the repository with `root`
(archive directory) and `database` (catalogue SQLite file) properties. These
are filesystem paths, not SSH or application credentials.

Install the supplied systemd service and catalogue timer. Add a service drop-in
under `malody-ranking.service.d/paths.conf` granting `ReadWritePaths` only for
the archive root's new `_rankings/v2` directory. The service's filesystem is
otherwise read-only, with private networking and no Linux capabilities.
Private state stays outside the shared archive tree.

The catalogue export also creates `_catalog/keys-v1.json`, keyed by CID and
chart hash. Give the catalogue service write access only to this new directory
using a systemd `ReadWritePaths` drop-in. It reads original `.mc` files without
changing them. Native Key mode uses its declared column count; Slide mode uses
a unique regular 4/5/6-lane grid when at least 98% of note positions fit, with
explicit version labels as a fallback. Conflicting or ambiguous data stays
unknown. This descriptive metadata does not change ranking revisions or rules.

Before an Android build, run `python tools/prepare_chart_keys.py` with
`MALODY_RANK_CONFIG` pointing to an external scoped app-account configuration.
The generated asset is ignored by Git. The app also refreshes this metadata with
normal catalogue updates and uses version labels if metadata is unavailable.
The original 4/5/6-key artwork is in `android/assets/rm-ui`, with crop provenance
in `sources.json`; no character sprite pack is bundled.

The worker polls file metadata every five seconds and rereads changed archives.
Boards receive a heartbeat every minute. The timer refreshes catalogue metadata
from a read-only SQLite connection. Catalogue revisions have separate namespaces.

## Compatibility and recovery

The client reads one requested v2 board through the existing authenticated file
gateway. It validates schema, server, catalogue revision, requested chart,
timestamps, bounds, duplicate identities and numeric fields. If a snapshot is
unavailable, invalid or older than two minutes, it falls back to legacy v1.

An unreadable or conflicting source retains the last good player snapshot and
increments the failure count. Never delete original histories to repair it;
inspect the private backup first. Empty archives are cached but not ranked.
Stopping `malody-ranking.service` rolls clients back to v1 after the freshness
window, without changing source data. App caches may persist up to five minutes;
the refresh button bypasses that cache.

SSH keys, passwords, private archives, device snapshots, backup manifests and
deployment logs belong outside the repository and must never be committed.

## App updates

The existing scoped file gateway also serves `/_updates/latest.json` and
`/_updates/<SHA256>.apk`; no new daemon or account permissions are needed.
The metadata contains `schema: 1`, `server`, `package`, `version_code`,
`version_name`, `sha256`, `size`, and plain-text `notes`. Publish the complete
APK first and replace the metadata atomically afterward. Increment Android's
version code for each release and keep the same application signing key.

The About card opens an explicit update check. It fetches only bounded metadata
until the player taps Download. The client streams at most 64 MiB, checks size
and SHA-256, then checks the package name, version and installed signing
certificate before offering installation. A non-exported read-only provider
grants the Android installer access to the verified cached APK. Installation
uses Android's normal source permission and confirmation UI. No automatic
background update download is scheduled.

## Comments and community boards

`community.py` is a separate standard-library Python worker. It opens SQLite in
read-only mode, sums `stats.download_count` per song and per credited mapper,
and reads total ratings from the existing exact Java v2 snapshot. It never
rewrites original scores, v1 indices or the catalogue database.

Reviews use append-only paths
`_community/v1/reviews/<sSID-or-cCID>/<QQ>/review-<SHA256>.json`. The current vote
is the latest valid review for each QQ and song, ordered by server file time.
The QQ is an existing archive identifier, not a verified login identity.
Stars are integral 1–5 and comment text is limited to 500 UTF-16 code units,
matching Android's input limit. A mapper receives one vote per user/song across
all their difficulties. Good-rating boards sort by average stars, then vote
count, then downloads. No votes means no entry on those boards.

Derived `boards.json` contains five top-50 boards. Mapper popularity has a
default filtered board and an optional `creator_hot_all` board: both always
exclude imddesigner; only the default board also hides the configured legacy
mapper aliases. Filtering happens before selecting and numbering the top 50.
The client remembers the visibility switch; mapper good-rating boards are
unaffected. Each `songs/<topic>.json` includes song-level totals, a
`chart_downloads` map keyed by CID, and up to 200 current reviews. Android shows
the latest 50 reviews and an icon/count on each chart's details card. Unavailable
chart counts are shown as a dash instead of an invented zero.

Install `community.py` and `malody-community.service` beside the Java worker.
Create `/var/lib/malody-ranking/community` for private recovery state. In a
systemd path drop-in, grant `ReadWritePaths` to the archive root's new
`_community/v1` directory and set `ReadOnlyPaths` on its `reviews` subdirectory.
Run `python3 -B /opt/malody-ranking/community.py --once` to validate publication
before starting the service. The worker polls every five seconds, preserves
last valid reviews across corrupt inputs/restarts, and publishes atomically.
