-- v2: URL ingest (§3.1). Fetch jobs share ingest_jobs with Inbox ingests,
-- so the dashboard and Sine show one queue.
ALTER TABLE ingest_jobs ADD COLUMN user_id INTEGER REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE ingest_jobs ADD COLUMN url TEXT;
ALTER TABLE ingest_jobs ADD COLUMN title TEXT;
ALTER TABLE ingest_jobs ADD COLUMN progress REAL NOT NULL DEFAULT 0;
ALTER TABLE ingest_jobs ADD COLUMN force INTEGER NOT NULL DEFAULT 0;
CREATE INDEX ingest_jobs_queue ON ingest_jobs(status, id);
CREATE INDEX ingest_jobs_user ON ingest_jobs(user_id, id);
-- Q12: a URL fetched before is recognised before anything downloads.
CREATE INDEX objects_source_url ON objects(source_url);
