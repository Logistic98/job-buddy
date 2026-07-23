INSERT INTO permission_definition (permission_code, permission_name, grantable, display_order)
VALUES ('platform:manage', '管理平台全局设置', FALSE, 940)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO rbac_menu (
  menu_id, tenant_id, parent_id, menu_code, menu_name, menu_type, route_path,
  component_key, external_url, icon_key, permission_code, display_order, visible, enabled
) VALUES (
  'menu_settings_platform', 'default-tenant', 'menu_settings', 'settings-platform',
  '平台全局设置', 'action', NULL, NULL, NULL, NULL, 'platform:manage', 950, FALSE, TRUE
)
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO role_menu (tenant_id, role_id, menu_id)
VALUES ('default-tenant', 'role_admin', 'menu_settings_platform')
ON CONFLICT DO NOTHING;

DELETE FROM role_menu
WHERE tenant_id = 'default-tenant'
  AND role_id = 'role_user'
  AND menu_id IN ('menu_settings', 'menu_settings_tenant');

DELETE FROM user_login_session session
USING app_user app
WHERE session.user_id = app.user_id
  AND app.user_id IN ('job_buddy_admin', 'job_buddy_user')
  AND app.password_hash = '$2y$10$/EhR7XPpYytk1JNM5FgdN.jq0zjp4AnUU4ej4VtpDPrF0aa5TxTF6';

UPDATE app_user
SET enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE user_id IN ('job_buddy_admin', 'job_buddy_user')
  AND password_hash = '$2y$10$/EhR7XPpYytk1JNM5FgdN.jq0zjp4AnUU4ej4VtpDPrF0aa5TxTF6';

ALTER TABLE boss_qr_login_session
  ADD COLUMN tool_session_token TEXT,
  ADD COLUMN tool_session_version INTEGER NOT NULL DEFAULT 0;
