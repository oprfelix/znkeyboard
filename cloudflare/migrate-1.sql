-- For the database created before uses, downloads and meta existed (once, already applied).
ALTER TABLE installs ADD COLUMN uses INTEGER NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS downloads (platform TEXT PRIMARY KEY, count INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated TEXT NOT NULL);
