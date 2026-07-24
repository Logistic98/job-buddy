import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/login', name: 'login', component: () => import('../components/LoginPage.vue'), meta: { public: true } },
  { path: '/chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { permission: 'chat:use' } },
  {
    path: '/profile',
    name: 'boss-resume',
    component: () => import('../components/BossResumePage.vue'),
    meta: { permission: 'resume:use' },
  },
  { path: '/jobs', name: 'jobs', component: () => import('../views/JobsView.vue'), meta: { permission: 'jobs:use' } },
  {
    path: '/journey',
    name: 'journey',
    component: () => import('../components/JobJourney.vue'),
    meta: { permission: 'journey:use' },
  },
  {
    path: '/resumes',
    component: () => import('../views/ResumeModuleView.vue'),
    meta: { permission: 'resume:use' },
    children: [
      { path: '', name: 'resume-manager', component: () => import('../components/ResumeManager.vue') },
      { path: 'writer', name: 'resume-writer', component: () => import('../components/ResumeWriter.vue') },
      { path: 'analysis', name: 'resumes', component: () => import('../components/ResumeLibrary.vue') },
    ],
  },
  {
    path: '/project-deep-dive',
    name: 'project-deep-dive',
    component: () => import('../components/ProjectDeepDive.vue'),
    meta: { permission: 'project:use' },
  },
  {
    path: '/practice',
    name: 'written-exam',
    component: () => import('../components/WrittenExamCenter.vue'),
    meta: { permission: 'practice:use' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('../components/SettingsCenter.vue'),
    meta: { management: true },
  },
  { path: '/admin/users', redirect: '/settings?tab=users' },
  {
    path: '/access-denied',
    name: 'access-denied',
    component: () => import('../components/AccessDeniedPage.vue'),
  },
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
  ensureDynamicMenuRoutes(auth)
  if (to.meta.management && !auth.isAdmin) return { path: firstAllowedPath(auth), query: { denied: 'management' } }
  if (to.meta.permission && !auth.hasPermission(to.meta.permission))
    return { path: firstAllowedPath(auth), query: { denied: 'permission' } }
  return true
})

const componentRegistry = {
  chat: () => import('../views/ChatView.vue'),
  profile: () => import('../components/BossResumePage.vue'),
  jobs: () => import('../views/JobsView.vue'),
  journey: () => import('../components/JobJourney.vue'),
  resumes: () => import('../views/ResumeModuleView.vue'),
  'project-deep-dive': () => import('../components/ProjectDeepDive.vue'),
  practice: () => import('../components/WrittenExamCenter.vue'),
  settings: () => import('../components/SettingsCenter.vue'),
}
const builtInPaths = new Set(routes.map((route) => route.path))
const dynamicPaths = new Set()
function ensureDynamicMenuRoutes(auth) {
  for (const menu of auth.menus || []) {
    const path = menu.routePath
    const component = componentRegistry[menu.componentKey]
    if (menu.parentId || !path || !component || builtInPaths.has(path) || dynamicPaths.has(path)) continue
    router.addRoute({
      path,
      name: `dynamic-menu-${menu.menuId || menu.menuCode}`,
      component,
      meta: { permission: menu.permissionCode || '' },
    })
    dynamicPaths.add(path)
  }
}

export function firstAllowedPath(auth) {
  const menu = (auth.menus || []).find(
    (item) =>
      !item.parentId &&
      item.routePath &&
      (!item.permissionCode || auth.hasPermission(item.permissionCode)) &&
      (item.componentKey !== 'settings' || auth.isAdmin),
  )
  return menu?.routePath || '/access-denied'
}

export default router
