CREATE TABLE analysis_task (
  task_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(128) NOT NULL,
  task_type VARCHAR(32) NOT NULL,
  resource_key VARCHAR(2048) NOT NULL,
  status VARCHAR(32) NOT NULL,
  stage VARCHAR(64) NOT NULL,
  message VARCHAR(512),
  request_json TEXT NOT NULL,
  result_json TEXT,
  error_message TEXT,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  partial_result_json TEXT
);

CREATE TABLE platform_setting (
  scope_id VARCHAR(128) NOT NULL DEFAULT 'global',
  setting_key VARCHAR(128) NOT NULL,
  setting_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (scope_id, setting_key)
);

CREATE TABLE user_workspace_state (
  user_id VARCHAR(128) NOT NULL,
  state_key VARCHAR(128) NOT NULL,
  state_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, state_key)
);

CREATE INDEX idx_analysis_task_owner_resource ON analysis_task (tenant_id, user_id, task_type, resource_key, created_at DESC);
CREATE INDEX idx_analysis_task_status_updated ON analysis_task (status, updated_at);
CREATE UNIQUE INDEX uq_analysis_task_active_resource ON analysis_task (tenant_id, user_id, task_type, resource_key) WHERE status IN ('queued', 'running');
CREATE INDEX idx_user_workspace_state_updated ON user_workspace_state (user_id, updated_at DESC);
