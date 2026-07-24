CREATE TEMP TABLE migrated_platform_role (
  tenant_id VARCHAR(64) NOT NULL,
  role_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (tenant_id, role_id)
) ON COMMIT DROP;

INSERT INTO migrated_platform_role (tenant_id, role_id)
SELECT DISTINCT rm.tenant_id, rm.role_id
FROM role_menu rm
JOIN rbac_menu menu
  ON menu.tenant_id = rm.tenant_id
 AND menu.menu_id = rm.menu_id
WHERE menu.menu_code IN ('settings-tenant', 'settings-platform');

UPDATE rbac_menu
SET menu_type = 'page',
    route_path = '/settings?tab=users',
    component_key = 'settings',
    visible = TRUE,
    display_order = 910,
    updated_at = CURRENT_TIMESTAMP
WHERE menu_code = 'settings-users';

UPDATE rbac_menu
SET menu_type = 'page',
    route_path = '/settings?tab=roles',
    component_key = 'settings',
    visible = TRUE,
    display_order = 920,
    updated_at = CURRENT_TIMESTAMP
WHERE menu_code = 'settings-roles';

UPDATE rbac_menu
SET menu_type = 'page',
    route_path = '/settings?tab=menus',
    component_key = 'settings',
    visible = TRUE,
    display_order = 930,
    updated_at = CURRENT_TIMESTAMP
WHERE menu_code = 'settings-menus';

UPDATE rbac_menu
SET menu_code = 'settings-workspace',
    menu_name = '运行参数',
    menu_type = 'page',
    route_path = '/settings?tab=workspace',
    component_key = 'settings',
    permission_code = 'platform:manage',
    visible = TRUE,
    display_order = 940,
    updated_at = CURRENT_TIMESTAMP
WHERE menu_code = 'settings-tenant';

UPDATE rbac_menu
SET menu_code = 'settings-blacklist',
    menu_name = '公司屏蔽',
    menu_type = 'page',
    route_path = '/settings?tab=blacklist',
    component_key = 'settings',
    permission_code = 'platform:manage',
    visible = TRUE,
    display_order = 950,
    updated_at = CURRENT_TIMESTAMP
WHERE menu_code = 'settings-platform';

INSERT INTO rbac_menu (
  menu_id, tenant_id, parent_id, menu_code, menu_name, menu_type, route_path,
  component_key, external_url, icon_key, permission_code, display_order, visible, enabled
) VALUES
  (
    'menu_settings_memory', 'default-tenant', 'menu_settings', 'settings-memory',
    '记忆管理', 'page', '/settings?tab=memory', 'settings', NULL, NULL,
    'platform:manage', 960, TRUE, TRUE
  ),
  (
    'menu_settings_services', 'default-tenant', 'menu_settings', 'settings-services',
    '服务监控', 'page', '/settings?tab=services', 'settings', NULL, NULL,
    'platform:manage', 970, TRUE, TRUE
  )
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO role_menu (tenant_id, role_id, menu_id)
SELECT platform_role.tenant_id, platform_role.role_id, menu.menu_id
FROM migrated_platform_role platform_role
JOIN rbac_menu menu
  ON menu.tenant_id = platform_role.tenant_id
 AND menu.menu_code IN (
   'settings-workspace',
   'settings-blacklist',
   'settings-memory',
   'settings-services'
 )
ON CONFLICT DO NOTHING;

DELETE FROM role_menu assignment
USING rbac_menu menu
WHERE assignment.tenant_id = menu.tenant_id
  AND assignment.menu_id = menu.menu_id
  AND menu.menu_type = 'action';

DELETE FROM rbac_menu
WHERE menu_type = 'action';

DELETE FROM permission_definition
WHERE permission_code IN ('boss:use', 'tenant:manage');

ALTER TABLE rbac_menu
  DROP CONSTRAINT ck_rbac_menu_type;

ALTER TABLE rbac_menu
  ADD CONSTRAINT ck_rbac_menu_type
  CHECK (menu_type IN ('directory', 'page', 'external'));
