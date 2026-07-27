CREATE TABLE chat_attachment (
  attachment_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(128),
  turn_id VARCHAR(128),
  file_name VARCHAR(512) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  suffix VARCHAR(16) NOT NULL,
  storage_path VARCHAR(1024) NOT NULL,
  size_bytes BIGINT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  parse_status VARCHAR(32) NOT NULL,
  parse_error TEXT,
  extracted_text TEXT,
  character_count INTEGER NOT NULL DEFAULT 0,
  truncated BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  bound_at TIMESTAMPTZ
);

CREATE INDEX idx_chat_attachment_owner_created
  ON chat_attachment (tenant_id, user_id, created_at DESC);

CREATE INDEX idx_chat_attachment_owner_turn
  ON chat_attachment (tenant_id, user_id, session_id, turn_id);
