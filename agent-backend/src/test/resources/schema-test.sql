CREATE TABLE IF NOT EXISTS job_buddy_resume_record (
  resume_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  original_name VARCHAR(512),
  storage_path VARCHAR(1024) NOT NULL,
  size_bytes BIGINT,
  suffix VARCHAR(32),
  uploaded_at TIMESTAMP,
  parse_status VARCHAR(32),
  parse_error CLOB,
  parsed_json CLOB
);

CREATE TABLE IF NOT EXISTS job_buddy_chat_session_state (
  session_id VARCHAR(128) PRIMARY KEY,
  resume_id VARCHAR(64),
  last_slots_json CLOB,
  jobs_json CLOB,
  updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS job_buddy_chat_message_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(128) NOT NULL,
  role VARCHAR(32) NOT NULL,
  content CLOB,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS job_buddy_auth_state (
  provider VARCHAR(64) PRIMARY KEY,
  status VARCHAR(32),
  credential_json CLOB,
  metadata_json CLOB,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_auth_state_updated
  ON job_buddy_auth_state (updated_at DESC);

CREATE TABLE IF NOT EXISTS app_user (
  user_id VARCHAR(64) PRIMARY KEY,
  username VARCHAR(128) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(128),
  role VARCHAR(64),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_login_session (
  token VARCHAR(128) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS job_buddy_journey_target (
  target_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  company_nature VARCHAR(128),
  company_scale VARCHAR(128),
  location VARCHAR(128),
  salary_range VARCHAR(128),
  domains CLOB,
  positions CLOB,
  preferred_companies CLOB,
  notes CLOB,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

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
  interview_content CLOB,
  interview_format VARCHAR(128),
  result VARCHAR(128),
  reflection CLOB,
  job_description CLOB,
  interview_process CLOB,
  next_action CLOB,
  status VARCHAR(128),
  priority VARCHAR(64),
  tags_json CLOB,
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS job_buddy_blacklist_item (
  item_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  item_type VARCHAR(32) NOT NULL,
  reason CLOB,
  source VARCHAR(32) NOT NULL DEFAULT 'system',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS interview_question (
  question_id VARCHAR(64) PRIMARY KEY,
  bank_type VARCHAR(32) NOT NULL DEFAULT 'baguwen',
  title VARCHAR(512) NOT NULL,
  category VARCHAR(128),
  difficulty VARCHAR(32),
  question_type VARCHAR(32),
  content CLOB NOT NULL,
  answer CLOB,
  tags_json CLOB,
  coding_meta_json CLOB,
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS interview_exam (
  exam_id VARCHAR(64) PRIMARY KEY,
  title VARCHAR(512),
  status VARCHAR(32),
  total_count INT,
  answered_count INT,
  score DECIMAL(8,2),
  duration_minutes INT DEFAULT 30,
  strategy_json CLOB,
  started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP,
  submitted_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS interview_exam_question (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  exam_id VARCHAR(64) NOT NULL,
  question_id VARCHAR(64) NOT NULL,
  display_order INT,
  user_answer CLOB,
  correct BOOLEAN,
  score DECIMAL(8,2),
  evaluated_at TIMESTAMP
);
