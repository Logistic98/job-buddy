import { apiFetch, parseApiResponse } from './http'

const jsonOptions = (method, payload) => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
})

export async function listUsers() {
  return parseApiResponse(await apiFetch('/admin/users'), '获取用户列表失败')
}
export async function listAssignableRoles() {
  return parseApiResponse(await apiFetch('/admin/users/roles'), '获取可分配角色失败')
}
export async function createUser(payload) {
  return parseApiResponse(await apiFetch('/admin/users', jsonOptions('POST', payload)), '创建用户失败')
}
export async function updateUser(userId, payload) {
  return parseApiResponse(
    await apiFetch(`/admin/users/${encodeURIComponent(userId)}`, jsonOptions('PUT', payload)),
    '更新用户失败',
  )
}
export async function replaceUserRoles(userId, roleIds) {
  return parseApiResponse(
    await apiFetch(`/admin/users/${encodeURIComponent(userId)}/roles`, jsonOptions('PUT', { roleIds })),
    '更新用户角色失败',
  )
}
export async function resetUserPassword(userId, password) {
  return parseApiResponse(
    await apiFetch(`/admin/users/${encodeURIComponent(userId)}/password`, jsonOptions('PUT', { password })),
    '重置密码失败',
  )
}

export async function listRoles() {
  return parseApiResponse(await apiFetch('/admin/rbac/roles'), '获取角色列表失败')
}
export async function listAssignableMenus() {
  return parseApiResponse(await apiFetch('/admin/rbac/roles/menus'), '获取可授权菜单失败')
}
export async function createRole(payload) {
  return parseApiResponse(await apiFetch('/admin/rbac/roles', jsonOptions('POST', payload)), '创建角色失败')
}
export async function updateRole(roleId, payload) {
  return parseApiResponse(
    await apiFetch(`/admin/rbac/roles/${encodeURIComponent(roleId)}`, jsonOptions('PUT', payload)),
    '更新角色失败',
  )
}
export async function replaceRoleMenus(roleId, menuIds) {
  return parseApiResponse(
    await apiFetch(`/admin/rbac/roles/${encodeURIComponent(roleId)}/menus`, jsonOptions('PUT', { menuIds })),
    '更新角色菜单失败',
  )
}
export async function deleteRole(roleId) {
  return parseApiResponse(
    await apiFetch(`/admin/rbac/roles/${encodeURIComponent(roleId)}`, { method: 'DELETE' }),
    '删除角色失败',
  )
}

export async function listMenus() {
  return parseApiResponse(await apiFetch('/admin/rbac/menus'), '获取菜单列表失败')
}
export async function createMenu(payload) {
  return parseApiResponse(await apiFetch('/admin/rbac/menus', jsonOptions('POST', payload)), '创建菜单失败')
}
export async function updateMenu(menuId, payload) {
  return parseApiResponse(
    await apiFetch(`/admin/rbac/menus/${encodeURIComponent(menuId)}`, jsonOptions('PUT', payload)),
    '更新菜单失败',
  )
}
export async function deleteMenu(menuId) {
  return parseApiResponse(
    await apiFetch(`/admin/rbac/menus/${encodeURIComponent(menuId)}`, { method: 'DELETE' }),
    '删除菜单失败',
  )
}
export async function listPermissionDefinitions() {
  return parseApiResponse(await apiFetch('/admin/rbac/permissions'), '获取权限码目录失败')
}
