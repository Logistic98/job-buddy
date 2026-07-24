<template>
  <section class="rbac-management">
    <header class="rbac-page-head">
      <div class="rbac-page-head-main">
        <span class="rbac-page-icon">MN</span>
        <div>
          <h2>菜单管理</h2>
          <p>动态维护与当前页面一致的菜单树，配置父子层级、导航目标和后端权限映射。</p>
        </div>
      </div>
      <button class="primary-btn" @click="openCreate">创建菜单</button>
    </header>

    <div class="rbac-metrics">
      <article class="rbac-metric">
        <span>菜单总数</span><strong>{{ menus.length }}</strong
        ><em>{{ rootCount }} 个顶级菜单</em>
      </article>
      <article class="rbac-metric">
        <span>前台显示</span><strong>{{ visibleCount }}</strong
        ><em>{{ hiddenCount }} 个菜单已隐藏</em>
      </article>
      <article class="rbac-metric">
        <span>二级菜单</span><strong>{{ childCount }}</strong
        ><em>当前页面树中的子菜单</em>
      </article>
    </div>

    <p v-if="error" class="rbac-error">{{ error }}</p>
    <section class="rbac-panel">
      <div class="rbac-panel-toolbar">
        <strong>菜单树</strong><span>按层级与排序展示，共 {{ menus.length }} 个页面节点</span>
      </div>
      <div class="rbac-table-scroll">
        <div class="rbac-data-grid menu-data-grid">
          <div class="rbac-data-row is-header">
            <span>菜单</span><span>类型与导航目标</span><span>后端权限映射</span><span>显示状态</span><span>操作</span>
          </div>
          <div v-for="menu in sortedMenus" :key="menu.menuId" class="rbac-data-row">
            <div class="rbac-primary-cell rbac-tree-cell" :style="{ paddingLeft: `${depth(menu) * 22}px` }">
              <span v-if="depth(menu)" class="tree-branch">└</span><strong>{{ menu.menuName }}</strong
              ><small>{{ menu.menuCode }}</small>
            </div>
            <div class="rbac-primary-cell">
              <div class="rbac-badges">
                <span :class="['rbac-badge', typeBadge(menu.menuType)]">{{ typeName(menu.menuType) }}</span>
              </div>
              <small :title="menuTarget(menu)">{{ menuTarget(menu) }}</small>
            </div>
            <span
              ><span v-if="menu.permissionCode" class="rbac-code">{{ menu.permissionCode }}</span
              ><span v-else class="rbac-badge neutral">{{ permissionFallback(menu) }}</span></span
            >
            <span class="rbac-badges"
              ><span :class="['rbac-badge', menu.enabled ? 'success' : 'danger']">{{
                menu.enabled ? '启用' : '停用'
              }}</span
              ><span :class="['rbac-badge', menu.visible ? 'primary' : 'neutral']">{{
                menu.visible ? '显示' : '隐藏'
              }}</span></span
            >
            <span class="rbac-row-actions"
              ><button class="rbac-action-btn" @click="openEdit(menu)">编辑</button
              ><button class="rbac-action-btn danger" @click="remove(menu)">删除</button></span
            >
          </div>
          <div v-if="!menus.length" class="rbac-empty">
            <strong>暂无菜单</strong><span>创建页面菜单后可在角色管理中进行树形授权。</span>
          </div>
        </div>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="modal" class="modal-mask rbac-modal-mask" @click.self="close">
        <section class="modal-card rbac-modal wide">
          <header class="rbac-modal-head">
            <div>
              <h2>{{ modal === 'create' ? '创建菜单' : '编辑菜单' }}</h2>
              <p>菜单数据决定导航与角色授权结构，后端权限注解仍是最终安全边界。</p>
            </div>
            <button class="close" @click="close">×</button>
          </header>
          <div class="rbac-modal-body">
            <section class="rbac-form-section">
              <div class="rbac-form-section-title">
                <strong>基础信息</strong><small>编码用于稳定识别菜单节点</small>
              </div>
              <div class="rbac-form-grid">
                <label class="rbac-field"
                  ><span class="form-required">菜单编码</span
                  ><input
                    v-model.trim="form.menuCode"
                    aria-required="true"
                    maxlength="64"
                    placeholder="例如 reports，字母开头，仅限字母、数字、下划线或连字符" /></label
                ><label class="rbac-field"
                  ><span class="form-required">菜单名称</span
                  ><input
                    v-model.trim="form.menuName"
                    maxlength="64"
                    aria-required="true"
                    placeholder="例如 数据报表，最多 64 字"
                /></label>
                <label class="rbac-field"
                  ><span class="form-required">菜单类型</span
                  ><select v-model="form.menuType" aria-required="true">
                    <option :value="null" disabled>请选择菜单类型</option>
                    <option value="directory">目录</option>
                    <option value="page">页面</option>
                    <option value="external">外链</option>
                  </select></label
                ><label class="rbac-field"
                  ><span>父菜单</span
                  ><select v-model="form.parentId">
                    <option :value="null" disabled>请选择父菜单</option>
                    <option value="">顶级菜单</option>
                    <option v-for="item in parentOptions" :key="item.menuId" :value="item.menuId">
                      {{ '—'.repeat(depth(item)) }} {{ item.menuName }}
                    </option>
                  </select></label
                >
              </div>
            </section>
            <section class="rbac-form-section">
              <div class="rbac-form-section-title">
                <strong>导航目标</strong><small>页面组件键仅允许加载已构建组件</small>
              </div>
              <div class="rbac-form-grid">
                <label class="rbac-field"
                  ><span :class="{ 'form-required': form.menuType === 'page' }">内部路由</span
                  ><input
                    v-model.trim="form.routePath"
                    :aria-required="form.menuType === 'page'"
                    maxlength="256"
                    placeholder="例如 /resumes，必须以 / 开头" /></label
                ><label class="rbac-field"
                  ><span :class="{ 'form-required': form.menuType === 'page' }">页面组件键</span
                  ><input
                    v-model.trim="form.componentKey"
                    maxlength="64"
                    :aria-required="form.menuType === 'page'"
                    placeholder="例如 resumes，字母开头"
                /></label>
                <label class="rbac-field wide"
                  ><span :class="{ 'form-required': form.menuType === 'external' }">外部地址</span
                  ><input
                    v-model.trim="form.externalUrl"
                    :aria-required="form.menuType === 'external'"
                    maxlength="512"
                    placeholder="例如 https://example.com，仅支持 HTTP/HTTPS" /></label
                ><label class="rbac-field"
                  ><span>图标键</span
                  ><input v-model.trim="form.iconKey" maxlength="64" placeholder="例如 folder，最多 64 字" /></label
                ><label class="rbac-field"
                  ><span class="form-required">排序值</span
                  ><input
                    v-model.number="form.displayOrder"
                    aria-required="true"
                    type="number"
                    min="0"
                    max="100000"
                    step="1"
                    placeholder="请输入 0-100000 的整数"
                /></label>
              </div>
            </section>
            <section class="rbac-form-section">
              <div class="rbac-form-section-title">
                <strong>权限与状态</strong><small>权限码来自后端稳定权限目录</small>
              </div>
              <div class="rbac-form-grid">
                <label class="rbac-field wide"
                  ><span>关联权限码</span
                  ><select v-model="form.permissionCode">
                    <option :value="null" disabled>请选择关联权限</option>
                    <option value="">无</option>
                    <option v-for="item in permissions" :key="item.permissionCode" :value="item.permissionCode">
                      {{ item.permissionName }}（{{ item.permissionCode }}）
                    </option>
                  </select></label
                ><label class="rbac-field"
                  ><span class="form-required">前台显示</span
                  ><select v-model="form.visible" aria-required="true">
                    <option :value="null" disabled>请选择显示状态</option>
                    <option :value="true">显示</option>
                    <option :value="false">隐藏</option>
                  </select></label
                ><label class="rbac-field"
                  ><span class="form-required">菜单状态</span
                  ><select v-model="form.enabled" aria-required="true">
                    <option :value="null" disabled>请选择菜单状态</option>
                    <option :value="true">启用</option>
                    <option :value="false">停用</option>
                  </select></label
                >
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
import { createMenu, deleteMenu, listMenus, listPermissionDefinitions, updateMenu } from '../api/users'
import { validateCode, validateHttpUrl, validateInteger, validateLength } from '../utils/formValidation'

const menus = ref([])
const permissions = ref([])
const error = ref('')
const modalError = ref('')
const modal = ref('')
const selected = ref(null)
const saving = ref(false)
const form = reactive({
  parentId: null,
  menuCode: '',
  menuName: '',
  menuType: null,
  routePath: '',
  componentKey: '',
  externalUrl: '',
  iconKey: '',
  permissionCode: null,
  displayOrder: '',
  visible: null,
  enabled: null,
})
const sortedMenus = computed(() => {
  const result = []
  const visit = (parent) =>
    menus.value
      .filter((item) => (item.parentId || '') === (parent || ''))
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .forEach((item) => {
        result.push(item)
        visit(item.menuId)
      })
  visit('')
  return result
})
const parentOptions = computed(() => sortedMenus.value.filter((item) => item.menuId !== selected.value?.menuId))
const rootCount = computed(() => menus.value.filter((menu) => !menu.parentId).length)
const childCount = computed(() => menus.value.filter((menu) => !!menu.parentId).length)
const visibleCount = computed(() => menus.value.filter((menu) => menu.visible && menu.enabled).length)
const hiddenCount = computed(() => menus.value.length - visibleCount.value)

onMounted(load)
async function load() {
  error.value = ''
  try {
    ;[menus.value, permissions.value] = await Promise.all([listMenus(), listPermissionDefinitions()])
  } catch (e) {
    error.value = e?.message || '加载失败'
  }
}
function openCreate() {
  selected.value = null
  Object.assign(form, {
    parentId: null,
    menuCode: '',
    menuName: '',
    menuType: null,
    routePath: '',
    componentKey: '',
    externalUrl: '',
    iconKey: '',
    permissionCode: null,
    displayOrder: '',
    visible: null,
    enabled: null,
  })
  modal.value = 'create'
  modalError.value = ''
}
function openEdit(menu) {
  selected.value = menu
  Object.assign(form, {
    parentId: menu.parentId || '',
    menuCode: menu.menuCode,
    menuName: menu.menuName,
    menuType: menu.menuType,
    routePath: menu.routePath || '',
    componentKey: menu.componentKey || '',
    externalUrl: menu.externalUrl || '',
    iconKey: menu.iconKey || '',
    permissionCode: menu.permissionCode || '',
    displayOrder: menu.displayOrder || 0,
    visible: menu.visible,
    enabled: menu.enabled,
  })
  modal.value = 'edit'
  modalError.value = ''
}
function close() {
  modal.value = ''
  selected.value = null
}
function depth(menu) {
  let value = 0
  let cursor = menu.parentId
  const byId = new Map(menus.value.map((item) => [item.menuId, item]))
  while (cursor && value < 8) {
    value += 1
    cursor = byId.get(cursor)?.parentId
  }
  return value
}
function typeName(type) {
  return { directory: '目录', page: '页面', external: '外链' }[type] || type
}
function typeBadge(type) {
  return { directory: 'warning', page: 'primary', external: 'success' }[type] || 'neutral'
}
function menuTarget(menu) {
  return menu.externalUrl || menu.routePath || menu.componentKey || '无导航目标'
}
function permissionFallback(menu) {
  return menus.value.some((item) => item.parentId === menu.menuId && item.permissionCode) ? '由子菜单控制' : '无需权限'
}
async function save() {
  modalError.value = ''
  try {
    validateCode(form.menuCode, '菜单编码')
    validateLength(form.menuName, '菜单名称', { max: 64, required: true })
    if (!form.menuType) throw new Error('请选择菜单类型')
    const displayOrder = validateInteger(form.displayOrder, '排序值', { min: 0, max: 100000 })
    if (form.visible == null || form.enabled == null) throw new Error('请选择显示状态和菜单状态')
    validateLength(form.iconKey, '图标键', { max: 64 })
    if (form.menuType === 'page') {
      validateLength(form.routePath, '内部路由', { max: 256, required: true })
      if (!form.routePath.startsWith('/')) throw new Error('内部路由必须以 / 开头')
      validateCode(form.componentKey, '页面组件键')
    }
    if (form.menuType === 'external') validateHttpUrl(form.externalUrl, '外部地址', { required: true })
    saving.value = true
    const payload = { ...form, displayOrder }
    if (payload.externalUrl) payload.externalUrl = validateHttpUrl(payload.externalUrl, '外部地址')
    if (modal.value === 'create') await createMenu(payload)
    else await updateMenu(selected.value.menuId, payload)
    close()
    await load()
  } catch (e) {
    modalError.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}
async function remove(menu) {
  if (!confirm(`确认删除菜单「${menu.menuName}」？包含子菜单或仍被角色引用时系统会拒绝。`)) return
  try {
    await deleteMenu(menu.menuId)
    await load()
  } catch (e) {
    error.value = e?.message || '删除失败'
  }
}
</script>

<style src="../styles/modules/rbac-management.css"></style>
<style scoped>
.menu-data-grid {
  min-width: 980px;
}
.menu-data-grid .rbac-data-row {
  grid-template-columns: minmax(190px, 1.2fr) minmax(210px, 1.35fr) minmax(130px, 0.85fr) 150px 130px;
}
.rbac-tree-cell {
  position: relative;
}
.tree-branch {
  position: absolute;
  margin-left: -17px;
  color: #b5bfd0;
}
</style>
