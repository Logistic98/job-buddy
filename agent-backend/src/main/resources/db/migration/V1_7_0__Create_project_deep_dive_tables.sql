CREATE TABLE IF NOT EXISTS job_buddy_project_deep_dive_project (
  project_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  role VARCHAR(128),
  summary TEXT,
  tech_stack VARCHAR(512),
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_project_deep_dive_project_updated
  ON job_buddy_project_deep_dive_project (updated_at DESC);

CREATE TABLE IF NOT EXISTS job_buddy_project_deep_dive_material (
  material_id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  file_name VARCHAR(512),
  content_type VARCHAR(128),
  content TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_project_deep_dive_material_project FOREIGN KEY (project_id) REFERENCES job_buddy_project_deep_dive_project(project_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_project_deep_dive_material_project
  ON job_buddy_project_deep_dive_material (project_id, created_at DESC);

CREATE TABLE IF NOT EXISTS job_buddy_project_deep_dive_question (
  question_id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  question TEXT NOT NULL,
  answer TEXT,
  category VARCHAR(128),
  difficulty VARCHAR(32),
  source VARCHAR(32),
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_project_deep_dive_question_project FOREIGN KEY (project_id) REFERENCES job_buddy_project_deep_dive_project(project_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_job_buddy_project_deep_dive_question_project
  ON job_buddy_project_deep_dive_question (project_id, created_at DESC);
