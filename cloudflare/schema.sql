-- One row per app install: a random id the app made, never tied to a person, device or IP.
CREATE TABLE IF NOT EXISTS installs (
  id TEXT PRIMARY KEY,
  platform TEXT NOT NULL,
  version TEXT NOT NULL,
  first_seen TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  days INTEGER NOT NULL DEFAULT 1,
  -- Polish and translate runs, as reported by the app.
  uses INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS installs_last_seen ON installs (last_seen);

-- Installers downloaded from znkeyboard.2amz.com, per platform.
CREATE TABLE IF NOT EXISTS downloads (
  platform TEXT PRIMARY KEY,
  count INTEGER NOT NULL DEFAULT 0
);

-- Small values the worker keeps, such as GitHub's own download total.
CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated TEXT NOT NULL
);
