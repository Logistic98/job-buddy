<template>
  <aside :class="['app-sidebar', { collapsed }]">
    <div class="brand-row">
      <div class="product-mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 3l7 4v5c0 4.5-2.9 7.8-7 9-4.1-1.2-7-4.5-7-9V7z" />
          <path d="M9 12l2 2 4-5" />
        </svg>
      </div>
      <div class="workspace-name">Job Buddy</div>
      <button class="sidebar-toggle" :title="collapsed ? '展开侧栏' : '折叠侧栏'" @click="$emit('update:collapsed', !collapsed)">{{ collapsed ? '›' : '‹' }}</button>
    </div>

    <nav class="main-nav">
      <button
        v-for="item in navItems"
        :key="item.key"
        :class="['nav-item', { active: route.name === item.key }]"
        @click="navigate(item)"
      >
        <span class="nav-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path v-for="path in item.iconPaths" :key="path" :d="path" />
          </svg>
        </span>
        <span class="nav-label">{{ item.label }}</span>
      </button>
    </nav>

    <div class="sidebar-footer">
      <div class="sidebar-footer-row">
        <div :class="['system-health', healthClass]" :title="healthTitle">
          <span class="pulse"></span>
          <span class="health-label">{{ healthLabel }}</span>
        </div>
        <button class="sidebar-logout-btn" type="button" title="退出登录" @click="$emit('logout')">退出登录</button>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const props = defineProps({
  sessionCount: { type: Number, default: 0 },
  resumeCount: { type: Number, default: 0 },
  jobCount: { type: Number, default: 0 },
  systemHealth: { type: Object, default: () => ({ status: 'checking', label: '检查中' }) },
  collapsed: { type: Boolean, default: false },
})

defineEmits(['update:collapsed', 'new-chat', 'logout'])

function navigate(item) {
  if (route.name !== item.key) router.push(item.to)
}

const healthState = computed(() => props.systemHealth || { status: 'checking', label: '检查中' })
const healthLabel = computed(() => healthState.value.label || (healthState.value.status === 'up' ? '系统就绪' : '服务未连接'))
const healthClass = computed(() => `health-${healthState.value.status || 'checking'}`)
const healthTitle = computed(() => healthState.value.error || (healthState.value.detail ? JSON.stringify(healthState.value.detail) : healthLabel.value))

const icons = {
  workbench: ['M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v13A2.5 2.5 0 0 1 17.5 21h-11A2.5 2.5 0 0 1 4 18.5z', 'M8 7h8', 'M8 11h8', 'M8 15h5'],
  profile: ['M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M4 21a8 8 0 0 1 16 0', 'M17.5 8.5l1.2 1.2 2-2'],
  bookmark: ['M6 4.5A2.5 2.5 0 0 1 8.5 2h7A2.5 2.5 0 0 1 18 4.5V21l-6-3.5L6 21z'],
  journey: ['M5 19c4-8 10-6 14-14', 'M6 5h5v5', 'M18 19h-5v-5'],
  folder: ['M3 6.5A2.5 2.5 0 0 1 5.5 4H10l2 2h6.5A2.5 2.5 0 0 1 21 8.5v8A2.5 2.5 0 0 1 18.5 19h-13A2.5 2.5 0 0 1 3 16.5z'],
  pen: ['M4 20l4.5-1 10-10a2.1 2.1 0 0 0-3-3l-10 10L4 20z', 'M13.5 6.5l4 4'],
  analysis: ['M4 19V5', 'M4 19h16', 'M8 16v-5', 'M12 16V8', 'M16 16v-8'],
  projectDeep: ['M4 6.5A2.5 2.5 0 0 1 6.5 4H10l2 2h5.5A2.5 2.5 0 0 1 20 8.5v9A2.5 2.5 0 0 1 17.5 20h-11A2.5 2.5 0 0 1 4 17.5z', 'M8 11h8', 'M8 15h5', 'M15 15l2 2 3-5'],
  exam: ['M8 3h8l2 2v16H6V5z', 'M9 9h6', 'M9 13h6', 'M9 17h3'],
  settings: ['M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z', 'M19.4 15a1.8 1.8 0 0 0 .36 1.98l.04.04a2 2 0 0 1-2.83 2.83l-.04-.04A1.8 1.8 0 0 0 15 19.4a1.8 1.8 0 0 0-1.67 1.1l-.02.06a2 2 0 0 1-3.62 0l-.02-.06A1.8 1.8 0 0 0 8 19.4a1.8 1.8 0 0 0-1.98.36l-.04.04a2 2 0 0 1-2.83-2.83l.04-.04A1.8 1.8 0 0 0 4.6 15a1.8 1.8 0 0 0-1.1-1.67l-.06-.02a2 2 0 0 1 0-3.62l.06-.02A1.8 1.8 0 0 0 4.6 8a1.8 1.8 0 0 0-.36-1.98l-.04-.04a2 2 0 0 1 2.83-2.83l.04.04A1.8 1.8 0 0 0 8 4.6a1.8 1.8 0 0 0 1.67-1.1l.02-.06a2 2 0 0 1 3.62 0l.02.06A1.8 1.8 0 0 0 15 4.6a1.8 1.8 0 0 0 1.98-.36l.04-.04a2 2 0 0 1 2.83 2.83l-.04.04A1.8 1.8 0 0 0 19.4 8a1.8 1.8 0 0 0 1.1 1.67l.06.02a2 2 0 0 1 0 3.62l-.06.02A1.8 1.8 0 0 0 19.4 15z'],
}

const navItems = computed(() => [
  { key: 'chat', to: '/chat', label: '智能引擎', iconPaths: icons.workbench },
  { key: 'boss-resume', to: '/profile', label: '求职画像', iconPaths: icons.profile },
  { key: 'jobs', to: '/jobs', label: '岗位收藏', iconPaths: icons.bookmark },
  { key: 'journey', to: '/journey', label: '求职进展', iconPaths: icons.journey },
  { key: 'resume-manager', to: '/resumes', label: '简历管理', iconPaths: icons.folder },
  { key: 'resume-writer', to: '/resume-writer', label: '简历撰写', iconPaths: icons.pen },
  { key: 'resumes', to: '/resume-analysis', label: '简历分析', iconPaths: icons.analysis },
  { key: 'project-deep-dive', to: '/project-deep-dive', label: '项目深挖', iconPaths: icons.projectDeep },
  { key: 'written-exam', to: '/practice', label: '练习中心', iconPaths: icons.exam },
  { key: 'settings', to: '/settings', label: '平台设置', iconPaths: icons.settings },
])
</script>
