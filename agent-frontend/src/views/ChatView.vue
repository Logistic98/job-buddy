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
    <div class="modal-card resume-modal-card resume-picker-modal">
      <button class="close" @click="showResumeModal = false">×</button>
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
      <label class="resume-picker-search">
        <span>检索简历</span>
        <input v-model.trim="resumeKeyword" placeholder="按文件名、姓名、摘要、技能搜索" />
      </label>
      <div v-if="resume.loading" class="empty-state compact"><strong>正在加载简历</strong><p>请稍候。</p></div>
      <div v-else-if="!resume.items.length" class="empty-state compact">
        <strong>暂无简历</strong>
        <p>可以直接在这里上传简历，不必跳转到简历管理页面。</p>
        <label class="primary-btn upload-resume-btn inline-upload">
          <input type="file" accept=".pdf,application/pdf" @change="uploadResumeFromPicker" />
          {{ resume.uploading ? '上传中' : '上传简历' }}
        </label>
      </div>
      <div v-else-if="!filteredResumes.length" class="empty-state compact"><strong>没有匹配的简历</strong><p>请更换关键词再搜索。</p></div>
      <div v-else class="resume-picker-list">
        <article v-for="item in filteredResumes" :key="item.resumeId" :class="['resume-picker-item', { active: resume.current?.resumeId === item.resumeId }]">
          <div class="resume-picker-thumb">
            <img v-if="item.resumeId" :src="resumeThumbUrl(item)" :alt="item.originalName || '简历缩略图'" loading="lazy" decoding="async" />
            <span v-else>{{ String(item.suffix || 'CV').toUpperCase() }}</span>
          </div>
          <div class="resume-picker-main">
            <strong>{{ resumeTitle(item) }}</strong>
            <span>{{ item.originalName || item.resumeId }}</span>
            <p>{{ resumeSummary(item) }}</p>
            <div class="skill-list compact"><span v-for="s in skills(item).slice(0, 8)" :key="s">{{ s }}</span></div>
          </div>
          <div class="resume-picker-actions">
            <span v-if="resume.current?.resumeId === item.resumeId" class="state-badge ok">当前使用</span>
            <button class="primary-btn" @click="selectResumeForChat(item)">{{ resume.current?.resumeId === item.resumeId ? '继续使用' : '选择这份' }}</button>
          </div>
        </article>
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

const router = useRouter()
const chat = useChatStore()
const resume = useResumeStore()
const chatPanel = ref(null)
const showResumeModal = ref(false)
const resumeKeyword = ref('')

const currentResumeName = computed(() => resume.current ? resumeTitle(resume.current) : '')
const filteredResumes = computed(() => {
  const q = resumeKeyword.value.trim().toLowerCase()
  if (!q) return resume.items
  return resume.items.filter(item => resumeSearchText(item).includes(q))
})

function ask(text) { chatPanel.value?.submitPrompt(text) }
function goChat() {}
function startNewChat() {
  chat.newSession()
}
function openResumePicker() {
  resumeKeyword.value = ''
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
  await resume.upload(file, chat.sessionId).catch(() => {})
  resumeKeyword.value = ''
}
function selectResumeForChat(item) {
  resume.select(item)
  showResumeModal.value = false
}
function resumeTitle(item) {
  return item?.parsed?.name || item?.parsed?.basic_info?.name || item?.parsed?.basicInfo?.name || item?.originalName || '未命名简历'
}
function resumeSummary(item) {
  return item?.parsed?.summary || item?.parsed?.personal_advantage || item?.parsed?.personalAdvantage || '暂无摘要'
}
function resumeThumbUrl(item) {
  return item?.resumeId ? resumeThumbnailUrl(item.resumeId) : ''
}
function skills(item) {
  const raw = item?.parsed?.skills || item?.parsed?.skill_tags || item?.parsed?.skillTags || []
  return Array.isArray(raw) ? raw : String(raw).split(/[,，、\s]+/).filter(Boolean)
}
function resumeSearchText(item) {
  return [
    resumeTitle(item), item?.originalName, item?.suffix, resumeSummary(item), ...skills(item),
    item?.parsed?.expected_titles, item?.parsed?.job_intentions,
  ].filter(Boolean).join(' ').toLowerCase()
}
</script>
