<template>
  <section class="system-page resume-manager-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Resume Manager</p>
        <h1>我的简历列表</h1>
        <p>集中管理已上传的简历文件，可上传、打标签、预览、下载、设为当前或删除。</p>
      </div>
      <div class="resume-manager-actions">
        <button class="secondary-btn" @click="openFolderManager">分组维护</button>
        <label class="primary-btn upload-entry" :class="{ disabled: resume.uploading }">
          <span v-if="resume.uploading" class="resume-upload-spinner" aria-hidden="true"></span>
          {{ resume.uploading ? '上传中...' : '上传简历' }}
          <input type="file" accept=".pdf,application/pdf" :disabled="resume.uploading" @change="pick" />
        </label>
      </div>
    </header>

    <p v-if="resume.error" class="error manager-error">{{ resume.error }}</p>

    <div class="resume-folder-bar">
      <button :class="{ active: activeFolder === '' }" @click="activeFolder = ''">
        全部 <span>{{ managedResumes.length }}</span>
      </button>
      <button
        v-for="folder in folders"
        :key="folder"
        :class="{ active: activeFolder === folder }"
        @click="activeFolder = folder"
      >
        {{ folder }} <span>{{ folderCount(folder) }}</span>
      </button>
    </div>

    <div v-if="resume.loading && !filteredResumes.length" class="empty-state manager-empty">
      <span class="favorite-analysis-loading-mark" aria-hidden="true"></span>
      <strong>正在加载简历</strong>
      <p>请稍候。</p>
    </div>

    <div v-else-if="filteredResumes.length" class="resume-card-grid">
      <article
        v-for="item in filteredResumes"
        :key="item.resumeId"
        :class="['resume-manage-card', { active: resume.current?.resumeId === item.resumeId }]"
        @click="setCurrent(item)"
      >
        <div class="resume-thumb" @click.stop="openPreview(item)">
          <img
            class="resume-thumb-image"
            :src="thumbnailUrl(item)"
            :alt="item.originalName"
            loading="lazy"
            decoding="async"
          />
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
            <span v-for="tag in tags(item)" :key="tag">{{ tag }}</span>
          </div>
          <div class="resume-card-actions" @click.stop>
            <button class="resume-card-action" @click="openPreview(item)">预览</button>
            <a class="resume-card-action" :href="downloadUrl(item)" :download="item.originalName">下载</a>
            <button class="resume-card-action danger-text" @click="removeResume(item)">删除</button>
            <button class="resume-card-action" @click="openMoveModal(item)">分组</button>
            <button class="resume-card-action" @click="openTagOnlyModal(item)">标签</button>
            <button class="resume-card-action" @click="openVersionModal(item)">版本</button>
          </div>
        </div>
      </article>
    </div>

    <div v-else class="empty-state manager-empty">
      <strong>暂无简历</strong>
      <p>点击右上角“上传简历”，添加 PDF 简历。</p>
    </div>

    <Teleport to="body">
      <div v-if="tagModal.visible" class="modal-mask" @click.self="closeTagModal">
        <div class="modal-card resume-tag-modal">
          <button class="close" @click="closeTagModal">×</button>
          <div class="resume-tag-modal-head">
            <p class="eyebrow">Resume {{ tagModal.mode === 'version' ? 'Version' : 'Tags' }}</p>
            <h2>{{ tagModal.mode === 'version' ? '维护简历版本' : '维护简历标签' }}</h2>
            <span>{{ tagModal.item?.originalName || '当前简历' }}</span>
          </div>
          <div v-if="tagModal.mode === 'version'" class="resume-meta-form single">
            <label
              ><span>版本号</span><input v-model.trim="versionDraft" maxlength="64" placeholder="例如：20260602_001"
            /></label>
          </div>
          <template v-else>
            <div class="resume-tag-input">
              <label for="resume-tag-draft">新增标签</label>
              <div class="resume-tag-input-row">
                <input
                  id="resume-tag-draft"
                  v-model="tagText"
                  aria-describedby="resume-tag-help"
                  :disabled="tagDrafts.length >= MAX_TAGS"
                  :placeholder="tagDraftPlaceholder"
                  @keydown.enter.prevent="addTagDraft"
                />
                <button
                  type="button"
                  class="secondary-btn"
                  :disabled="!tagText.trim() || tagDrafts.length >= MAX_TAGS"
                  @click="addTagDraft"
                >
                  添加
                </button>
              </div>
              <small id="resume-tag-help"
                >可一次输入多个标签，用空格、逗号或顿号分隔；最多 {{ MAX_TAGS }} 个，单个最多
                {{ MAX_TAG_LENGTH }} 个字符。</small
              >
            </div>
            <div class="resume-tag-preview">
              <strong>当前标签 {{ tagDrafts.length }}/{{ MAX_TAGS }}</strong>
              <template v-if="tagDrafts.length">
                <span v-for="tag in tagDrafts" :key="tag" class="editable-tag"
                  >{{ tag }}<button type="button" @click="removeTagDraft(tag)">×</button></span
                >
              </template>
              <em v-else>暂无标签</em>
            </div>
            <p v-if="tagError" class="resume-tag-error">{{ tagError }}</p>
            <div class="resume-tag-suggestions">
              <strong>常用标签</strong>
              <button
                v-for="tag in tagSuggestions"
                :key="tag"
                type="button"
                :class="{ active: isTagSelected(tag) }"
                :aria-pressed="isTagSelected(tag)"
                :disabled="tagDrafts.length >= MAX_TAGS && !isTagSelected(tag)"
                @click="toggleTag(tag)"
              >
                {{ tag }}
              </button>
            </div>
          </template>
          <div class="modal-actions resume-tag-actions">
            <button class="secondary-btn" type="button" @click="closeTagModal">取消</button>
            <button
              class="primary-btn"
              type="button"
              :disabled="tagSaving"
              @click="tagModal.mode === 'version' ? saveVersionOnly() : saveTagsOnly()"
            >
              {{
                tagSaving ? '保存中...' : tagModal.mode === 'version' ? '保存版本' : `保存 ${tagDrafts.length} 个标签`
              }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="deleteModal.visible" class="modal-mask" @click.self="closeDeleteModal">
        <div
          class="modal-card resume-delete-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="resume-delete-title"
          aria-describedby="resume-delete-description"
        >
          <button class="close" type="button" aria-label="关闭删除确认框" @click="closeDeleteModal">×</button>
          <div class="resume-delete-heading">
            <span class="resume-delete-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none">
                <path d="M4 7h16M9 7V4h6v3m3 0-1 13H7L6 7m4 4v5m4-5v5" />
              </svg>
            </span>
            <div>
              <p class="eyebrow">Delete Resume</p>
              <h2 id="resume-delete-title">删除简历</h2>
            </div>
          </div>
          <div class="resume-delete-target">
            <span>即将删除</span>
            <strong :title="deleteModal.item?.originalName || '当前简历'">
              {{ deleteModal.item?.originalName || '当前简历' }}
            </strong>
          </div>
          <p id="resume-delete-description" class="resume-delete-description">删除后无法恢复，请确认是否继续。</p>
          <div class="modal-actions resume-delete-actions">
            <button class="secondary-btn" type="button" @click="closeDeleteModal">取消</button>
            <button class="danger-btn" type="button" @click="confirmRemoveResume">确认删除</button>
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
            <button
              v-for="folder in folders"
              :key="folder"
              type="button"
              :class="{ active: moveFolderDraft === folder }"
              @click="moveFolderDraft = folder"
            >
              {{ folder }}
            </button>
          </div>
          <div class="modal-actions resume-tag-actions">
            <button class="primary-btn" @click="moveResumeToFolder">确认移动</button>
          </div>
        </div>
      </div>

      <div v-if="showFolderModal" class="modal-mask" @click.self="closeFolderModal">
        <div
          class="modal-card resume-folder-modal resume-folder-manager-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="resume-folder-manager-title"
        >
          <button class="close" @click="closeFolderModal">×</button>
          <p class="eyebrow">Resume Folder</p>
          <h2 id="resume-folder-manager-title">简历分组维护</h2>
          <p>可以新建、重命名或删除分组。删除分组不会删除简历。</p>
          <div class="resume-folder-create">
            <label for="resume-folder-name" class="form-required">新建分组</label>
            <div class="resume-folder-create-row">
              <input
                id="resume-folder-name"
                v-model.trim="folderText"
                aria-required="true"
                maxlength="64"
                placeholder="请输入分组名称"
                :disabled="folderActionSaving"
                @keydown.enter.prevent="createFolder"
              />
              <button
                class="primary-btn"
                type="button"
                :disabled="folderActionSaving || !folderText.trim()"
                @click="createFolder"
              >
                新建
              </button>
            </div>
          </div>
          <p v-if="folderError" class="resume-tag-error form-error-alert" role="alert" aria-live="assertive">
            {{ folderError }}
          </p>
          <div v-if="folders.length" class="resume-folder-maintenance-list">
            <div v-for="folder in folders" :key="folder" class="resume-folder-maintenance-item">
              <template v-if="renamingFolder === folder">
                <input
                  v-model.trim="renameFolderText"
                  :aria-label="`重命名分组 ${folder}`"
                  maxlength="64"
                  :disabled="folderActionSaving"
                  @keydown.enter.prevent="confirmRenameFolder(folder)"
                  @keydown.esc.prevent="cancelRenameFolder"
                />
                <span>{{ folderCount(folder) }} 份简历</span>
                <button
                  type="button"
                  class="resume-folder-text-action"
                  :disabled="folderActionSaving"
                  @click="cancelRenameFolder"
                >
                  取消
                </button>
                <button
                  type="button"
                  class="resume-folder-text-action primary"
                  :disabled="folderActionSaving || !renameFolderText.trim()"
                  @click="confirmRenameFolder(folder)"
                >
                  保存
                </button>
              </template>
              <template v-else>
                <strong>{{ folder }}</strong>
                <span>{{ folderCount(folder) }} 份简历</span>
                <button
                  type="button"
                  class="resume-folder-text-action"
                  :disabled="folderActionSaving"
                  @click="startRenameFolder(folder)"
                >
                  重命名
                </button>
                <button
                  type="button"
                  class="resume-folder-text-action danger"
                  :disabled="folderActionSaving"
                  @click="requestDeleteFolder(folder)"
                >
                  删除
                </button>
              </template>
            </div>
          </div>
          <div v-else class="resume-folder-maintenance-empty">暂无分组，请先新建分组。</div>
        </div>
      </div>

      <div v-if="folderDeleteModal.visible" class="modal-mask" @click.self="closeFolderDeleteModal">
        <div
          class="modal-card resume-delete-modal"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="resume-folder-delete-title"
          aria-describedby="resume-folder-delete-description"
        >
          <button class="close" type="button" aria-label="关闭删除分组确认框" @click="closeFolderDeleteModal">×</button>
          <div class="resume-delete-heading">
            <span class="resume-delete-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none">
                <path d="M4 7h16M9 7V4h6v3m3 0-1 13H7L6 7m4 4v5m4-5v5" />
              </svg>
            </span>
            <div>
              <p class="eyebrow">Delete Folder</p>
              <h2 id="resume-folder-delete-title">删除分组</h2>
            </div>
          </div>
          <div class="resume-delete-target">
            <span>即将删除</span>
            <strong>{{ folderDeleteModal.folder }}</strong>
          </div>
          <p id="resume-folder-delete-description" class="resume-delete-description">
            组内 {{ folderCount(folderDeleteModal.folder) }} 份简历将移至“未分组”，简历文件不会被删除。
          </p>
          <p v-if="folderError" class="resume-tag-error form-error-alert" role="alert">{{ folderError }}</p>
          <div class="modal-actions resume-delete-actions">
            <button class="secondary-btn" type="button" :disabled="folderActionSaving" @click="closeFolderDeleteModal">
              取消
            </button>
            <button class="danger-btn" type="button" :disabled="folderActionSaving" @click="confirmDeleteFolder">
              {{ folderActionSaving ? '处理中...' : '确认删除' }}
            </button>
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
          <iframe :src="previewUrl(previewItem)" title="PDF 简历预览"></iframe>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { resumeDownloadUrl, resumePreviewUrl, resumeThumbnailUrl } from '../api/resume'
import { getWorkspaceState, saveWorkspaceState } from '../api/workspace'
import { useChatStore } from '../stores/chat'
import { useResumeStore } from '../stores/resume'
import { validateFile } from '../utils/formValidation'

const chat = useChatStore()
const resume = useResumeStore()
const previewItem = ref(null)
const tagModal = ref({ visible: false, item: null, mode: 'tags' })
const tagText = ref('')
const tagDrafts = ref([])
const tagError = ref('')
const tagSaving = ref(false)
const folderDraft = ref('')
const versionDraft = ref('')
const showFolderModal = ref(false)
const folderText = ref('')
const folderError = ref('')
const folderActionSaving = ref(false)
const renamingFolder = ref('')
const renameFolderText = ref('')
const folderDeleteModal = ref({ visible: false, folder: '' })
const moveModal = ref({ visible: false, item: null })
const moveFolderDraft = ref('')
const deleteModal = ref({ visible: false, item: null })
const FOLDERS_STATE_KEY = 'resume.folders'
const MAX_TAGS = 6
const MAX_TAG_LENGTH = 12
const folders = ref([])
const activeFolder = ref('')
const managedResumes = computed(() => resume.items.filter((item) => String(item.suffix || '').toLowerCase() === 'pdf'))
const filteredResumes = computed(() =>
  activeFolder.value
    ? managedResumes.value.filter((item) => folderOf(item) === activeFolder.value)
    : managedResumes.value,
)
const tagDraftPlaceholder = computed(() =>
  tagDrafts.value.length >= MAX_TAGS ? '标签数量已达上限' : '输入后回车添加，例如：Agent工程',
)
const tagSuggestions = [
  '后端',
  'Agent',
  'RAG',
  '大数据处理',
  'AI工程化',
  'AI原生',
  'AI算法',
  'Harness',
  'LLM',
  '模型训练',
  '基础设施',
]

function pick(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  try {
    validateFile(file, 'PDF 简历', {
      extensions: ['pdf'],
      mimeTypes: ['application/pdf'],
      maxBytes: 20 * 1024 * 1024,
    })
    resume.upload(file, chat.sessionId).catch(() => {})
  } catch (err) {
    resume.error = err.message
  }
}
function shortTime(value) {
  return value
    ? new Date(value).toLocaleString(undefined, {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '未知时间'
}
function previewUrl(item) {
  return item?.resumeId ? resumePreviewUrl(item.resumeId) : '#'
}
function thumbnailUrl(item) {
  return item?.resumeId ? resumeThumbnailUrl(item.resumeId) : '#'
}
function downloadUrl(item) {
  return item?.resumeId ? resumeDownloadUrl(item.resumeId) : '#'
}
function openPreview(item) {
  previewItem.value = item
}
function setCurrent(item) {
  resume.select(item)
}
async function loadFolders() {
  const state = await getWorkspaceState(FOLDERS_STATE_KEY)
  folders.value = Array.isArray(state.folders) ? state.folders.map(String).filter(Boolean) : []
}
async function persistFolders(nextFolders) {
  await saveWorkspaceState(FOLDERS_STATE_KEY, { folders: nextFolders })
  folders.value = nextFolders
}
onMounted(() => {
  resume.load().catch(() => {})
  loadFolders().catch((error) => {
    folderError.value = error?.message || '分组加载失败'
  })
})
function folderOf(item) {
  return String(item?.parsed?.folder || item?.parsed?.resumeFolder || '').trim()
}
function folderCount(folder) {
  return managedResumes.value.filter((item) => folderOf(item) === folder).length
}
function versionOf(item) {
  return String(item?.parsed?.version || item?.parsed?.resumeVersion || defaultVersion(item)).trim()
}
function defaultVersion(item) {
  const date = new Date(item?.uploadedAt || Date.now())
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const prefix = `${y}${m}${d}`
  const sameDay = managedResumes.value
    .filter((row) => defaultVersionPrefix(row) === prefix)
    .sort((a, b) => String(a.resumeId).localeCompare(String(b.resumeId)))
  const index = Math.max(1, sameDay.findIndex((row) => row.resumeId === item?.resumeId) + 1)
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
function parseTagText(value) {
  return String(value || '')
    .split(/[,，、\n\r\t ]+/)
    .map((v) => v.trim())
    .filter(Boolean)
}
function normalizeTags(rows) {
  const seen = new Set()
  const result = []
  for (const raw of rows || []) {
    const tag = String(raw || '')
      .trim()
      .slice(0, MAX_TAG_LENGTH)
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
  if (tagSaving.value) return
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
  tagDrafts.value = tagDrafts.value.filter((item) => item !== tag)
  tagError.value = ''
}
function appendTag(tag) {
  tagText.value = tag
  addTagDraft()
}
function isTagSelected(tag) {
  const key = String(tag || '').toLowerCase()
  return tagDrafts.value.some((item) => item.toLowerCase() === key)
}
function toggleTag(tag) {
  if (isTagSelected(tag)) {
    const key = tag.toLowerCase()
    tagDrafts.value = tagDrafts.value.filter((item) => item.toLowerCase() !== key)
    tagError.value = ''
    return
  }
  appendTag(tag)
}
async function saveTagsOnly() {
  addTagDraft()
  const item = tagModal.value.item
  if (!item?.resumeId) return
  const labels = normalizeTags(tagDrafts.value)
  const parsed = { ...(item.parsed || {}), labels, manageTags: labels }
  tagSaving.value = true
  try {
    await resume.saveParsed(item.resumeId, parsed)
    tagSaving.value = false
    closeTagModal()
  } catch (error) {
    tagSaving.value = false
    tagError.value = error?.message || '标签保存失败，请重试。'
  }
}
async function saveVersionOnly() {
  const item = tagModal.value.item
  if (!item?.resumeId) return
  const version = versionDraft.value.trim() || defaultVersion(item)
  const parsed = { ...(item.parsed || {}), version, resumeVersion: version }
  tagSaving.value = true
  try {
    await resume.saveParsed(item.resumeId, parsed)
    tagSaving.value = false
    closeTagModal()
  } catch (error) {
    tagSaving.value = false
    tagError.value = error?.message || '版本保存失败，请重试。'
  }
}
function closeFolderModal() {
  if (folderActionSaving.value) return
  showFolderModal.value = false
  folderText.value = ''
  folderError.value = ''
  renamingFolder.value = ''
  renameFolderText.value = ''
}
function openFolderManager() {
  folderError.value = ''
  showFolderModal.value = true
}
async function createFolder() {
  const value = folderText.value.trim()
  folderError.value = ''
  if (!value) {
    folderError.value = '请输入分组名称。'
    return
  }
  if (folders.value.includes(value)) {
    folderError.value = '分组已存在。'
    return
  }
  folderActionSaving.value = true
  try {
    await persistFolders([...folders.value, value])
    folderText.value = ''
    activeFolder.value = value
  } catch (error) {
    folderError.value = error?.message || '分组创建失败，请重试。'
  } finally {
    folderActionSaving.value = false
  }
}
function startRenameFolder(folder) {
  folderError.value = ''
  renamingFolder.value = folder
  renameFolderText.value = folder
}
function cancelRenameFolder() {
  renamingFolder.value = ''
  renameFolderText.value = ''
  folderError.value = ''
}
async function confirmRenameFolder(folder) {
  const nextName = renameFolderText.value.trim()
  folderError.value = ''
  if (!nextName) {
    folderError.value = '请输入分组名称。'
    return
  }
  if (nextName !== folder && folders.value.includes(nextName)) {
    folderError.value = '分组已存在。'
    return
  }
  if (nextName === folder) {
    cancelRenameFolder()
    return
  }
  folderActionSaving.value = true
  try {
    const affectedResumes = managedResumes.value.filter((item) => folderOf(item) === folder)
    await Promise.all(
      affectedResumes.map((item) =>
        resume.saveParsed(item.resumeId, {
          ...(item.parsed || {}),
          folder: nextName,
          resumeFolder: nextName,
        }),
      ),
    )
    await persistFolders(folders.value.map((item) => (item === folder ? nextName : item)))
    if (activeFolder.value === folder) activeFolder.value = nextName
    cancelRenameFolder()
  } catch (error) {
    folderError.value = error?.message || '分组重命名失败，请重试。'
  } finally {
    folderActionSaving.value = false
  }
}
function requestDeleteFolder(folder) {
  folderError.value = ''
  folderDeleteModal.value = { visible: true, folder }
}
function closeFolderDeleteModal() {
  if (folderActionSaving.value) return
  folderDeleteModal.value = { visible: false, folder: '' }
  folderError.value = ''
}
async function confirmDeleteFolder() {
  const folder = folderDeleteModal.value.folder
  if (!folder) return
  folderError.value = ''
  folderActionSaving.value = true
  try {
    const affectedResumes = managedResumes.value.filter((item) => folderOf(item) === folder)
    await Promise.all(
      affectedResumes.map((item) =>
        resume.saveParsed(item.resumeId, {
          ...(item.parsed || {}),
          folder: '',
          resumeFolder: '',
        }),
      ),
    )
    await persistFolders(folders.value.filter((item) => item !== folder))
    if (activeFolder.value === folder) activeFolder.value = ''
    folderDeleteModal.value = { visible: false, folder: '' }
    renamingFolder.value = ''
    renameFolderText.value = ''
  } catch (error) {
    folderError.value = error?.message || '分组删除失败，请重试。'
  } finally {
    folderActionSaving.value = false
  }
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

<style scoped>
.resume-manager-actions .upload-entry {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-width: 110px;
}

.resume-manager-actions .upload-entry.disabled {
  cursor: not-allowed;
  opacity: 0.75;
  pointer-events: none;
}

.resume-upload-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.45);
  border-top-color: #fff;
  border-radius: 50%;
  display: inline-block;
  animation: resume-spin 0.7s linear infinite;
}

@keyframes resume-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
