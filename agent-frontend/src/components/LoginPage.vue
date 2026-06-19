<template>
  <section class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="login-logo">JA</div>
        <div>
          <p class="eyebrow">Job Buddy</p>
          <h1>登录工作台</h1>
          <p>请输入账号密码进入求职智能工作台。</p>
        </div>
      </div>

      <form class="login-form" @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model.trim="username" autocomplete="username" placeholder="admin" />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" placeholder="123456" />
        </label>
        <button class="primary-btn login-submit" :disabled="auth.loading || !username || !password">
          {{ auth.loading ? '登录中' : '登录' }}
        </button>
      </form>
    </div>

    <div v-if="showError" class="modal-mask warning-modal-mask" @click.self="closeError">
      <div class="modal-card warning-modal-card">
        <button class="close" @click="closeError">×</button>
        <p class="eyebrow">登录失败</p>
        <h2>无法进入工作台</h2>
        <p>{{ errorText }}</p>
        <div class="modal-actions">
          <button class="primary-btn" @click="closeError">我知道了</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useAuthStore } from '../stores/auth'

const emit = defineEmits(['logged-in'])
const auth = useAuthStore()
const username = ref('admin')
const password = ref('123456')
const showError = ref(false)

// 始终展示稳定的用户向文案，绝不把后端原始异常/堆栈直接抛给用户。
const errorText = computed(() => friendlyMessage(auth.error))

function friendlyMessage(raw) {
  const text = (raw || '').trim()
  if (!text) return '登录失败，请稍后重试。'
  // 后端原始异常（SQL、连接池、堆栈等）一律不展示，统一降级为友好文案。
  if (/exception|nested|jdbc|sql|hikari|timed out|stack|\bat\s/i.test(text)) {
    return '服务暂时不可用，请稍后重试。如持续失败，请联系管理员。'
  }
  return text.length > 120 ? '登录失败，请稍后重试。' : text
}

function closeError() {
  showError.value = false
  auth.error = ''
}

async function submit() {
  const ok = await auth.login(username.value, password.value)
  if (ok) {
    emit('logged-in')
  } else {
    showError.value = true
  }
}
</script>
