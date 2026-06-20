CREATE TABLE IF NOT EXISTS job_buddy_auth_state (
  provider VARCHAR(64) PRIMARY KEY,
  status VARCHAR(32),
  credential_json TEXT,
  metadata_json TEXT,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_auth_state_updated
  ON job_buddy_auth_state (updated_at DESC);
