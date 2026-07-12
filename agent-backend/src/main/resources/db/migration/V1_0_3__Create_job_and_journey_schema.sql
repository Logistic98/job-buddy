CREATE TABLE blacklist_item (
  item_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  item_type VARCHAR(32) NOT NULL,
  reason TEXT,
  source VARCHAR(32) NOT NULL DEFAULT 'system',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE job_favorite (
  favorite_id VARCHAR(128) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  job_key VARCHAR(512) NOT NULL,
  job_json TEXT NOT NULL,
  favorited_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  analysis_json TEXT,
  analyzed_at TIMESTAMPTZ
);

CREATE TABLE journey_target (
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

CREATE TABLE journey_record (
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
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  favorite_key VARCHAR(512)
);

CREATE UNIQUE INDEX uk_blacklist_item_name_type ON blacklist_item (name, item_type);
CREATE INDEX idx_job_favorite_user_updated ON job_favorite (user_id, updated_at DESC);
CREATE UNIQUE INDEX uk_job_favorite_user_key ON job_favorite (user_id, job_key);
CREATE INDEX idx_journey_target_user ON journey_target (user_id, updated_at DESC);
CREATE INDEX idx_journey_record_status ON journey_record (user_id, status, result);
CREATE INDEX idx_journey_record_user_updated ON journey_record (user_id, updated_at DESC);
CREATE INDEX idx_journey_record_user_favorite ON journey_record (user_id, favorite_key, updated_at DESC);
