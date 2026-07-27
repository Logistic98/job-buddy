<template>
  <div>
    <p v-if="error" class="error settings-error form-error-alert" role="alert" aria-live="assertive">
      {{ error }}
    </p>
    <div v-if="loading" class="empty-state">
      <strong>正在加载设置</strong>
      <p>请稍候。</p>
    </div>
    <section v-if="memory" class="settings-section">
      <div class="setting-card">
        <div>
          <h3>记忆策略</h3>
          <p>
            长期记忆用于保存稳定偏好、求职约束和面试复盘，并按当前租户和用户隔离，帮助工作台在后续任务中读取并使用相关信息。
          </p>
        </div>
        <div class="form-grid">
          <AppSwitch v-model="memory.enabled" class="memory-switch-field" aria-label="启用记忆">
            <span class="switch-text">
              <strong>{{ memory.enabled ? '记忆已启用' : '记忆已关闭' }}</strong>
              <small>保存长期偏好与求职信息，并在后续任务中按需使用。</small>
            </span>
          </AppSwitch>
          <AppSwitch v-model="memory.autoSaveChat" class="memory-switch-field" aria-label="自动保存稳定偏好">
            <span class="switch-text">
              <strong>{{ memory.autoSaveChat ? '自动保存已启用' : '自动保存已关闭' }}</strong>
              <small>从对话中识别稳定偏好，并自动沉淀为长期记忆。</small>
            </span>
          </AppSwitch>
          <AppSwitch v-model="memory.autoUseMemory" class="memory-switch-field" aria-label="按需使用相关记忆">
            <span class="switch-text">
              <strong>{{ memory.autoUseMemory ? '按需使用已启用' : '按需使用已关闭' }}</strong>
              <small>执行任务时检索与当前请求相关的记忆，减少重复说明。</small>
            </span>
          </AppSwitch>
          <label
            ><span class="form-required">最大记忆条数</span
            ><input
              v-model.number="memory.maxItems"
              aria-required="true"
              type="number"
              min="20"
              max="1000"
              step="10"
              placeholder="请输入 20-1000 的整数"
          /></label>
        </div>
      </div>
      <div class="setting-card">
        <div class="memory-card-head">
          <div>
            <h3>记忆管理</h3>
            <p>可以手动新增、删除或清空记忆。建议把稳定偏好、求职约束、面试复盘沉淀为记忆。</p>
          </div>
          <button class="danger-btn" :disabled="!memories.length" @click="clearAll">清空记忆</button>
        </div>
        <div class="memory-editor">
          <label class="memory-editor-field">
            <span class="form-required">记忆内容</span>
            <input
              v-model.trim="form.content"
              maxlength="2000"
              aria-label="记忆内容"
              aria-required="true"
              placeholder="例如：优先看上海 Agent 应用开发岗，薪资 40-50k，排除外包驻场"
              @keyup.enter="create"
            />
          </label>
          <button class="primary-btn" @click="create">新增记忆</button>
        </div>
        <div class="source-list memory-list">
          <article v-for="item in memories" :key="item.id" class="source-item memory-item">
            <div>
              <strong>{{ item.content }}</strong>
              <small>{{ memorySourceLabel(item.source) }} · {{ formatTime(item.updatedAt || item.createdAt) }}</small>
            </div>
            <div class="source-badges">
              <span :class="['source-state', item.enabled ? 'enabled' : 'disabled']">{{
                item.enabled ? '启用' : '关闭'
              }}</span
              ><button class="danger-text" @click="remove(item.id)">删除</button>
            </div>
          </article>
          <div v-if="!memories.length" class="empty-state">
            <strong>暂无记忆</strong>
            <p>新增一条求职偏好后，工作台会自动读取并使用。</p>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { addMemory, clearMemories, deleteMemory, listMemories } from '../../api/settings'
import { useScopedSettings } from '../../composables/useScopedSettings'
import { validateInteger, validateLength } from '../../utils/formValidation'
import AppSwitch from '../AppSwitch.vue'

const emit = defineEmits(['state-change'])
const normalizeMemory = (value) => ({
  enabled: value?.enabled ?? true,
  autoSaveChat: value?.autoSaveChat ?? true,
  autoUseMemory: value?.autoUseMemory ?? true,
  maxItems: Number(value?.maxItems) || 200,
})
const {
  value: memory,
  loading,
  saving,
  error,
  dirty,
  load,
  save: saveMemory,
} = useScopedSettings('memory', normalizeMemory)
const memories = ref([])
const form = ref({ content: '' })
const memorySourceLabels = {
  manual: '手动添加',
  'agent-memory': '自动沉淀',
  chat: '对话沉淀',
}

watch(
  [dirty, saving, loading],
  () => emit('state-change', { key: 'memory', dirty: dirty.value, saving: saving.value, loading: loading.value }),
  { immediate: true },
)
onMounted(async () => {
  await load()
  try {
    memories.value = await listMemories()
  } catch (err) {
    error.value = err.message || '记忆加载失败'
  }
})
defineExpose({ save })

async function save() {
  error.value = ''
  try {
    validateInteger(memory.value.maxItems, '最大记忆条数', { min: 20, max: 1000 })
  } catch (err) {
    error.value = err.message
    return false
  }
  return saveMemory()
}

async function create() {
  try {
    validateLength(form.value.content, '记忆内容', { max: 2000, required: true })
    const item = await addMemory({ ...form.value, source: 'manual', enabled: true })
    const key = memoryKey(item)
    memories.value = [item, ...memories.value.filter((row) => memoryKey(row) !== key)]
    form.value.content = ''
  } catch (err) {
    error.value = err.message || '记忆保存失败'
  }
}
function memoryKey(item) {
  const content = String(item?.content || '')
    .toLowerCase()
    .replace(/[\s　]+/g, '')
    .replace(/，/g, ',')
    .replace(/。/g, '.')
  return content
}
function memorySourceLabel(source) {
  return (
    memorySourceLabels[
      String(source || '')
        .trim()
        .toLowerCase()
    ] || '系统记录'
  )
}
async function remove(memoryId) {
  try {
    await deleteMemory(memoryId)
    memories.value = memories.value.filter((item) => item.id !== memoryId)
  } catch (err) {
    error.value = err.message || '记忆删除失败'
  }
}
async function clearAll() {
  if (!window.confirm('确认清空长期记忆？')) return
  try {
    await clearMemories()
    memories.value = []
  } catch (err) {
    error.value = err.message || '记忆清空失败'
  }
}
function formatTime(value) {
  return value
    ? new Date(value).toLocaleString(undefined, {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      })
    : ''
}
</script>
