# Photo cache design

## Goals

- Slideshow must work fully offline once photos are downloaded.
- Cache size is user-configurable; **default 1 GB** (`1_073_741_824` bytes).
  Preset choices in settings: 256 MB / 512 MB / 1 GB / 2 GB / 4 GB.
- Old frames may have tiny internal storage — prefer
  `context.getExternalFilesDir("gphotos")` when mounted (often an SD card),
  falling back to internal `filesDir`. Both are app-private: no permissions
  needed and files are removed on uninstall.

## Layout

```
<base>/gphotos/
├── index.db          # SQLite index (single source of truth)
├── media/<itemId>.jpg
└── media/<itemId>.mv.mp4   # motion part, only when motion photos enabled
```

## Index schema (plain SQLiteOpenHelper — no Room, keep the APK small)

```sql
CREATE TABLE media (
  item_id TEXT PRIMARY KEY,   -- Picker mediaItem.id
  file_name TEXT NOT NULL,
  mime TEXT NOT NULL,
  width INTEGER, height INTEGER,
  size_bytes INTEGER NOT NULL,
  created_at INTEGER,          -- photo capture time (for sort modes later)
  downloaded_at INTEGER NOT NULL,
  last_shown_at INTEGER NOT NULL DEFAULT 0,
  has_motion INTEGER NOT NULL DEFAULT 0
);
```

## Rules

- **Write order:** download to `<name>.tmp`, fsync, rename, then insert the
  index row. On startup, delete `*.tmp` and any file without an index row
  (and any row without a file). Crash-safe by construction.
- **Eviction = LRU by `last_shown_at`**, run after every sync and after the
  user lowers the cap. Never evict below the point where fewer than ~20
  photos remain if the total set is larger — a frame that shows 3 photos in
  a loop looks broken; instead surface "cache too small for N photos".
- **Cap check before download:** if adding the next file would exceed the cap,
  evict first; if the single file alone exceeds the cap, skip it and log.
- Update `last_shown_at` lazily (batch once a minute), not on every slide —
  avoid a DB write per photo on weak flash storage.
- The cap setting lives in `SharedPreferences` under `cache_size_bytes`.

## What the cache is NOT

Glide keeps its own small decoded-thumbnail cache; do not point Glide's disk
cache at these originals (set `diskCacheStrategy(NONE)` when loading from our
cache files) — otherwise every photo is stored twice.
