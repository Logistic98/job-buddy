<template>
  <aside class="chat-history-panel glass-card">
    <div class="history-mini-head">
      <div>
        <p class="eyebrow">History</p>
        <h2>历史记录</h2>
      </div>
      <div class="history-mini-actions">
        <button class="secondary-btn mini history-search-trigger" @click="openSearch">搜索</button>
        <button class="primary-btn mini" @click="$emit('new-chat')">+ 新建</button>
      </div>
    </div>

    <div class="history-mini-list">
      <article
        v-for="item in chat.sessions"
        :key="item.sessionId"
        :class="['history-row compact-row', { active: item.sessionId === chat.sessionId }]"
        @click="open(item.sessionId)"
      >
        <span class="session-dot"></span>
        <div class="history-row-main">
          <strong>{{ item.title || '新会话' }}</strong>
          <small>{{ shortTime(item.updatedAt) }}</small>
        </div>
        <button class="row-delete" title="删除该会话" @click.stop="askRemove(item)">删除</button>
      </article>

      <div v-if="!chat.sessions.length" class="empty-state compact">
        <strong>暂无历史会话</strong>
        <p>发起对话后会自动保存。</p>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="searchVisible" class="modal-mask history-search-mask" @click.self="closeSearch">
        <div class="history-search-modal">
          <div class="history-search-modal-head">
            <input ref="searchInput" v-model.trim="keyword" placeholder="搜索聊天" @keydown.esc="closeSearch" />
            <button class="close" @click="closeSearch">×</button>
          </div>
          <div class="history-search-modal-body">
            <button class="history-search-new" @click="$emit('new-chat'); closeSearch()">新聊天</button>
            <p class="history-search-group">历史记录</p>
            <article
              v-for="item in filteredSessions"
              :key="item.sessionId"
              class="history-search-result"
              @click="openFromSearch(item.sessionId)"
            >
              <span class="session-dot"></span>
              <div>
                <strong>{{ item.title || '新会话' }}</strong>
                <small>{{ shortTime(item.updatedAt) }}</small>
              </div>
            </article>
            <div v-if="!filteredSessions.length" class="empty-state compact">
              <strong>{{ keyword ? '没有匹配的会话' : '暂无历史会话' }}</strong>
              <p>{{ keyword ? '换一个关键词试试。' : '发起对话后会自动保存。' }}</p>
            </div>
          </div>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div v-if="deleteTarget" class="modal-mask history-delete-mask" @click.self="cancelRemove">
        <div class="history-delete-modal">
          <button class="close" @click="cancelRemove">×</button>
          <p class="eyebrow">Delete Chat</p>
          <h2>删除这个会话？</h2>
          <p>确认删除「{{ deleteTarget.title || '新会话' }}」？</p>
          <div class="history-delete-actions">
            <button class="secondary-btn" :disabled="deleting" @click="cancelRemove">取消</button>
            <button class="danger-btn" :disabled="deleting" @click="confirmRemove">{{ deleting ? '删除中' : '确认删除' }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </aside>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useChatStore } from '../stores/chat'

const emit = defineEmits(['open-chat', 'new-chat'])
const chat = useChatStore()
const keyword = ref('')
const searchVisible = ref(false)
const searchInput = ref(null)
const deleteTarget = ref(null)
const deleting = ref(false)

onMounted(() => refresh())

const filteredSessions = computed(() => {
  const q = keyword.value.toLowerCase()
  const rows = chat.sessions || []
  if (!q) return rows
  return rows.filter(item => [item.title, item.sessionId, shortTime(item.updatedAt), fullTime(item.updatedAt)]
    .some(value => String(value || '').toLowerCase().includes(q)))
})

function refresh() { chat.loadSessions().catch(() => {}) }
function openSearch() {
  keyword.value = ''
  searchVisible.value = true
  nextTick(() => searchInput.value?.focus())
}
function closeSearch() { searchVisible.value = false }
function open(sessionId) {
  emit('open-chat', sessionId)
  chat.openSession(sessionId).catch(() => {})
}
function openFromSearch(sessionId) {
  open(sessionId)
  closeSearch()
}
function askRemove(item) {
  if (!item?.sessionId) return
  deleteTarget.value = item
}
function cancelRemove() {
  if (deleting.value) return
  deleteTarget.value = null
}
async function confirmRemove() {
  const sessionId = deleteTarget.value?.sessionId
  if (!sessionId) return
  deleting.value = true
  try {
    await chat.removeSession(sessionId)
    deleteTarget.value = null
  } finally {
    deleting.value = false
  }
}
function shortTime(value) {
  return value ? new Date(value).toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '未知时间'
}
function fullTime(value) { return value ? new Date(value).toLocaleString() : '未知时间' }
</script>
