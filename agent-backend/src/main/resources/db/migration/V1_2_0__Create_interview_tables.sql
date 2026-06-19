CREATE TABLE IF NOT EXISTS job_buddy_interview_question (
  question_id VARCHAR(64) PRIMARY KEY,
  title VARCHAR(512) NOT NULL,
  category VARCHAR(128),
  difficulty VARCHAR(32),
  question_type VARCHAR(32),
  content TEXT NOT NULL,
  answer TEXT,
  tags_json TEXT,
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_interview_question_category
  ON job_buddy_interview_question (category, difficulty);

CREATE INDEX IF NOT EXISTS idx_job_buddy_interview_question_updated
  ON job_buddy_interview_question (updated_at DESC);

CREATE TABLE IF NOT EXISTS job_buddy_interview_exam (
  exam_id VARCHAR(64) PRIMARY KEY,
  title VARCHAR(512),
  status VARCHAR(32),
  total_count INT,
  answered_count INT,
  score NUMERIC(8,2),
  started_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  submitted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_interview_exam_started
  ON job_buddy_interview_exam (started_at DESC);

CREATE TABLE IF NOT EXISTS job_buddy_interview_exam_question (
  id BIGSERIAL PRIMARY KEY,
  exam_id VARCHAR(64) NOT NULL,
  question_id VARCHAR(64) NOT NULL,
  display_order INT,
  user_answer TEXT,
  correct BOOLEAN,
  score NUMERIC(8,2),
  evaluated_at TIMESTAMPTZ,
  CONSTRAINT fk_interview_exam_question_exam FOREIGN KEY (exam_id) REFERENCES job_buddy_interview_exam(exam_id) ON DELETE CASCADE,
  CONSTRAINT fk_interview_exam_question_question FOREIGN KEY (question_id) REFERENCES job_buddy_interview_question(question_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_interview_exam_question_exam
  ON job_buddy_interview_exam_question (exam_id, display_order);
