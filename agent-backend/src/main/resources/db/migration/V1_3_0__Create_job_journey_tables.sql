CREATE TABLE IF NOT EXISTS job_buddy_journey_target (
  target_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  company_nature VARCHAR(128),
  company_scale VARCHAR(128),
  location VARCHAR(128),
  salary_range VARCHAR(128),
  domains TEXT,
  positions TEXT,
  preferred_companies TEXT,
  notes TEXT,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_journey_target_user
  ON job_buddy_journey_target (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS job_buddy_journey_record (
  record_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  company VARCHAR(256) NOT NULL,
  city VARCHAR(128),
  company_nature VARCHAR(128),
  company_scale VARCHAR(128),
  position_name VARCHAR(256),
  salary_range VARCHAR(128),
  business_direction VARCHAR(256),
  interview_round VARCHAR(128),
  interview_time VARCHAR(128),
  interview_content TEXT,
  interview_format VARCHAR(128),
  result VARCHAR(128),
  reflection TEXT,
  job_description TEXT,
  interview_process TEXT,
  next_action TEXT,
  status VARCHAR(128),
  priority VARCHAR(64),
  tags_json TEXT,
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_journey_record_user_updated
  ON job_buddy_journey_record (user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_job_buddy_journey_record_status
  ON job_buddy_journey_record (user_id, status, result);
