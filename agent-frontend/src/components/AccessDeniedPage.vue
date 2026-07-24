<template>
  <main class="access-denied-page">
    <section class="access-denied-card" role="alert">
      <h1>当前账号暂无可用功能</h1>
      <p>请联系管理员分配对应菜单访问权限，或退出后使用其他账号登录。</p>
      <button type="button" @click="logout">退出登录</button>
    </section>
  </main>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function logout() {
  await auth.logout()
  await router.replace('/login')
}
</script>

<style scoped>
.access-denied-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: #f6f7fb;
}

.access-denied-card {
  width: min(460px, 100%);
  padding: 32px;
  border: 1px solid #e5e7eb;
  border-radius: 16px;
  background: #ffffff;
  box-shadow: 0 12px 32px rgb(15 23 42 / 8%);
  text-align: center;
}

.access-denied-card h1 {
  margin: 0 0 12px;
  color: #111827;
  font-size: 24px;
}

.access-denied-card p {
  margin: 0 0 24px;
  color: #64748b;
  line-height: 1.7;
}

.access-denied-card button {
  min-width: 120px;
  padding: 10px 18px;
  border: 0;
  border-radius: 8px;
  background: #2563eb;
  color: #ffffff;
  cursor: pointer;
}
</style>
