CREATE TABLE IF NOT EXISTS job_buddy_resume_record (
  resume_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  original_name VARCHAR(512),
  storage_path VARCHAR(1024) NOT NULL,
  size_bytes BIGINT,
  suffix VARCHAR(32),
  uploaded_at TIMESTAMPTZ,
  parse_status VARCHAR(32),
  parse_error TEXT,
  parsed_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_resume_record_user_uploaded
  ON job_buddy_resume_record (user_id, uploaded_at DESC);

CREATE TABLE IF NOT EXISTS job_buddy_chat_session_state (
  session_id VARCHAR(128) PRIMARY KEY,
  resume_id VARCHAR(64),
  last_slots_json TEXT,
  jobs_json TEXT,
  updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_chat_session_state_updated
  ON job_buddy_chat_session_state (updated_at DESC);

CREATE TABLE IF NOT EXISTS job_buddy_chat_message_log (
  id BIGSERIAL PRIMARY KEY,
  session_id VARCHAR(128) NOT NULL,
  role VARCHAR(32) NOT NULL,
  content TEXT,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_chat_message_log_session_created
  ON job_buddy_chat_message_log (session_id, created_at ASC);
