<template>
  <section class="workspace-view with-history main-only">
    <ChatHistorySidebar @new-chat="startNewChat" @open-chat="goChat" />
    <div class="conversation-column main-workbench">
      <ChatPanel
        ref="chatPanel"
        :resume-id="resume.current?.resumeId"
        :resume-name="currentResumeName"
        @ask="ask"
        @select-resume="openResumePicker"
      />
    </div>
  </section>

  <div v-if="showResumeModal" class="modal-mask">
    <div class="modal-card resume-modal-card resume-picker-modal chat-resume-picker-modal">
      <button class="close" aria-label="关闭" @click="showResumeModal = false">×</button>
      <div class="resume-picker-head">
        <div>
          <p class="eyebrow">Resume Context</p>
          <h2>选择会话简历</h2>
          <p>选中的简历会直接关联到当前会话框，后续岗位推荐、匹配和问答都会带上这份简历上下文。</p>
        </div>
        <div class="resume-picker-head-actions">
          <label class="primary-btn upload-resume-btn">
            <input type="file" accept=".pdf,application/pdf" @change="uploadResumeFromPicker" />
            {{ resume.uploading ? '上传中' : '上传简历' }}
          </label>
          <button class="secondary-btn" @click="goManageResumes">管理简历</button>
        </div>
      </div>
      <div class="chat-resume-picker-scroll">
        <div v-if="resume.loading" class="empty-state compact">
          <strong>正在加载简历</strong>
          <p>请稍候。</p>
        </div>
        <div v-else-if="!resume.items.length" class="empty-state compact">
          <strong>暂无简历</strong>
          <p>可点击上方“上传简历”按钮添加简历，不必跳转到简历管理页面。</p>
        </div>
        <div v-else class="resume-picker-list">
          <article
            v-for="item in resume.items"
            :key="item.resumeId"
            :class="['resume-picker-item', { active: resume.current?.resumeId === item.resumeId }]"
          >
            <div class="resume-picker-thumb">
              <img
                v-if="item.resumeId"
                :src="resumeThumbUrl(item)"
                :alt="item.originalName || '简历缩略图'"
                loading="lazy"
                decoding="async"
              />
              <span v-else>{{ String(item.suffix || 'CV').toUpperCase() }}</span>
            </div>
            <div class="resume-picker-content">
              <div class="resume-picker-main">
                <strong :title="resumeTitle(item)">{{ resumeTitle(item) }}</strong>
              </div>
              <div class="resume-picker-meta">
                <span>{{ resumeFolder(item) }}</span>
                <span>{{ resumeVersion(item) }}</span>
                <time :datetime="item.uploadedAt || ''">上传于 {{ formatUploadTime(item.uploadedAt) }}</time>
              </div>
              <div class="resume-picker-tags">
                <span v-for="tag in resumeTags(item).slice(0, 3)" :key="tag">{{ tag }}</span>
                <em v-if="resumeTags(item).length > 3">+{{ resumeTags(item).length - 3 }}</em>
                <small v-if="!resumeTags(item).length">暂无标签</small>
              </div>
              <div class="resume-picker-actions">
                <span v-if="resume.current?.resumeId === item.resumeId" class="state-badge ok">当前使用</span>
                <button class="primary-btn" @click="selectResumeForChat(item)">
                  {{ resume.current?.resumeId === item.resumeId ? '继续使用' : '选择这份' }}
                </button>
              </div>
            </div>
          </article>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import ChatHistorySidebar from '../components/ChatHistorySidebar.vue'
import ChatPanel from '../components/ChatPanel.vue'
import { resumeThumbnailUrl } from '../api/resume'
import { useChatStore } from '../stores/chat'
import { useResumeStore } from '../stores/resume'
import { validateFile } from '../utils/formValidation'
import { resumePickerTitle } from '../utils/resumePicker'

const router = useRouter()
const chat = useChatStore()
const resume = useResumeStore()
const chatPanel = ref(null)
const showResumeModal = ref(false)

const currentResumeName = computed(() => (resume.current ? resumeTitle(resume.current) : ''))

function ask(text) {
  chatPanel.value?.submitPrompt(text)
}
function goChat() {}
function startNewChat() {
  chat.newSession()
}
function openResumePicker() {
  showResumeModal.value = true
  resume.load().catch(() => {})
}
function goManageResumes() {
  showResumeModal.value = false
  router.push('/resumes')
}
async function uploadResumeFromPicker(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || resume.uploading) return
  try {
    validateFile(file, 'PDF 简历', {
      extensions: ['pdf'],
      mimeTypes: ['application/pdf'],
      maxBytes: 20 * 1024 * 1024,
    })
  } catch (err) {
    resume.error = err.message
    return
  }
  await resume.upload(file, chat.sessionId).catch(() => {})
}
function selectResumeForChat(item) {
  resume.select(item)
  showResumeModal.value = false
}
function resumeTitle(item) {
  return resumePickerTitle(item)
}
function resumeThumbUrl(item) {
  return item?.resumeId ? resumeThumbnailUrl(item.resumeId) : ''
}
function resumeFolder(item) {
  return String(item?.parsed?.folder || item?.parsed?.resumeFolder || '').trim() || '未分组'
}
function resumeVersion(item) {
  return `版本 ${String(item?.parsed?.version || item?.parsed?.resumeVersion || '初始版本').trim()}`
}
function resumeTags(item) {
  const raw = item?.parsed?.labels || item?.parsed?.manageTags || []
  const rows = Array.isArray(raw) ? raw : String(raw || '').split(/[,，、\s]+/)
  return Array.from(new Set(rows.map((tag) => String(tag?.label || tag?.name || tag || '').trim()).filter(Boolean)))
}
function formatUploadTime(value) {
  if (!value) return '未知时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '未知时间'
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}
</script>
