<template>
  <section class="rbac-management">
    <header class="rbac-page-head">
      <div class="rbac-page-head-main">
        <span class="rbac-page-icon">RM</span>
        <div>
          <h2>角色管理</h2>
          <p>创建动态角色，并按当前页面结构配置树形菜单授权。</p>
        </div>
      </div>
      <button class="primary-btn" @click="openCreate">创建角色</button>
    </header>

    <div class="rbac-metrics">
      <article class="rbac-metric">
        <span>角色总数</span><strong>{{ roles.length }}</strong
        ><em>当前租户动态角色</em>
      </article>
      <article class="rbac-metric">
        <span>启用角色</span><strong>{{ enabledCount }}</strong
        ><em>{{ roles.length - enabledCount }} 个角色已停用</em>
      </article>
      <article class="rbac-metric">
        <span>授权项</span><strong>{{ authorizationCount }}</strong
        ><em>角色与菜单关系数</em>
      </article>
    </div>

    <p v-if="error" class="rbac-error">{{ error }}</p>
    <div class="rbac-card-grid">
      <article v-for="role in roles" :key="role.roleId" class="rbac-role-card">
        <header class="rbac-role-card-head">
          <div>
            <h3>{{ role.roleName }}</h3>
            <span class="rbac-code">{{ role.roleCode }}</span>
          </div>
          <span :class="['rbac-badge', role.enabled ? 'success' : 'danger']">{{ role.enabled ? '启用' : '停用' }}</span>
        </header>
        <p>{{ role.description || '尚未填写角色说明。' }}</p>
        <div class="rbac-role-meta">
          <span>菜单授权</span><strong>{{ selectedMenuCount(role) }} / {{ navigationMenus.length }}</strong>
        </div>
        <div class="rbac-row-actions">
          <button class="rbac-action-btn" @click="openEdit(role)">编辑与授权</button
          ><button class="rbac-action-btn danger" @click="remove(role)">删除</button>
        </div>
      </article>
      <div v-if="!roles.length" class="rbac-panel rbac-empty">
        <strong>暂无角色</strong><span>创建角色后即可配置菜单授权。</span>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="modal" class="modal-mask rbac-modal-mask" @click.self="close">
        <section class="modal-card rbac-modal wide">
          <header class="rbac-modal-head">
            <div>
              <h2>{{ modal === 'create' ? '创建角色' : '编辑角色' }}</h2>
              <p>角色编码用于稳定识别，菜单树与当前页面结构保持一致。</p>
            </div>
            <button class="close" @click="close">×</button>
          </header>
          <div class="rbac-modal-body">
            <section class="rbac-form-section">
              <div class="rbac-form-section-title">
                <strong>角色信息</strong><small>名称和说明用于管理员识别</small>
              </div>
              <div class="rbac-form-grid">
                <label class="rbac-field"
                  ><span class="form-required">角色编码</span
                  ><input
                    v-model.trim="form.roleCode"
                    aria-required="true"
                    maxlength="64"
                    placeholder="例如 developer，字母开头，仅限字母、数字、下划线或连字符" /></label
                ><label class="rbac-field"
                  ><span class="form-required">角色名称</span
                  ><input
                    v-model.trim="form.roleName"
                    maxlength="64"
                    aria-required="true"
                    placeholder="例如研发人员，最多 64 字"
                /></label>
                <label class="rbac-field wide"
                  ><span>角色说明</span
                  ><input
                    v-model.trim="form.description"
                    maxlength="500"
                    placeholder="说明该角色的职责和适用范围，最多 500 字" /></label
                ><label class="rbac-field"
                  ><span class="form-required">角色状态</span
                  ><select v-model="form.enabled" aria-required="true">
                    <option :value="null" disabled>请选择角色状态</option>
                    <option :value="true">启用</option>
                    <option :value="false">停用</option>
                  </select></label
                >
              </div>
            </section>
            <section class="rbac-form-section">
              <div class="rbac-form-section-title">
                <strong>菜单授权</strong
                ><small>已选择 {{ selectedNavigationCount }} / {{ navigationMenus.length }}</small>
              </div>
              <div class="rbac-choice-list rbac-menu-tree" role="tree" aria-label="菜单授权">
                <label
                  v-for="menu in navigationMenus"
                  :key="menu.menuId"
                  :class="['rbac-choice', 'rbac-tree-guide', { 'is-parent': hasChildren(menu) }]"
                  :style="{ '--tree-depth': menuDepth(menu), paddingLeft: `${14 + menuDepth(menu) * 24}px` }"
                  role="treeitem"
                  ><input
                    type="checkbox"
                    :checked="form.menuIds.includes(menu.menuId)"
                    :indeterminate="isIndeterminate(menu)"
                    @change="toggleMenu(menu, $event.target.checked)"
                  /><span
                    ><strong>{{ menu.menuName }}</strong
                    ><em v-if="hasChildren(menu)">{{ childMenus(menu.menuId).length }} 个子菜单</em></span
                  ><small>{{ menu.routePath || menu.menuCode }}</small></label
                >
                <div v-if="!navigationMenus.length" class="rbac-empty">
                  <strong>暂无菜单</strong><span>请先在菜单管理中创建菜单。</span>
                </div>
              </div>
            </section>
            <p v-if="modalError" class="rbac-error form-error-alert" role="alert" aria-live="assertive">
              {{ modalError }}
            </p>
          </div>
          <footer class="rbac-modal-actions">
            <button class="primary-btn" :disabled="saving" @click="save">{{ saving ? '保存中' : '确认保存' }}</button>
          </footer>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { createRole, deleteRole, listAssignableMenus, listRoles, updateRole } from '../api/users'
import { validateCode, validateLength } from '../utils/formValidation'

const roles = ref([])
const menus = ref([])
const error = ref('')
const modalError = ref('')
const modal = ref('')
const selected = ref(null)
const saving = ref(false)
const form = reactive({ roleCode: '', roleName: '', description: '', enabled: null, menuIds: [] })
const enabledCount = computed(() => roles.value.filter((role) => role.enabled).length)
const authorizationCount = computed(() => roles.value.reduce((total, role) => total + (role.menuIds?.length || 0), 0))
const orderedMenus = computed(() => {
  const result = []
  const visit = (parentId) =>
    menus.value
      .filter((menu) => (menu.parentId || '') === (parentId || ''))
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .forEach((menu) => {
        result.push(menu)
        visit(menu.menuId)
      })
  visit('')
  return result
})
const navigationMenus = computed(() => orderedMenus.value.filter((menu) => menu.menuType !== 'action'))
const selectedNavigationCount = computed(
  () => navigationMenus.value.filter((menu) => form.menuIds.includes(menu.menuId)).length,
)

onMounted(load)
async function load() {
  error.value = ''
  try {
    ;[roles.value, menus.value] = await Promise.all([listRoles(), listAssignableMenus()])
  } catch (e) {
    error.value = e?.message || '加载失败'
  }
}
function openCreate() {
  selected.value = null
  Object.assign(form, { roleCode: '', roleName: '', description: '', enabled: null, menuIds: [] })
  modal.value = 'create'
  modalError.value = ''
}
function openEdit(role) {
  selected.value = role
  Object.assign(form, {
    roleCode: role.roleCode,
    roleName: role.roleName,
    description: role.description || '',
    enabled: role.enabled,
    menuIds: [...(role.menuIds || [])],
  })
  modal.value = 'edit'
  modalError.value = ''
}
function close() {
  modal.value = ''
  selected.value = null
}
function menuDepth(menu) {
  let depth = 0
  let cursor = menu.parentId
  const byId = new Map(menus.value.map((item) => [item.menuId, item]))
  while (cursor && depth < 8) {
    depth += 1
    cursor = byId.get(cursor)?.parentId
  }
  return depth
}
function childMenus(menuId) {
  return navigationMenus.value.filter((menu) => menu.parentId === menuId)
}
function descendantMenus(menuId) {
  const result = []
  const visit = (parentId) => {
    childMenus(parentId).forEach((child) => {
      result.push(child)
      visit(child.menuId)
    })
  }
  visit(menuId)
  return result
}
function hasChildren(menu) {
  return childMenus(menu.menuId).length > 0
}
function isIndeterminate(menu) {
  const descendants = descendantMenus(menu.menuId)
  if (!descendants.length) return false
  const selectedDescendants = descendants.filter((item) => form.menuIds.includes(item.menuId)).length
  return selectedDescendants > 0 && selectedDescendants < descendants.length
}
function toggleMenu(menu, checked) {
  const selectedIds = new Set(form.menuIds)
  const subtree = [menu, ...descendantMenus(menu.menuId)]
  subtree.forEach((item) => (checked ? selectedIds.add(item.menuId) : selectedIds.delete(item.menuId)))

  let parentId = menu.parentId
  const menuById = new Map(navigationMenus.value.map((item) => [item.menuId, item]))
  while (parentId) {
    const selectedChildExists = childMenus(parentId).some(
      (child) =>
        selectedIds.has(child.menuId) ||
        descendantMenus(child.menuId).some((descendant) => selectedIds.has(descendant.menuId)),
    )
    if (selectedChildExists) selectedIds.add(parentId)
    else selectedIds.delete(parentId)
    parentId = menuById.get(parentId)?.parentId
  }

  form.menuIds = navigationMenus.value.filter((item) => selectedIds.has(item.menuId)).map((item) => item.menuId)
}
function selectedMenuCount(role) {
  const selectedMenuIds = new Set(role.menuIds || [])
  return navigationMenus.value.filter((menu) => selectedMenuIds.has(menu.menuId)).length
}
async function save() {
  modalError.value = ''
  try {
    validateCode(form.roleCode, '角色编码')
    validateLength(form.roleName, '角色名称', { max: 64, required: true })
    validateLength(form.description, '角色说明', { max: 500 })
    if (form.enabled == null) throw new Error('请选择角色状态')
  } catch (e) {
    modalError.value = e.message
    return
  }
  saving.value = true
  try {
    const payload = { ...form, menuIds: [...form.menuIds] }
    if (modal.value === 'create') await createRole(payload)
    else await updateRole(selected.value.roleId, payload)
    close()
    await load()
  } catch (e) {
    modalError.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}
async function remove(role) {
  if (!confirm(`确认删除角色「${role.roleName}」？被用户引用时系统会拒绝删除。`)) return
  try {
    await deleteRole(role.roleId)
    await load()
  } catch (e) {
    error.value = e?.message || '删除失败'
  }
}
</script>

<style src="../styles/modules/rbac-management.css"></style>
