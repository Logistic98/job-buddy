<template>
  <section class="settings-shell">
    <aside class="settings-nav glass-card">
      <header>
        <h2>平台设置</h2>
        <p>集中管理平台运行参数、数据规则、长期记忆和服务状态。</p>
      </header>
      <button
        v-for="item in menu"
        :key="item.key"
        :class="['settings-tab', { active: activeTab === item.key }]"
        @click="selectTab(item.key)"
      >
        <span class="tab-letter">{{ item.letter }}</span>
        <span
          ><strong>{{ item.label }}</strong
          ><small>{{ item.desc }}</small></span
        >
      </button>
    </aside>

    <main class="settings-content glass-card">
      <div v-if="currentSettingsComponent" class="settings-content-head">
        <div>
          <p class="eyebrow">{{ currentMenu?.en }}</p>
          <h1>{{ currentMenu?.label }}</h1>
          <p>{{ currentMenu?.desc }}</p>
        </div>
        <div v-if="currentSettingsComponent" class="settings-actions">
          <span v-if="currentModuleState.dirty" class="save-hint dirty">有未保存修改</span>
          <button
            v-if="activeTab === 'workspace'"
            type="button"
            class="secondary-btn"
            :disabled="currentModuleState.saving || currentModuleState.loading"
            @click="openWorkspaceRestoreConfirm"
          >
            恢复默认
          </button>
          <button
            type="button"
            class="primary-btn"
            :disabled="currentModuleState.saving || currentModuleState.loading"
            @click="saveCurrentModule"
          >
            {{ currentModuleState.saving ? '处理中' : '保存设置' }}
          </button>
        </div>
      </div>

      <UserManagement v-if="activeTab === 'users'" />
      <RoleManagement v-else-if="activeTab === 'roles'" />
      <MenuManagement v-else-if="activeTab === 'menus'" />
      <KeepAlive v-else>
        <component
          :is="currentSettingsComponent"
          :key="activeTab"
          ref="settingsModuleRef"
          @state-change="handleModuleState"
        />
      </KeepAlive>
    </main>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import MenuManagement from './MenuManagement.vue'
import RoleManagement from './RoleManagement.vue'
import UserManagement from './UserManagement.vue'
import CompanyBlacklistPanel from './settings/CompanyBlacklistPanel.vue'
import MemorySettingsPanel from './settings/MemorySettingsPanel.vue'
import RuntimeSettingsPanel from './settings/RuntimeSettingsPanel.vue'
import ServiceMonitorPanel from './settings/ServiceMonitorPanel.vue'

const auth = useAuthStore()
const settingsMenu = [
  {
    key: 'users',
    menuCode: 'settings-users',
    permission: 'users:manage',
    letter: 'UM',
    label: '用户管理',
    en: 'Users',
    desc: '账号状态与动态角色',
  },
  {
    key: 'roles',
    menuCode: 'settings-roles',
    permission: 'roles:manage',
    letter: 'RM',
    label: '角色管理',
    en: 'Roles',
    desc: '角色与菜单授权',
  },
  {
    key: 'menus',
    menuCode: 'settings-menus',
    permission: 'menus:manage',
    letter: 'MN',
    label: '菜单管理',
    en: 'Menus',
    desc: '动态菜单树与权限码',
  },
  {
    key: 'workspace',
    menuCode: 'settings-workspace',
    permission: 'platform:manage',
    letter: 'RP',
    label: '运行参数',
    en: 'Runtime Parameters',
    desc: '推荐、检索、执行与简历参数',
  },
  {
    key: 'blacklist',
    menuCode: 'settings-blacklist',
    permission: 'platform:manage',
    letter: 'CB',
    label: '公司屏蔽',
    en: 'Company Blocking',
    desc: '屏蔽外包、驻场和指定公司',
  },
  {
    key: 'memory',
    menuCode: 'settings-memory',
    permission: 'platform:manage',
    letter: 'MM',
    label: '记忆管理',
    en: 'Memory',
    desc: '长期记忆新增、删除、清空',
  },
  {
    key: 'services',
    menuCode: 'settings-services',
    permission: 'platform:manage',
    letter: 'SM',
    label: '服务监控',
    en: 'Service Monitor',
    desc: '服务地址、健康状态与监测记录',
  },
]
const menu = computed(() =>
  settingsMenu.filter(
    (item) =>
      auth.menus.some((authorizedMenu) => authorizedMenu.menuCode === item.menuCode) &&
      auth.hasPermission(item.permission),
  ),
)
const settingsComponents = {
  workspace: RuntimeSettingsPanel,
  blacklist: CompanyBlacklistPanel,
  memory: MemorySettingsPanel,
  services: ServiceMonitorPanel,
}
const activeTab = ref(new URLSearchParams(window.location.search).get('tab') || menu.value[0]?.key || 'users')
const settingsModuleRef = ref(null)
const moduleStates = ref({})
const currentMenu = computed(() => menu.value.find((item) => item.key === activeTab.value) || menu.value[0])
const currentSettingsComponent = computed(() => settingsComponents[activeTab.value] || null)
const currentModuleState = computed(
  () => moduleStates.value[activeTab.value] || { dirty: false, saving: false, loading: true },
)

watch(
  menu,
  (availableMenu) => {
    if (!availableMenu.some((item) => item.key === activeTab.value)) {
      activeTab.value = availableMenu[0]?.key || ''
      replaceTabQuery(activeTab.value)
    }
  },
  { immediate: true },
)

function selectTab(key) {
  activeTab.value = key
  replaceTabQuery(key)
}
function replaceTabQuery(key) {
  const url = new URL(window.location.href)
  if (key) url.searchParams.set('tab', key)
  else url.searchParams.delete('tab')
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}
function handleModuleState(state) {
  moduleStates.value = { ...moduleStates.value, [state.key]: state }
}
function saveCurrentModule() {
  settingsModuleRef.value?.save?.()
}
function openWorkspaceRestoreConfirm() {
  settingsModuleRef.value?.openRestoreConfirm?.()
}
</script>
