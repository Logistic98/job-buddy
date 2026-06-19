<template>
  <router-view v-if="auth.initialized && !auth.isLoggedIn" />
  <div v-else-if="auth.isLoggedIn" class="system-shell">
    <AppSidebar
      :session-count="chat.sessions.length"
      :resume-count="resume.items.length"
      :job-count="job.favoriteCount"
      :system-health="systemHealth"
      v-model:collapsed="sidebarCollapsed"
      @new-chat="startNewChat"
      @logout="handleLogout"
    />

    <main :class="['main-stage', { 'sidebar-collapsed': sidebarCollapsed }]">
      <router-view v-slot="{ Component }">
        <component
          :is="Component"
          @manage-resumes="router.push('/resumes')"
          @open-writer="router.push('/resume-writer')"
        />
      </router-view>
    </main>

    <BossLoginQrModal
      :visible="!!chat.authRequired"
      :data="chat.authRequired"
      :session-id="chat.sessionId"
      @close="chat.authRequired = null"
      @logged-in="handleBossLoggedIn"
    />
  </div>
  <div v-else class="app-boot-screen"></div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSystemHealth } from './api/health'
import AppSidebar from './components/AppSidebar.vue'
import BossLoginQrModal from './components/BossLoginQrModal.vue'
import { useAuthStore } from './stores/auth'
import { useChatStore } from './stores/chat'
import { useJobStore } from './stores/job'
import { useResumeStore } from './stores/resume'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const chat = useChatStore()
const job = useJobStore()
const resume = useResumeStore()
const systemHealth = ref({ status: 'checking', label: '检查中' })
let healthTimer = null
const sidebarCollapsed = ref(false)

onMounted(async () => {
  await auth.init()
  if (auth.isLoggedIn) bootstrapAppData()
})

onBeforeUnmount(() => {
  if (healthTimer) window.clearInterval(healthTimer)
})

function bootstrapAppData() {
  chat.loadSessions().catch(() => {})
  resume.load().catch(() => {})
  job.loadFavorites().catch(() => {})
  refreshSystemHealth()
  if (healthTimer) window.clearInterval(healthTimer)
  healthTimer = window.setInterval(refreshSystemHealth, 10000)
}

watch(() => auth.isLoggedIn, (loggedIn) => {
  if (loggedIn) {
    bootstrapAppData()
    if (route.meta.public || route.path === '/login') router.replace('/chat')
  } else if (route.path !== '/login') {
    router.replace('/login')
  }
})

async function handleLogout() {
  await auth.logout()
  if (healthTimer) window.clearInterval(healthTimer)
  healthTimer = null
  chat.newSession()
}

async function refreshSystemHealth() {
  try {
    const data = await getSystemHealth()
    const up = data?.status === 'UP' || data?.status === 'up'
    systemHealth.value = { status: up ? 'up' : 'down', label: up ? '系统就绪' : '服务异常', detail: data }
  } catch (error) {
    systemHealth.value = { status: 'down', label: '服务未连接', error: error?.message || String(error || '') }
  }
}

function startNewChat() {
  chat.newSession()
  router.push('/chat')
}
async function handleBossLoggedIn() {
  router.push('/chat')
  await chat.resumeAfterAuth()
}
watch(() => chat.lastJobCardsEvent, value => { if (value) job.setJobs(value) })
watch(() => chat.lastResumeMatchEvent, value => { if (value) job.setMatch(value) })
</script>
