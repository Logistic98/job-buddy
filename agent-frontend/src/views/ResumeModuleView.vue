<template>
  <section class="resume-module-shell">
    <nav class="resume-module-tabs">
      <router-link
        v-for="tab in tabs"
        :key="tab.name"
        :to="tab.to"
        :class="['resume-module-tab', { active: route.name === tab.name }]"
      >
        {{ tab.label }}
      </router-link>
    </nav>
    <div class="resume-module-body">
      <router-view v-slot="{ Component }">
        <component :is="Component" @manage-resumes="goList" />
      </router-view>
    </div>
  </section>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const tabs = [
  { name: 'resume-manager', to: '/resumes', label: '简历列表' },
  { name: 'resume-writer', to: '/resumes/writer', label: '简历撰写' },
  { name: 'resumes', to: '/resumes/analysis', label: '简历分析' },
]

function goList() {
  router.push('/resumes')
}
</script>

<style src="../styles/modules/resume-module-layout.css"></style>

<style scoped>
.resume-module-shell {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.resume-module-tabs {
  position: relative;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px;
  background: #fff;
  border: 1px solid #e8eef7;
  border-radius: 14px;
  box-shadow: 0 10px 28px rgba(46, 64, 120, 0.06);
}
.resume-module-tab {
  height: 34px;
  display: inline-flex;
  align-items: center;
  padding: 0 16px;
  border-radius: 10px;
  color: #475467;
  font-size: 14px;
  font-weight: 800;
  text-decoration: none;
  white-space: nowrap;
}
.resume-module-tab:hover {
  background: #f2f5fc;
  color: #172033;
}
.resume-module-tab.active {
  background: #eef2ff;
  color: #3157ff;
}
.resume-module-body {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
</style>
