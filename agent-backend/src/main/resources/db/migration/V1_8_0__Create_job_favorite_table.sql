CREATE TABLE IF NOT EXISTS job_buddy_job_favorite (
  favorite_id VARCHAR(128) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  job_key VARCHAR(512) NOT NULL,
  job_json TEXT NOT NULL,
  favorited_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_job_buddy_job_favorite_user_key
  ON job_buddy_job_favorite (user_id, job_key);

CREATE INDEX IF NOT EXISTS idx_job_buddy_job_favorite_user_updated
  ON job_buddy_job_favorite (user_id, updated_at DESC);
