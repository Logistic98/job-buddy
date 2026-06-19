-- Persist per-favorite job analysis result and analysis time.
ALTER TABLE job_favorite ADD COLUMN IF NOT EXISTS analysis_json TEXT;
ALTER TABLE job_favorite ADD COLUMN IF NOT EXISTS analyzed_at TIMESTAMPTZ;
