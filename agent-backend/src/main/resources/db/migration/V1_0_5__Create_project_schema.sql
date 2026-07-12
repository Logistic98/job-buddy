CREATE TABLE project_deep_dive_project (
  project_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  role VARCHAR(128),
  summary TEXT,
  tech_stack VARCHAR(512),
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  project_period VARCHAR(128),
  team_size VARCHAR(64),
  background TEXT,
  responsibilities TEXT,
  highlights TEXT,
  challenges TEXT,
  outcomes TEXT,
  project_type VARCHAR(128),
  business_domain VARCHAR(128),
  project_status VARCHAR(64)
);

CREATE TABLE project_deep_dive_material (
  material_id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  file_name VARCHAR(512),
  content_type VARCHAR(128),
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  storage_path VARCHAR(1024),
  size_bytes BIGINT,
  sha256 VARCHAR(64),
  CONSTRAINT fk_project_material_project FOREIGN KEY (project_id) REFERENCES project_deep_dive_project(project_id) ON DELETE CASCADE
);

CREATE TABLE project_deep_dive_question (
  question_id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  question TEXT NOT NULL,
  answer TEXT,
  category VARCHAR(128),
  difficulty VARCHAR(32),
  source VARCHAR(32),
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_project_question_project FOREIGN KEY (project_id) REFERENCES project_deep_dive_project(project_id) ON DELETE CASCADE
);

CREATE INDEX idx_project_owner_updated ON project_deep_dive_project (tenant_id, user_id, updated_at DESC);
CREATE INDEX idx_project_updated ON project_deep_dive_project (updated_at DESC);
CREATE INDEX idx_project_material_project ON project_deep_dive_material (project_id, created_at DESC);
CREATE UNIQUE INDEX uk_project_material_sha256 ON project_deep_dive_material (project_id, sha256) WHERE sha256 IS NOT NULL;
CREATE INDEX idx_project_question_project ON project_deep_dive_question (project_id, created_at DESC);
