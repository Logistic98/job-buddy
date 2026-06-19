<template>
  <section class="system-page resume-manager-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Resume Manager</p>
        <h1>我的简历列表</h1>
        <p>集中管理已上传的简历文件，可上传、打标签、预览、下载、设为当前或删除。</p>
      </div>
      <div class="resume-manager-actions">
        <button class="secondary-btn" @click="showFolderModal = true">新建分组</button>
        <label class="primary-btn upload-entry">
          上传简历
          <input type="file" accept=".pdf,application/pdf" @change="pick" />
        </label>
      </div>
    </header>

    <p v-if="resume.error" class="error manager-error">{{ resume.error }}</p>

    <div class="resume-folder-bar">
      <button :class="{ active: activeFolder === '' }" @click="activeFolder = ''">全部 <span>{{ managedResumes.length }}</span></button>
      <button v-for="folder in folders" :key="folder" :class="{ active: activeFolder === folder }" @click="activeFolder = folder">{{ folder }} <span>{{ folderCount(folder) }}</span></button>
    </div>

    <div v-if="filteredResumes.length" class="resume-card-grid">
      <article v-for="item in filteredResumes" :key="item.resumeId" :class="['resume-manage-card', { active: resume.current?.resumeId === item.resumeId }]" @click="setCurrent(item)">
        <div class="resume-thumb" @click.stop="openPreview(item)">
          <img class="resume-thumb-image" :src="thumbnailUrl(item)" :alt="item.originalName" loading="lazy" decoding="async" />
        </div>
        <div class="resume-card-info">
          <div class="resume-card-title">
            <h2>{{ item.originalName }}</h2>
            <span v-if="resume.current?.resumeId === item.resumeId" class="state-badge ok">当前</span>
          </div>
          <p>更新于：{{ shortTime(item.uploadedAt) }}</p>
          <div class="resume-meta-row">
            <span>{{ folderOf(item) || '未分组' }}</span>
            <span>版本 {{ versionOf(item) }}</span>
          </div>
          <div class="resume-tags">
            <span v-for="tag in visibleTags(item)" :key="tag">{{ tag }}</span>
            <em v-if="hiddenTagCount(item)">+{{ hiddenTagCount(item) }}</em>
          </div>
          <div class="resume-card-actions" @click.stop>
            <button :disabled="!isPdf(item)" @click="openPreview(item)">预览</button>
            <a :href="downloadUrl(item)" :download="item.originalName">下载</a>
            <button v-if="isMarkdown(item)" class="resume-action-primary" @click="openInWriter(item)"><span class="resume-action-mark">✎</span>在撰写中打开</button>
            <button class="danger-text" @click="removeResume(item)">删除</button>
            <button class="resume-action-primary" @click="openMoveModal(item)"><span class="resume-action-mark">▣</span>分组</button>
            <button class="resume-action-primary" @click="openTagOnlyModal(item)"><span class="resume-action-mark">⌗</span>标签</button>
            <button class="resume-action-primary" @click="openVersionModal(item)"><span class="resume-action-mark">◇</span>版本</button>
          </div>
        </div>
      </article>
    </div>

    <div v-else class="empty-state manager-empty">
      <strong>暂无简历</strong>
      <p>点击右上角“上传简历”，添加 PDF 简历。</p>
    </div>

    <div v-if="tagModal.visible" class="modal-mask" @click.self="closeTagModal">
      <div class="modal-card resume-tag-modal">
        <button class="close" @click="closeTagModal">×</button>
        <div class="resume-tag-modal-head">
          <p class="eyebrow">Resume {{ tagModal.mode === 'version' ? 'Version' : 'Tags' }}</p>
          <h2>{{ tagModal.mode === 'version' ? '维护简历版本' : '维护简历标签' }}</h2>
          <span>{{ tagModal.item?.originalName || '当前简历' }}</span>
        </div>
        <div v-if="tagModal.mode === 'version'" class="resume-meta-form single">
          <label><span>版本号</span><input v-model.trim="versionDraft" placeholder="例如：20260602_001" /></label>
        </div>
        <template v-else>
          <label class="resume-tag-input">
            <span>新增标签</span>
            <input v-model="tagText" :placeholder="tagDraftPlaceholder" @keydown.enter.prevent="addTagDraft" />
            <small>最多 {{ MAX_TAGS }} 个标签，单个标签最多 {{ MAX_TAG_LENGTH }} 个字符；重复标签会自动忽略。</small>
          </label>
          <div class="resume-tag-preview">
            <strong>当前标签 {{ tagDrafts.length }}/{{ MAX_TAGS }}</strong>
            <template v-if="tagDrafts.length">
              <span v-for="tag in tagDrafts" :key="tag" class="editable-tag">{{ tag }}<button type="button" @click="removeTagDraft(tag)">×</button></span>
            </template>
            <em v-else>暂无标签</em>
          </div>
          <p v-if="tagError" class="resume-tag-error">{{ tagError }}</p>
          <div class="resume-tag-suggestions">
            <strong>常用标签</strong>
            <button v-for="tag in tagSuggestions" :key="tag" type="button" @click="appendTag(tag)">{{ tag }}</button>
          </div>
        </template>
        <div class="modal-actions resume-tag-actions">
          <button class="secondary-btn" @click="closeTagModal">取消</button>
          <button class="primary-btn" @click="tagModal.mode === 'version' ? saveVersionOnly() : saveTagsOnly()">保存{{ tagModal.mode === 'version' ? '版本' : '标签' }}</button>
        </div>
      </div>
    </div>

    <div v-if="deleteModal.visible" class="modal-mask" @click.self="closeDeleteModal">
      <div class="modal-card resume-delete-modal">
        <button class="close" @click="closeDeleteModal">×</button>
        <p class="eyebrow">Delete Resume</p>
        <h2>删除简历</h2>
        <p>确认删除简历「{{ deleteModal.item?.originalName || '当前简历' }}」？删除后不可恢复。</p>
        <div class="modal-actions resume-tag-actions">
          <button class="secondary-btn" @click="closeDeleteModal">取消</button>
          <button class="danger-btn" @click="confirmRemoveResume">确认删除</button>
        </div>
      </div>
    </div>

    <div v-if="moveModal.visible" class="modal-mask" @click.self="closeMoveModal">
      <div class="modal-card resume-folder-modal">
        <button class="close" @click="closeMoveModal">×</button>
        <p class="eyebrow">Move Resume</p>
        <h2>移动到分组</h2>
        <p>{{ moveModal.item?.originalName || '当前简历' }}</p>
        <label class="resume-tag-input">
          <span>目标分组</span>
          <select v-model="moveFolderDraft" class="resume-folder-select">
            <option value="">未分组</option>
            <option v-for="folder in folders" :key="folder" :value="folder">{{ folder }}</option>
          </select>
        </label>
        <div v-if="folders.length" class="resume-folder-quick">
          <button v-for="folder in folders" :key="folder" type="button" :class="{ active: moveFolderDraft === folder }" @click="moveFolderDraft = folder">{{ folder }}</button>
        </div>
        <div class="modal-actions resume-tag-actions">
          <button class="secondary-btn" @click="closeMoveModal">取消</button>
          <button class="primary-btn" @click="moveResumeToFolder">确认移动</button>
        </div>
      </div>
    </div>

    <div v-if="showFolderModal" class="modal-mask" @click.self="closeFolderModal">
      <div class="modal-card resume-folder-modal">
        <button class="close" @click="closeFolderModal">×</button>
        <p class="eyebrow">Resume Folder</p>
        <h2>新建简历分组</h2>
        <p>分组用于对简历进行分类管理。</p>
        <label class="resume-tag-input">
          <span>分组名称</span>
          <input v-model.trim="folderText" placeholder="请输入分组名称" @keydown.enter.prevent="createFolder" />
        </label>
        <p v-if="folderError" class="resume-tag-error">{{ folderError }}</p>
        <div class="modal-actions resume-tag-actions">
          <button class="secondary-btn" @click="closeFolderModal">取消</button>
          <button class="primary-btn" @click="createFolder">创建分组</button>
        </div>
      </div>
    </div>

    <div v-if="previewItem" class="modal-mask">
      <div class="pdf-preview-modal">
        <button class="close" @click="previewItem = null">×</button>
        <div class="pdf-preview-head">
          <div>
            <p class="eyebrow">Resume Preview</p>
            <h2>{{ previewItem.originalName }}</h2>
          </div>
          <a class="primary-link" :href="downloadUrl(previewItem)" :download="previewItem.originalName">下载</a>
        </div>
        <iframe v-if="isPdf(previewItem)" :src="previewUrl(previewItem)" title="简历预览"></iframe>
        <div v-else class="empty-state compact"><strong>当前文件格式暂不支持在线预览</strong><p>Markdown 简历请点击“在撰写中打开”继续编辑。</p></div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { resumeDownloadUrl, resumePreviewUrl, resumeThumbnailUrl } from '../api/resume'
import { useChatStore } from '../stores/chat'
import { useResumeStore } from '../stores/resume'

const emit = defineEmits(['open-writer'])
const chat = useChatStore()
const resume = useResumeStore()
const previewItem = ref(null)
const tagModal = ref({ visible: false, item: null, mode: 'tags' })
const tagText = ref('')
const tagDrafts = ref([])
const tagError = ref('')
const folderDraft = ref('')
const versionDraft = ref('')
const showFolderModal = ref(false)
const folderText = ref('')
const folderError = ref('')
const moveModal = ref({ visible: false, item: null })
const moveFolderDraft = ref('')
const deleteModal = ref({ visible: false, item: null })
const FOLDERS_KEY = 'job-buddy.resume-folders'
const MAX_TAGS = 6
const MAX_TAG_LENGTH = 12
const CARD_VISIBLE_TAGS = 5
const folders = ref(readFolders())
const activeFolder = ref('')
const managedResumes = computed(() => resume.items.filter(item => ['pdf', 'md', 'markdown'].includes(String(item.suffix || '').toLowerCase())))
const filteredResumes = computed(() => activeFolder.value ? managedResumes.value.filter(item => folderOf(item) === activeFolder.value) : managedResumes.value)
const tagDraftPlaceholder = computed(() => tagDrafts.value.length >= MAX_TAGS ? '标签数量已达上限' : '输入后回车添加，例如：Java后端')
const tagSuggestions = ['Java后端', 'Python', 'AI应用', '大模型', '上海', '远程', '3年经验', '5年经验', '重点简历']

function pick(event) {
  const file = event.target.files?.[0]
  if (file) resume.upload(file, chat.sessionId).catch(() => {})
  event.target.value = ''
}
function shortTime(value) { return value ? new Date(value).toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '未知时间' }
function previewUrl(item) { return item?.resumeId ? resumePreviewUrl(item.resumeId) : '#' }
function thumbnailUrl(item) { return item?.resumeId ? resumeThumbnailUrl(item.resumeId) : '#' }
function downloadUrl(item) { return item?.resumeId ? resumeDownloadUrl(item.resumeId) : '#' }
function isPdf(item) { return (item?.suffix || '').toLowerCase() === 'pdf' }
function isMarkdown(item) { return ['md', 'markdown'].includes(String(item?.suffix || '').toLowerCase()) }
function openPreview(item) { if (isPdf(item)) previewItem.value = item }
function openInWriter(item) {
  const parsed = item?.parsed || {}
  const content = String(parsed.markdown || parsed.raw_text || '').trim()
  if (!content) { alert('该 Markdown 简历没有可编辑内容。'); return }
  if (!confirm(`将在简历撰写中打开「${item.originalName || item.resumeId}」，并覆盖当前撰写草稿，是否继续？`)) return
  localStorage.setItem('job-buddy.resume-writer.markdown', content)
  localStorage.setItem('job-buddy.resume-writer.opened-resume-id', item.resumeId || '')
  emit('open-writer', item)
}
function setCurrent(item) { resume.select(item) }
function readFolders() {
  try {
    const rows = JSON.parse(localStorage.getItem(FOLDERS_KEY) || '[]')
    return Array.isArray(rows) ? rows.map(String).filter(Boolean) : []
  } catch (_) {
    return []
  }
}
function persistFolders() { localStorage.setItem(FOLDERS_KEY, JSON.stringify(folders.value)) }
function folderOf(item) { return String(item?.parsed?.folder || item?.parsed?.resumeFolder || '').trim() }
function folderCount(folder) { return managedResumes.value.filter(item => folderOf(item) === folder).length }
function versionOf(item) { return String(item?.parsed?.version || item?.parsed?.resumeVersion || defaultVersion(item)).trim() }
function defaultVersion(item) {
  const date = new Date(item?.uploadedAt || Date.now())
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const prefix = `${y}${m}${d}`
  const sameDay = managedResumes.value.filter(row => defaultVersionPrefix(row) === prefix).sort((a, b) => String(a.resumeId).localeCompare(String(b.resumeId)))
  const index = Math.max(1, sameDay.findIndex(row => row.resumeId === item?.resumeId) + 1)
  return `${prefix}_${String(index).padStart(3, '0')}`
}
function defaultVersionPrefix(item) {
  const date = new Date(item?.uploadedAt || Date.now())
  return `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, '0')}${String(date.getDate()).padStart(2, '0')}`
}
function tags(item) {
  const raw = item?.parsed?.labels || item?.parsed?.manageTags || []
  const rows = Array.isArray(raw) ? raw : String(raw || '').split(/[,，、\s]+/)
  return normalizeTags(rows)
}
function visibleTags(item) { return tags(item).slice(0, CARD_VISIBLE_TAGS) }
function hiddenTagCount(item) { return Math.max(0, tags(item).length - CARD_VISIBLE_TAGS) }
function parseTagText(value) {
  return String(value || '').split(/[,，、\n\r\t ]+/).map(v => v.trim()).filter(Boolean)
}
function normalizeTags(rows) {
  const seen = new Set()
  const result = []
  for (const raw of rows || []) {
    const tag = String(raw || '').trim().slice(0, MAX_TAG_LENGTH)
    const key = tag.toLowerCase()
    if (!tag || seen.has(key)) continue
    seen.add(key)
    result.push(tag)
    if (result.length >= MAX_TAGS) break
  }
  return result
}
function openTagOnlyModal(item) {
  tagModal.value = { visible: true, item, mode: 'tags' }
  tagDrafts.value = tags(item)
  versionDraft.value = ''
  tagText.value = ''
  tagError.value = ''
}
function openVersionModal(item) {
  tagModal.value = { visible: true, item, mode: 'version' }
  tagDrafts.value = []
  versionDraft.value = versionOf(item)
  tagText.value = ''
  tagError.value = ''
}
function closeTagModal() {
  tagModal.value = { visible: false, item: null, mode: 'tags' }
  tagDrafts.value = []
  folderDraft.value = ''
  versionDraft.value = ''
  tagText.value = ''
  tagError.value = ''
}
function addTagDraft() {
  tagError.value = ''
  const incoming = parseTagText(tagText.value)
  if (!incoming.length) return
  const before = tagDrafts.value.length
  const merged = normalizeTags([...tagDrafts.value, ...incoming])
  tagDrafts.value = merged
  tagText.value = ''
  if (before >= MAX_TAGS || incoming.length + before > merged.length) {
    tagError.value = merged.length >= MAX_TAGS ? `最多只能添加 ${MAX_TAGS} 个标签。` : '重复标签已自动忽略。'
  }
}
function removeTagDraft(tag) {
  tagDrafts.value = tagDrafts.value.filter(item => item !== tag)
  tagError.value = ''
}
function appendTag(tag) {
  tagText.value = tag
  addTagDraft()
}
async function saveTagsOnly() {
  addTagDraft()
  const item = tagModal.value.item
  if (!item?.resumeId) return
  const labels = normalizeTags(tagDrafts.value)
  const parsed = { ...(item.parsed || {}), labels, manageTags: labels }
  await resume.saveParsed(item.resumeId, parsed)
  closeTagModal()
}
async function saveVersionOnly() {
  const item = tagModal.value.item
  if (!item?.resumeId) return
  const version = versionDraft.value.trim() || defaultVersion(item)
  const parsed = { ...(item.parsed || {}), version, resumeVersion: version }
  await resume.saveParsed(item.resumeId, parsed)
  closeTagModal()
}
function closeFolderModal() {
  showFolderModal.value = false
  folderText.value = ''
  folderError.value = ''
}
function createFolder() {
  const value = folderText.value.trim()
  folderError.value = ''
  if (!value) { folderError.value = '请输入分组名称。'; return }
  if (folders.value.includes(value)) { folderError.value = '分组已存在。'; return }
  folders.value = [...folders.value, value]
  persistFolders()
  activeFolder.value = value
  closeFolderModal()
}
function openMoveModal(item) {
  moveModal.value = { visible: true, item }
  moveFolderDraft.value = folderOf(item)
}
function closeMoveModal() {
  moveModal.value = { visible: false, item: null }
  moveFolderDraft.value = ''
}
async function moveResumeToFolder() {
  const item = moveModal.value.item
  if (!item?.resumeId) return
  const folder = moveFolderDraft.value.trim()
  const parsed = { ...(item.parsed || {}), folder, resumeFolder: folder }
  await resume.saveParsed(item.resumeId, parsed)
  closeMoveModal()
}
function removeResume(item) {
  deleteModal.value = { visible: true, item }
}
function closeDeleteModal() {
  deleteModal.value = { visible: false, item: null }
}
async function confirmRemoveResume() {
  const item = deleteModal.value.item
  if (!item?.resumeId) return
  await resume.remove(item.resumeId)
  if (previewItem.value?.resumeId === item.resumeId) previewItem.value = null
  closeDeleteModal()
}

</script>
