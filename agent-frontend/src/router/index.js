import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/login', name: 'login', component: () => import('../components/LoginPage.vue'), meta: { public: true } },
  { path: '/chat', name: 'chat', component: () => import('../views/ChatView.vue') },
  { path: '/profile', name: 'boss-resume', component: () => import('../components/BossResumePage.vue') },
  { path: '/jobs', name: 'jobs', component: () => import('../views/JobsView.vue') },
  { path: '/journey', name: 'journey', component: () => import('../components/JobJourney.vue') },
  { path: '/resumes', name: 'resume-manager', component: () => import('../components/ResumeManager.vue') },
  { path: '/resume-writer', name: 'resume-writer', component: () => import('../components/ResumeWriter.vue') },
  { path: '/resume-analysis', name: 'resumes', component: () => import('../components/ResumeLibrary.vue') },
  { path: '/project-deep-dive', name: 'project-deep-dive', component: () => import('../components/ProjectDeepDive.vue') },
  { path: '/practice', name: 'written-exam', component: () => import('../components/WrittenExamCenter.vue') },
  { path: '/settings', name: 'settings', component: () => import('../components/SettingsCenter.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) {
    await auth.init().catch(() => {})
  }
  if (to.meta.public) {
    return auth.isLoggedIn ? { path: '/chat' } : true
  }
  if (!auth.isLoggedIn) {
    return { path: '/login' }
  }
  return true
})

export default router
