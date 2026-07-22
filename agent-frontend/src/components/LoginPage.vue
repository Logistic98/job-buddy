<template>
  <section class="login-page">
    <div class="login-bg" aria-hidden="true">
      <span class="login-orb login-orb-primary"></span>
      <span class="login-orb login-orb-secondary"></span>
      <span class="login-orb login-orb-tertiary"></span>
      <span class="login-grid"></span>
      <span class="login-beam"></span>
      <span class="login-halo"></span>
      <span class="login-ring login-ring-a"></span>
      <span class="login-ring login-ring-b"></span>
      <span class="login-glass-chip login-glass-chip-a"></span>
      <span class="login-glass-chip login-glass-chip-b"></span>
      <span class="login-glass-chip login-glass-chip-c"></span>
      <span class="login-particle login-particle-a"></span>
      <span class="login-particle login-particle-b"></span>
      <span class="login-particle login-particle-c"></span>
    </div>
    <div class="login-card">
      <div class="login-brand">
        <div class="login-logo" aria-hidden="true">
          <JobBuddyLogo />
        </div>
        <div>
          <p class="eyebrow">JobBuddy</p>
          <h1>登录工作台</h1>
          <p class="login-tagline">让每一步求职更高效。</p>
        </div>
      </div>

      <form class="login-form" autocomplete="off" @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input
            v-model.trim="username"
            autocomplete="off"
            minlength="3"
            maxlength="32"
            placeholder="请输入 3-32 位用户名，字母开头"
          />
        </label>
        <label>
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            autocomplete="new-password"
            minlength="8"
            maxlength="64"
            placeholder="请输入 8-64 位密码"
          />
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
import { validateLength, validateUsername } from '../utils/formValidation'
import JobBuddyLogo from './JobBuddyLogo.vue'

const emit = defineEmits(['logged-in'])
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const showError = ref(false)
const validationError = ref('')

// 始终展示稳定的用户向文案，绝不把后端原始异常/堆栈直接抛给用户。
const errorText = computed(() => validationError.value || friendlyMessage(auth.error))

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
  validationError.value = ''
  auth.error = ''
}

async function submit() {
  try {
    validateUsername(username.value)
    validateLength(password.value, '密码', { min: 8, max: 64, required: true })
  } catch (err) {
    validationError.value = err.message
    showError.value = true
    return
  }
  validationError.value = ''
  const ok = await auth.login(username.value, password.value)
  if (ok) {
    emit('logged-in')
  } else {
    showError.value = true
  }
}
</script>
