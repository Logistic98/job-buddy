CREATE TABLE profile_document (
  document_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  title VARCHAR(512) NOT NULL,
  document_type VARCHAR(64) NOT NULL,
  content TEXT NOT NULL,
  storage_path VARCHAR(1024),
  sha256 VARCHAR(64) NOT NULL,
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE resume_record (
  resume_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  original_name VARCHAR(512),
  storage_path VARCHAR(1024) NOT NULL,
  size_bytes BIGINT,
  suffix VARCHAR(32),
  uploaded_at TIMESTAMPTZ,
  parse_status VARCHAR(32),
  parse_error TEXT,
  parsed_json TEXT,
  sha256 VARCHAR(64),
  tenant_id VARCHAR(64) NOT NULL
);

CREATE TABLE resume_asset (
  asset_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  resume_id VARCHAR(64),
  file_name VARCHAR(512) NOT NULL,
  content_type VARCHAR(128),
  storage_path VARCHAR(1024) NOT NULL,
  size_bytes BIGINT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  tenant_id VARCHAR(64) NOT NULL,
  CONSTRAINT fk_resume_asset_resume FOREIGN KEY (resume_id) REFERENCES resume_record(resume_id) ON DELETE SET NULL
);

CREATE TABLE resume_writer_version (
  version_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(128) NOT NULL,
  resume_id VARCHAR(64),
  version_no BIGINT NOT NULL,
  source VARCHAR(32) NOT NULL,
  title VARCHAR(256),
  snapshot_json TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_profile_document_user_sha256 ON profile_document (user_id, sha256);
CREATE INDEX idx_resume_record_user_uploaded ON resume_record (user_id, uploaded_at DESC);
CREATE INDEX idx_resume_record_owner_uploaded ON resume_record (tenant_id, user_id, uploaded_at DESC);
CREATE INDEX idx_resume_asset_owner_created ON resume_asset (tenant_id, user_id, created_at DESC);
CREATE INDEX idx_resume_asset_user_sha256 ON resume_asset (user_id, sha256);
CREATE INDEX idx_resume_writer_version_owner_created ON resume_writer_version (tenant_id, user_id, created_at DESC);
CREATE UNIQUE INDEX uk_resume_writer_version_owner_no ON resume_writer_version (tenant_id, user_id, version_no);
