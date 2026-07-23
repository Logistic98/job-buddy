CREATE INDEX idx_app_user_tenant_created_username
  ON app_user (tenant_id, created_at, username);

CREATE INDEX idx_rbac_role_tenant_created_name
  ON rbac_role (tenant_id, created_at, role_name);
