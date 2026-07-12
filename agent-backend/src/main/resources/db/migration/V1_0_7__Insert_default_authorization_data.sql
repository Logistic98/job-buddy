INSERT INTO tenant (tenant_id, tenant_code, enabled)
VALUES ('default-tenant', 'default', TRUE);

INSERT INTO permission_definition (permission_code, permission_name, grantable, display_order) VALUES
  ('chat:use', '使用智能对话', TRUE, 10),
  ('boss:use', '使用 Boss 直聘连接', TRUE, 20),
  ('resume:use', '使用简历与求职画像', TRUE, 30),
  ('jobs:use', '使用岗位收藏与分析', TRUE, 40),
  ('journey:use', '使用求职旅程', TRUE, 50),
  ('practice:use', '使用练习中心', TRUE, 60),
  ('project:use', '使用项目深挖', TRUE, 70),
  ('users:manage', '管理租户用户', FALSE, 900),
  ('tenant:manage', '管理租户设置', FALSE, 910),
  ('roles:manage', '管理角色', FALSE, 920),
  ('menus:manage', '管理菜单', FALSE, 930);

INSERT INTO rbac_role (role_id, tenant_id, role_code, role_name, description, enabled) VALUES
  ('role_admin', 'default-tenant', 'admin', '管理员', '系统管理员角色，拥有全部菜单与权限', TRUE),
  ('role_user', 'default-tenant', 'user', '普通用户', '普通用户角色，不包含用户、角色和菜单管理权限', TRUE);

INSERT INTO rbac_menu (
  menu_id, tenant_id, parent_id, menu_code, menu_name, menu_type, route_path,
  component_key, external_url, icon_key, permission_code, display_order, visible, enabled
) VALUES
  ('menu_chat', 'default-tenant', NULL, 'chat', '智能引擎', 'page', '/chat', 'chat', NULL, 'workbench', 'chat:use', 10, TRUE, TRUE),
  ('menu_profile', 'default-tenant', NULL, 'profile', '求职画像', 'page', '/profile', 'profile', NULL, 'profile', 'resume:use', 20, TRUE, TRUE),
  ('menu_jobs', 'default-tenant', NULL, 'jobs', '岗位收藏', 'page', '/jobs', 'jobs', NULL, 'bookmark', 'jobs:use', 30, TRUE, TRUE),
  ('menu_journey', 'default-tenant', NULL, 'journey', '求职进展', 'page', '/journey', 'journey', NULL, 'journey', 'journey:use', 40, TRUE, TRUE),
  ('menu_resumes', 'default-tenant', NULL, 'resumes', '简历管理', 'page', '/resumes', 'resumes', NULL, 'folder', 'resume:use', 50, TRUE, TRUE),
  ('menu_project', 'default-tenant', NULL, 'project-deep-dive', '项目深挖', 'page', '/project-deep-dive', 'project-deep-dive', NULL, 'projectDeep', 'project:use', 60, TRUE, TRUE),
  ('menu_practice', 'default-tenant', NULL, 'practice', '练习中心', 'page', '/practice', 'practice', NULL, 'exam', 'practice:use', 70, TRUE, TRUE),
  ('menu_settings', 'default-tenant', NULL, 'settings', '平台设置', 'page', '/settings', 'settings', NULL, 'settings', NULL, 900, TRUE, TRUE),
  ('menu_chat_boss', 'default-tenant', 'menu_chat', 'chat-boss', 'Boss 直聘能力', 'action', NULL, NULL, NULL, NULL, 'boss:use', 20, FALSE, TRUE),
  ('menu_settings_users', 'default-tenant', 'menu_settings', 'settings-users', '用户管理', 'action', NULL, NULL, NULL, NULL, 'users:manage', 910, FALSE, TRUE),
  ('menu_settings_roles', 'default-tenant', 'menu_settings', 'settings-roles', '角色管理', 'action', NULL, NULL, NULL, NULL, 'roles:manage', 920, FALSE, TRUE),
  ('menu_settings_menus', 'default-tenant', 'menu_settings', 'settings-menus', '菜单管理', 'action', NULL, NULL, NULL, NULL, 'menus:manage', 930, FALSE, TRUE),
  ('menu_settings_tenant', 'default-tenant', 'menu_settings', 'settings-tenant', '运行设置', 'action', NULL, NULL, NULL, NULL, 'tenant:manage', 940, FALSE, TRUE);

INSERT INTO role_menu (tenant_id, role_id, menu_id)
SELECT 'default-tenant', 'role_admin', menu_id FROM rbac_menu WHERE tenant_id = 'default-tenant';

INSERT INTO role_menu (tenant_id, role_id, menu_id) VALUES
  ('default-tenant', 'role_user', 'menu_chat'),
  ('default-tenant', 'role_user', 'menu_chat_boss'),
  ('default-tenant', 'role_user', 'menu_profile'),
  ('default-tenant', 'role_user', 'menu_jobs'),
  ('default-tenant', 'role_user', 'menu_journey'),
  ('default-tenant', 'role_user', 'menu_resumes'),
  ('default-tenant', 'role_user', 'menu_project'),
  ('default-tenant', 'role_user', 'menu_practice'),
  ('default-tenant', 'role_user', 'menu_settings'),
  ('default-tenant', 'role_user', 'menu_settings_tenant');

INSERT INTO app_user (
  user_id, tenant_id, username, password_hash, display_name, role, enabled
) VALUES
  ('job_buddy_admin', 'default-tenant', 'admin', '$2y$10$/EhR7XPpYytk1JNM5FgdN.jq0zjp4AnUU4ej4VtpDPrF0aa5TxTF6', '管理员', 'admin', TRUE),
  ('job_buddy_user', 'default-tenant', 'user', '$2y$10$/EhR7XPpYytk1JNM5FgdN.jq0zjp4AnUU4ej4VtpDPrF0aa5TxTF6', '普通用户', 'user', TRUE);

INSERT INTO user_role (tenant_id, user_id, role_id) VALUES
  ('default-tenant', 'job_buddy_admin', 'role_admin'),
  ('default-tenant', 'job_buddy_user', 'role_user');
