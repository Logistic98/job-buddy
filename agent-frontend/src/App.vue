<template>
  <div class="app-shell">
    <AppSidebar :active="active" @navigate="active = $event" />
    <main class="app-main">
      <h1>Job Buddy</h1>
      <p>求职智能工作台正在初始化。</p>
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import AppSidebar from './components/AppSidebar.vue'
const active = ref('chat')
</script>
