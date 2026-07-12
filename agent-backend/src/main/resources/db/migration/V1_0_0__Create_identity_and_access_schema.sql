CREATE TABLE tenant (
  tenant_id VARCHAR(64) PRIMARY KEY,
  tenant_code VARCHAR(64) NOT NULL UNIQUE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE permission_definition (
  permission_code VARCHAR(64) PRIMARY KEY,
  permission_name VARCHAR(128) NOT NULL,
  grantable BOOLEAN NOT NULL DEFAULT TRUE,
  display_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE app_user (
  user_id VARCHAR(64) PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  display_name VARCHAR(128),
  role VARCHAR(32) DEFAULT 'admin',
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  tenant_id VARCHAR(64) NOT NULL,
  CONSTRAINT ck_app_user_role CHECK (role IN ('admin', 'user')),
  CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
);

CREATE TABLE user_login_session (
  token VARCHAR(128) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  last_seen_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rbac_role (
  role_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  role_code VARCHAR(64) NOT NULL,
  role_name VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_rbac_role_tenant_code UNIQUE (tenant_id, role_code),
  CONSTRAINT fk_rbac_role_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id) ON DELETE CASCADE
);

CREATE TABLE rbac_menu (
  menu_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  parent_id VARCHAR(64),
  menu_code VARCHAR(64) NOT NULL,
  menu_name VARCHAR(128) NOT NULL,
  menu_type VARCHAR(16) NOT NULL,
  route_path VARCHAR(256),
  component_key VARCHAR(128),
  external_url VARCHAR(512),
  icon_key VARCHAR(64),
  permission_code VARCHAR(64),
  display_order INTEGER NOT NULL DEFAULT 0,
  visible BOOLEAN NOT NULL DEFAULT TRUE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_rbac_menu_tenant_code UNIQUE (tenant_id, menu_code),
  CONSTRAINT ck_rbac_menu_target CHECK (menu_type <> 'external' OR external_url IS NOT NULL),
  CONSTRAINT ck_rbac_menu_type CHECK (menu_type IN ('directory', 'page', 'external', 'action')),
  CONSTRAINT fk_rbac_menu_parent FOREIGN KEY (parent_id) REFERENCES rbac_menu(menu_id) ON DELETE RESTRICT,
  CONSTRAINT fk_rbac_menu_permission FOREIGN KEY (permission_code) REFERENCES permission_definition(permission_code) ON DELETE RESTRICT,
  CONSTRAINT fk_rbac_menu_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id) ON DELETE CASCADE
);

CREATE TABLE role_menu (
  tenant_id VARCHAR(64) NOT NULL,
  role_id VARCHAR(64) NOT NULL,
  menu_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, role_id, menu_id),
  CONSTRAINT fk_role_menu_menu FOREIGN KEY (menu_id) REFERENCES rbac_menu(menu_id) ON DELETE CASCADE,
  CONSTRAINT fk_role_menu_role FOREIGN KEY (role_id) REFERENCES rbac_role(role_id) ON DELETE CASCADE,
  CONSTRAINT fk_role_menu_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id) ON DELETE CASCADE
);

CREATE TABLE user_permission (
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  permission_code VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, user_id, permission_code),
  CONSTRAINT fk_user_permission_definition FOREIGN KEY (permission_code) REFERENCES permission_definition(permission_code) ON DELETE CASCADE,
  CONSTRAINT fk_user_permission_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id) ON DELETE CASCADE,
  CONSTRAINT fk_user_permission_user FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE
);

CREATE TABLE user_role (
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  role_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, user_id, role_id),
  CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES rbac_role(role_id) ON DELETE CASCADE,
  CONSTRAINT fk_user_role_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id) ON DELETE CASCADE,
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE
);

CREATE TABLE auth_state (
  provider VARCHAR(64) NOT NULL,
  status VARCHAR(32),
  credential_json TEXT,
  metadata_json TEXT,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (tenant_id, user_id, provider)
);

CREATE TABLE boss_qr_login_session (
  qr_session_id VARCHAR(128) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  chat_session_id VARCHAR(128),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_boss_qr_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id) ON DELETE CASCADE,
  CONSTRAINT fk_boss_qr_user FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_app_user_global_username ON app_user (LOWER(username));
CREATE UNIQUE INDEX uk_app_user_tenant_username ON app_user (tenant_id, username);
CREATE INDEX idx_app_user_tenant_enabled ON app_user (tenant_id, enabled, created_at);
CREATE INDEX idx_user_login_session_expires ON user_login_session (expires_at);
CREATE INDEX idx_user_login_session_user ON user_login_session (user_id);
CREATE INDEX idx_rbac_role_tenant_enabled ON rbac_role (tenant_id, enabled, created_at);
CREATE INDEX idx_rbac_menu_tenant_parent_order ON rbac_menu (tenant_id, parent_id, display_order, menu_id);
CREATE INDEX idx_role_menu_menu ON role_menu (tenant_id, menu_id, role_id);
CREATE INDEX idx_user_permission_user ON user_permission (tenant_id, user_id);
CREATE INDEX idx_user_role_role ON user_role (tenant_id, role_id, user_id);
CREATE INDEX idx_auth_state_owner_updated ON auth_state (tenant_id, user_id, updated_at DESC);
CREATE INDEX idx_auth_state_updated ON auth_state (updated_at DESC);
CREATE INDEX idx_boss_qr_expires ON boss_qr_login_session (expires_at);
CREATE INDEX idx_boss_qr_owner_chat ON boss_qr_login_session (tenant_id, user_id, chat_session_id, expires_at);
