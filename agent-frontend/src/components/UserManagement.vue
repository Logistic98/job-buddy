<template>
  <section class="rbac-management">
    <header class="rbac-page-head">
      <div class="rbac-page-head-main">
        <span class="rbac-page-icon">UM</span>
        <div>
          <h2>用户管理</h2>
          <p>维护当前租户账号、启用状态与动态角色关系。</p>
        </div>
      </div>
      <button class="primary-btn" @click="openCreate">创建用户</button>
    </header>

    <div class="rbac-metrics">
      <article class="rbac-metric">
        <span>用户总数</span><strong>{{ users.length }}</strong
        ><em>当前租户全部账号</em>
      </article>
      <article class="rbac-metric">
        <span>启用用户</span><strong>{{ enabledCount }}</strong
        ><em>{{ disabledCount }} 个账号已停用</em>
      </article>
      <article class="rbac-metric">
        <span>可分配角色</span><strong>{{ roles.length }}</strong
        ><em>{{ assignedRoleCount }} 个角色已被使用</em>
      </article>
    </div>

    <p v-if="error" class="rbac-error">{{ error }}</p>
    <section class="rbac-panel">
      <div class="rbac-panel-toolbar">
        <strong>用户</strong><span>{{ loading ? '正在加载' : `共 ${users.length} 个账号` }}</span>
      </div>
      <div class="rbac-table-scroll">
        <div class="rbac-data-grid user-data-grid">
          <div class="rbac-data-row is-header">
            <span>账号</span><span>状态</span><span>动态角色</span><span>有效权限</span><span>操作</span>
          </div>
          <div v-for="user in users" :key="user.userId" class="rbac-data-row">
            <div class="rbac-account">
              <span class="rbac-avatar">{{ userInitial(user) }}</span
              ><span class="rbac-primary-cell"
                ><strong>{{ user.displayName || user.username }}</strong
                ><small>{{ user.username }}</small></span
              >
            </div>
            <span
              ><span :class="['rbac-badge', user.enabled ? 'success' : 'danger']">{{
                user.enabled ? '启用' : '停用'
              }}</span></span
            >
            <div class="rbac-badges">
              <span v-for="name in user.roleNames || []" :key="name" class="rbac-badge primary">{{ name }}</span
              ><span v-if="!user.roleNames?.length" class="rbac-badge neutral">未分配</span>
            </div>
            <span class="rbac-summary" :title="user.permissions?.join('、')">{{
              user.permissions?.join('、') || '暂无有效权限'
            }}</span>
            <span class="rbac-row-actions"
              ><button class="rbac-action-btn" @click="openEdit(user)">编辑</button
              ><button class="rbac-action-btn" @click="openPassword(user)">重置密码</button></span
            >
          </div>
          <div v-if="!loading && !users.length" class="rbac-empty">
            <strong>暂无用户</strong><span>创建账号后可在这里分配动态角色。</span>
          </div>
        </div>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="modal" class="modal-mask rbac-modal-mask" @click.self="closeModal">
        <section class="modal-card rbac-modal">
          <header class="rbac-modal-head">
            <div>
              <h2>{{ modalTitle }}</h2>
              <p>
                {{ modal === 'password' ? '重置后其他登录会话将立即失效。' : '账号通过动态角色获得树形菜单权限。' }}
              </p>
            </div>
            <button class="close" @click="closeModal">×</button>
          </header>
          <div class="rbac-modal-body">
            <template v-if="modal === 'password'">
              <section class="rbac-form-section">
                <div class="rbac-form-section-title">
                  <strong>安全设置</strong><small>{{ selected?.username }}</small>
                </div>
                <label class="rbac-field"
                  ><span class="form-required">新密码</span
                  ><input
                    v-model="form.password"
                    aria-required="true"
                    type="password"
                    autocomplete="new-password"
                    minlength="8"
                    maxlength="16"
                    placeholder="请输入 8-16 位新密码"
                /></label>
              </section>
            </template>
            <template v-else>
              <section class="rbac-form-section">
                <div class="rbac-form-section-title">
                  <strong>账号信息</strong><small>用户名用于登录，需保持全局唯一</small>
                </div>
                <div class="rbac-form-grid">
                  <label class="rbac-field"
                    ><span class="form-required">全局唯一用户名</span
                    ><input
                      v-model.trim="form.username"
                      aria-required="true"
                      autocomplete="off"
                      minlength="3"
                      maxlength="32"
                      placeholder="例如 zhangsan，3-32 位且以字母开头" /></label
                  ><label class="rbac-field"
                    ><span class="form-required">显示名称</span
                    ><input
                      v-model.trim="form.displayName"
                      aria-required="true"
                      maxlength="64"
                      placeholder="用于页面展示，最多 64 字" /></label
                  ><label v-if="modal === 'create'" class="rbac-field"
                    ><span class="form-required">初始密码</span
                    ><input
                      v-model="form.password"
                      aria-required="true"
                      type="password"
                      autocomplete="new-password"
                      minlength="8"
                      maxlength="16"
                      placeholder="请输入 8-16 位初始密码" /></label
                  ><label v-if="modal === 'edit'" class="rbac-field"
                    ><span class="form-required">账号状态</span
                    ><select v-model="form.enabled" aria-required="true">
                      <option :value="true">启用</option>
                      <option :value="false">停用</option>
                    </select></label
                  >
                </div>
              </section>
              <section class="rbac-form-section">
                <div class="rbac-form-section-title">
                  <strong>动态角色</strong><small>支持多选，最终权限取并集</small>
                </div>
                <div class="rbac-choice-list">
                  <label v-for="role in roles" :key="role.roleId" class="rbac-choice"
                    ><input
                      v-model="form.roleIds"
                      type="checkbox"
                      :value="role.roleId"
                      :disabled="role.assignable === false"
                    /><span
                      ><strong>{{ role.roleName }}</strong></span
                    ><small>{{
                      role.assignable === false ? `${role.roleCode} · 受保护角色` : role.roleCode
                    }}</small></label
                  >
                  <div v-if="!roles.length" class="rbac-empty">
                    <strong>暂无角色</strong><span>请先在角色管理中创建角色。</span>
                  </div>
                </div>
              </section>
            </template>
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
import { createUser, listAssignableRoles, listUsers, resetUserPassword, updateUser } from '../api/users'
import { validateLength, validateUsername } from '../utils/formValidation'

const users = ref([])
const roles = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const modalError = ref('')
const modal = ref('')
const selected = ref(null)
const form = reactive({ username: '', displayName: '', password: '', enabled: null, roleIds: [] })
const enabledCount = computed(() => users.value.filter((user) => user.enabled).length)
const disabledCount = computed(() => users.value.length - enabledCount.value)
const assignedRoleCount = computed(() => new Set(users.value.flatMap((user) => user.roleIds || [])).size)
const modalTitle = computed(() =>
  modal.value === 'create' ? '创建用户' : modal.value === 'password' ? '重置密码' : '编辑用户',
)

onMounted(load)
async function load() {
  loading.value = true
  error.value = ''
  try {
    ;[users.value, roles.value] = await Promise.all([listUsers(), listAssignableRoles()])
  } catch (e) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}
function userInitial(user) {
  return String(user.displayName || user.username || 'U')
    .trim()
    .slice(0, 1)
    .toUpperCase()
}
function openCreate() {
  selected.value = null
  Object.assign(form, { username: '', displayName: '', password: '', enabled: null, roleIds: [] })
  modal.value = 'create'
  modalError.value = ''
}
function openEdit(user) {
  selected.value = user
  Object.assign(form, {
    username: user.username,
    displayName: user.displayName || '',
    password: '',
    enabled: user.enabled,
    roleIds: [...(user.roleIds || [])],
  })
  modal.value = 'edit'
  modalError.value = ''
}
function openPassword(user) {
  selected.value = user
  form.password = ''
  modal.value = 'password'
  modalError.value = ''
}
function closeModal() {
  modal.value = ''
  selected.value = null
}
async function save() {
  modalError.value = ''
  try {
    if (modal.value === 'create') {
      validateUsername(form.username)
      validateLength(form.displayName, '显示名称', { max: 64, required: true })
      validateLength(form.password, '初始密码', { min: 8, max: 16, required: true })
    } else if (modal.value === 'password') {
      validateLength(form.password, '新密码', { min: 8, max: 16, required: true })
    } else {
      validateUsername(form.username)
      validateLength(form.displayName, '显示名称', { max: 64, required: true })
      if (typeof form.enabled !== 'boolean') throw new Error('请选择账号状态')
    }
  } catch (e) {
    modalError.value = e.message
    return
  }
  saving.value = true
  try {
    if (modal.value === 'create')
      await createUser({
        username: form.username,
        displayName: form.displayName,
        password: form.password,
        roleIds: form.roleIds,
      })
    else if (modal.value === 'password') await resetUserPassword(selected.value.userId, form.password)
    else
      await updateUser(selected.value.userId, {
        username: form.username,
        displayName: form.displayName,
        enabled: form.enabled,
        roleIds: form.roleIds,
      })
    closeModal()
    await load()
  } catch (e) {
    modalError.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<style src="../styles/modules/rbac-management.css"></style>
<style scoped>
.user-data-grid .rbac-data-row {
  grid-template-columns: minmax(190px, 1.15fr) 80px minmax(180px, 1.2fr) minmax(220px, 1.7fr) 170px;
}
</style>
