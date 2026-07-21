<template>
  <section class="resume-writer-clean">
    <header class="resume-clean-topbar">
      <nav class="resume-clean-menu">
        <button class="import-md-btn" @click="importClick">导入</button>
        <button class="load-example-btn" :disabled="loadingExample" @click="loadExampleResume">
          {{ loadingExample ? '加载中' : '加载示例' }}
        </button>
        <button :disabled="savingVersion" @click="openSaveVersionDialog">
          {{ savingVersion ? '保存中' : '保存版本' }}
        </button>
        <button @click="openVersionHistory">版本历史</button>
        <button @click="showPhotoModal = true">照片维护</button>
        <button @click="showIconModal = true">图标列表</button>
        <button @click="cycleTheme">主题：{{ themeName }}</button>
      </nav>
      <div class="resume-export-actions">
        <span class="export-label">导出</span>
        <button class="primary-btn" :disabled="exportingPdf" @click="exportPdf">
          {{ exportingPdf ? '生成中' : 'PDF' }}
        </button>
        <button class="secondary-btn" @click="exportMarkdown">MD</button>
        <button class="secondary-btn" @click="exportHtml">HTML</button>
      </div>
      <input ref="fileInput" type="file" accept=".md,.markdown,.txt" hidden @change="importMarkdown" />
      <input ref="photoInput" type="file" accept="image/*" multiple hidden @change="importPhoto" />
    </header>

    <div class="resume-clean-toolbar">
      <label class="resume-filename-field" title="导出 PDF、MD、HTML 时使用该文件名">
        <span>文件名</span>
        <input v-model="fileName" placeholder="请输入导出文件名" @input="saveFileName" @blur="normalizeFileName" />
      </label>
      <div class="resume-view-switch">
        <button :class="{ active: editorMode === 'source' }" @click="editorMode = 'source'">源码模式</button>
        <button :class="{ active: editorMode === 'split' }" @click="editorMode = 'split'">左右分屏</button>
        <button :class="{ active: editorMode === 'preview' }" @click="editorMode = 'preview'">预览模式</button>
      </div>
      <div class="font-control-group">
        <span>字体</span>
        <select v-model="fontFamily">
          <option value="PingFang SC, Microsoft YaHei, Arial, sans-serif">苹果方正_Medium</option>
          <option value="Songti SC, SimSun, serif">宋体 / SimSun</option>
          <option value="Kaiti SC, KaiTi, serif">楷体 / KaiTi</option>
          <option value="Arial, Helvetica, sans-serif">Arial</option>
          <option value="Georgia, Times New Roman, serif">Georgia</option>
          <option value="Menlo, Consolas, monospace">Menlo / Consolas</option>
        </select>
      </div>
      <div class="font-control-group compact-select-group">
        <span>字号</span>
        <div class="editable-select" @mousedown.stop>
          <input
            v-model.trim="fontSize"
            aria-label="字号"
            aria-haspopup="listbox"
            :aria-expanded="activeCombo === 'fontSize'"
            placeholder="12.5px"
            :title="`当前字号：${currentFontSizeLabel}，可下拉选择也可手输，例如 13 或 13px`"
            @focus="openCombo('fontSize')"
            @input="openCombo('fontSize')"
            @blur="handleComboBlur('fontSize')"
            @keydown.enter.prevent="handleComboEnter('fontSize')"
            @keydown.esc.prevent="closeCombo()"
          />
          <button
            type="button"
            class="editable-select-arrow"
            aria-label="展开字号选项"
            tabindex="-1"
            @mousedown.prevent="toggleCombo('fontSize')"
          ></button>
          <div v-if="activeCombo === 'fontSize'" class="editable-select-menu" role="listbox" aria-label="字号选项">
            <button
              v-for="item in fontSizeOptions"
              :key="item.value"
              type="button"
              role="option"
              :aria-selected="item.value === fontSize"
              :class="{ active: item.value === fontSize }"
              @mousedown.prevent="selectFontSizeOption(item.value)"
            >
              {{ item.label }}
            </button>
          </div>
        </div>
      </div>
      <div class="font-control-group compact-select-group line-control-group">
        <span>行距</span>
        <div class="editable-select" @mousedown.stop>
          <input
            v-model.trim="lineHeight"
            aria-label="行距"
            aria-haspopup="listbox"
            :aria-expanded="activeCombo === 'lineHeight'"
            placeholder="1.72"
            :title="`当前行距：${currentLineHeightLabel}，可下拉选择也可手输，例如 1.6 或 1.85`"
            @focus="openCombo('lineHeight')"
            @input="openCombo('lineHeight')"
            @blur="handleComboBlur('lineHeight')"
            @keydown.enter.prevent="handleComboEnter('lineHeight')"
            @keydown.esc.prevent="closeCombo()"
          />
          <button
            type="button"
            class="editable-select-arrow"
            aria-label="展开行距选项"
            tabindex="-1"
            @mousedown.prevent="toggleCombo('lineHeight')"
          ></button>
          <div v-if="activeCombo === 'lineHeight'" class="editable-select-menu" role="listbox" aria-label="行距选项">
            <button
              v-for="item in lineHeightOptions"
              :key="item.value"
              type="button"
              role="option"
              :aria-selected="item.value === lineHeight"
              :class="{ active: item.value === lineHeight }"
              @mousedown.prevent="selectLineHeightOption(item.value)"
            >
              {{ item.label }}
            </button>
          </div>
        </div>
      </div>
      <div v-if="selectedPhoto" class="photo-adjust-group">
        <span>照片缩放</span>
        <input
          type="range"
          min="0.4"
          max="3"
          step="0.05"
          :value="photoSettings.scale"
          @input="setPhotoScale($event.target.value)"
        />
        <b>{{ Math.round(photoSettings.scale * 100) }}%</b>
        <button type="button" @click="resetPhotoAdjust">重置照片</button>
      </div>
      <span class="save-state">{{ savedAt ? `已保存 ${savedAt}` : '服务端自动保存' }}</span>
    </div>

    <main :class="['resume-clean-workbench', `mode-${editorMode}`]">
      <section v-show="editorMode !== 'preview'" class="resume-clean-editor">
        <header class="resume-pane-head">
          <div><span class="resume-pane-dot editor-dot"></span><strong>Markdown 编辑</strong></div>
          <small>{{ markdown.length.toLocaleString() }} 字符</small>
        </header>
        <textarea
          ref="textareaRef"
          v-model="markdown"
          spellcheck="false"
          placeholder="请输入或导入 Markdown 简历内容"
          @input="saveDraft"
        />
      </section>
      <section v-show="editorMode !== 'source'" class="resume-clean-preview">
        <header class="resume-pane-head">
          <div><span class="resume-pane-dot preview-dot"></span><strong>PDF / A4 预览</strong></div>
          <small>{{ pages.length }} 页</small>
        </header>
        <div
          ref="previewCanvas"
          class="resume-preview-canvas"
          @pointerdown="handlePreviewPointerDown"
          @mousedown="handlePreviewPointerDown"
          @click="handlePreviewClick"
        >
          <div id="resume-print-root" class="resume-pages" :style="pagesScaleStyle">
            <!-- page 源自 sanitizeResumeHtml 清洗后的简历 HTML，已剥离脚本与事件处理器。 -->
            <!-- eslint-disable vue/no-v-html -->
            <article
              v-for="(page, idx) in pages"
              :key="idx"
              :class="paperClasses"
              :style="paperStyle"
              class="resume-page"
              v-html="page"
            ></article>
            <!-- eslint-enable vue/no-v-html -->
          </div>
        </div>
      </section>
      <!-- 测量节点复用同一份已清洗 HTML，仅用于分页高度计算。 -->
      <!-- eslint-disable vue/no-v-html -->
      <article
        ref="measureRef"
        :class="paperClasses"
        :style="paperStyle"
        class="resume-paper resume-measure"
        v-html="html"
      ></article>
      <!-- eslint-enable vue/no-v-html -->
    </main>

    <ResumePhotoModal
      v-if="showPhotoModal"
      :photo-url="photoUrl"
      :photos="displayPhotoLibrary"
      :uploading="photoUploading"
      @close="showPhotoModal = false"
      @pick-local="photoInput?.click()"
      @clear="clearPhoto"
      @select-photo="selectPhoto"
      @delete-photo="deletePhoto"
    />

    <ResumeIconModal v-if="showIconModal" @close="showIconModal = false" @insert="insertSnippet" />

    <div v-if="showSaveVersionDialog" class="writer-dialog-mask" @click.self="showSaveVersionDialog = false">
      <section class="writer-dialog">
        <header>
          <div>
            <p>Save Version</p>
            <h2>保存当前版本</h2>
          </div>
          <button @click="showSaveVersionDialog = false">×</button>
        </header>
        <label
          ><span>版本说明</span
          ><input
            v-model.trim="versionTitle"
            maxlength="256"
            placeholder="例如：补充项目量化成果"
            @keydown.enter.prevent="saveManualVersion"
        /></label>
        <div class="writer-dialog-actions">
          <button class="secondary-btn" @click="showSaveVersionDialog = false">取消</button
          ><button class="primary-btn" :disabled="savingVersion" @click="saveManualVersion">
            {{ savingVersion ? '保存中' : '保存版本' }}
          </button>
        </div>
      </section>
    </div>

    <div v-if="showVersionHistory" class="writer-panel-mask" @click.self="showVersionHistory = false">
      <aside class="writer-side-panel">
        <header>
          <div>
            <p>Version History</p>
            <h2>版本历史</h2>
            <span>最多保留最新 30 条，回退前会自动备份当前内容。</span>
          </div>
          <button @click="showVersionHistory = false">×</button>
        </header>
        <p v-if="versionError" class="writer-panel-error">{{ versionError }}</p>
        <div v-if="versionsLoading" class="writer-panel-empty">正在加载版本历史</div>
        <div v-else-if="!versions.length" class="writer-panel-empty">暂无版本，请先保存一个版本。</div>
        <div v-else class="writer-version-list">
          <article
            v-for="version in versions"
            :key="version.versionId"
            :class="{ active: previewVersion?.versionId === version.versionId }"
          >
            <button class="writer-version-main" @click="previewHistoryVersion(version)">
              <strong>版本 {{ version.versionNo }} · {{ sourceLabel(version.source) }}</strong>
              <span>{{ version.title || '未填写说明' }}</span>
              <small>{{ formatVersionTime(version.createdAt) }}</small>
            </button>
            <div class="writer-version-actions">
              <button @click="restoreHistoryVersion(version)">回退</button>
              <button class="danger" @click="removeHistoryVersion(version)">删除</button>
            </div>
          </article>
        </div>
        <section v-if="previewVersion" class="writer-version-preview">
          <h3>版本 {{ previewVersion.versionNo }} 预览</h3>
          <pre>{{ previewMarkdown }}</pre>
        </section>
      </aside>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  createWriterVersion,
  deleteWriterVersion,
  getWriterVersion,
  listWriterVersions,
  normalizeResumeAssetUrl,
  restoreWriterVersion,
  resumeAssetDisplayUrl,
  uploadResumeAsset,
} from '../api/resume'
import { backupWriterState } from '../composables/useResumeWriterImport'
import { getWorkspaceState, saveWorkspaceState } from '../api/workspace'
import {
  applyResumePhotoToHtml,
  extractManagedResumePhoto,
  photoTransformStyle,
  renderResumeMarkdown,
  resumePrintCss,
  stripManagedResumePhoto,
} from '../utils/resumeRender'
import { sanitizeResumeHtml } from '../utils/sanitizeHtml'
import {
  clampPhotoNumber,
  downloadFile,
  escapeHtmlText,
  normalizeFontSizeValue,
  normalizeLineHeightValue,
  resolveResumeExportFileName,
  sanitizeResumeFileName,
  stripFileExt,
  waitForImages,
} from '../utils/resumeWriterFormat'
import { imageUrlToDataUrl } from '../utils/imageData'
import { collectPageSegments, groupSegmentsIntoPages, renderPageSegments } from '../utils/resumePagination'
import {
  addPdfLinks,
  getPdfLinkLayouts,
  getPdfPhotoLayouts,
  photoResizeDelta,
  pinPdfPhotoFrames,
} from '../utils/resumePdf'
import ResumeIconModal from './resume-writer/ResumeIconModal.vue'
import ResumePhotoModal from './resume-writer/ResumePhotoModal.vue'

const WRITER_STATE_KEY = 'resume.writer'
const AUTO_VERSION_INTERVAL = 5 * 60 * 1000
const selectedTags = ref([])
const profile = ref(emptyProfile())
const photoUrl = ref('')
const photoLibrary = ref([])
const photoUploading = ref(false)
const photoSettings = ref(readPhotoSettings())
const selectedPhoto = ref(false)
const markdown = ref('')
const fileName = ref('')
const savedAt = ref('')
const exportingPdf = ref(false)
const loadingExample = ref(false)
const onePage = ref(false)
const showPhotoModal = ref(false)
const showIconModal = ref(false)
const showVersionHistory = ref(false)
const showSaveVersionDialog = ref(false)
const versionTitle = ref('')
const versions = ref([])
const previewVersion = ref(null)
const previewMarkdown = ref('')
const versionsLoading = ref(false)
const versionError = ref('')
const savingVersion = ref(false)
const editorMode = ref('split')
const themeIndex = ref(0)
const fileInput = ref(null)
const photoInput = ref(null)
const textareaRef = ref(null)
const measureRef = ref(null)
const previewCanvas = ref(null)
const pages = ref([])
const pageWidthPx = ref(0)
const previewScale = ref(1)
const pagesScaleStyle = computed(() => (previewScale.value < 1 ? { zoom: previewScale.value } : {}))
const themes = ['theme-default', 'theme-blue', 'theme-orange']
const themeLabels = ['黑白经典', '科技蓝', '活力橙']
const currentTheme = computed(() => themes[themeIndex.value % themes.length])
const paperClasses = computed(() => ['resume-paper', currentTheme.value, { compact: onePage.value }])
const themeName = computed(() => themeLabels[themeIndex.value % themeLabels.length])
const fontFamily = ref('PingFang SC, Microsoft YaHei, Arial, sans-serif')
const fontSizeOptions = [
  { value: '10px', label: '10px' },
  { value: '10.5px', label: '10.5px' },
  { value: '11px', label: '11px' },
  { value: '11.5px', label: '11.5px' },
  { value: '12px', label: '12px' },
  { value: '12.5px', label: '12.5px' },
  { value: '13px', label: '13px' },
  { value: '13.5px', label: '13.5px' },
  { value: '14px', label: '14px' },
  { value: '15px', label: '15px' },
  { value: '16px', label: '16px' },
]
const lineHeightOptions = [
  { value: '1.15', label: '1.15' },
  { value: '1.25', label: '1.25' },
  { value: '1.35', label: '1.35' },
  { value: '1.45', label: '1.45' },
  { value: '1.5', label: '1.50' },
  { value: '1.6', label: '1.60' },
  { value: '1.72', label: '1.72' },
  { value: '1.85', label: '1.85' },
  { value: '2', label: '2.00' },
  { value: '2.2', label: '2.20' },
]
const fontSize = ref('12.5px')
const lineHeight = ref('1.72')
const currentFontSizeLabel = computed(
  () => fontSizeOptions.find((item) => item.value === fontSize.value)?.label || fontSize.value,
)
const currentLineHeightLabel = computed(
  () => lineHeightOptions.find((item) => item.value === lineHeight.value)?.label || lineHeight.value,
)
const activeCombo = ref('')
let comboBlurTimer = null
let saveTimer = null
let photoDragState = null
let writerStateLoaded = false
let lastVersionAt = Date.now()
let lastVersionMarkdown = ''
let autoVersionSaving = false
const displayPhotoUrl = computed(() => resumeAssetDisplayUrl(photoUrl.value))
const displayPhotoLibrary = computed(() =>
  photoLibrary.value.map((item) => ({ ...item, displayUrl: resumeAssetDisplayUrl(item.url) })),
)
const html = computed(() =>
  sanitizeResumeHtml(
    applyResumePhotoToHtml(renderResumeMarkdown(markdown.value), displayPhotoUrl.value, photoSettings.value, {
      selected: selectedPhoto.value,
    }),
  ),
)
const paperStyle = computed(() => ({
  fontFamily: fontFamily.value,
  fontSize: fontSize.value,
  lineHeight: lineHeight.value,
  '--resume-line-height': lineHeight.value,
}))
watch([photoUrl, photoLibrary, photoSettings, selectedTags, profile, fontSize, lineHeight, onePage], saveDraft, {
  deep: true,
})

let pageTimer = null
let resizeObserver = null
function schedulePaginate() {
  if (pageTimer) clearTimeout(pageTimer)
  pageTimer = setTimeout(() => nextTick(paginate), 220)
}
function paginate() {
  const el = measureRef.value
  if (!el) {
    pages.value = [html.value]
    return
  }
  const rect = el.getBoundingClientRect()
  const pxPerMm = rect.width / 210
  if (!pxPerMm) {
    pages.value = [html.value]
    return
  }
  pageWidthPx.value = rect.width
  const padMm = onePage.value ? 12 : 14
  // 留出安全余量，避免渲染取整误差把内容挤到下一页或导出时多出空白页。
  const usable = (297 - padMm * 2) * pxPerMm - 10
  const blocks = collectPageSegments(el, rect)
  if (!blocks.length) {
    pages.value = ['']
    return
  }
  pages.value = groupSegmentsIntoPages(blocks, usable).map(renderPageSegments)
  updateScale()
}
function updateScale() {
  const canvas = previewCanvas.value
  if (!canvas || !pageWidthPx.value) {
    previewScale.value = 1
    return
  }
  const available = canvas.clientWidth - 36
  previewScale.value = available >= pageWidthPx.value ? 1 : Math.max(0.4, available / pageWidthPx.value)
}
watch([html, fontSize, lineHeight, fontFamily, currentTheme, onePage], schedulePaginate)
watch(editorMode, () => nextTick(updateScale))
onMounted(async () => {
  await loadWriterState()
  await initializeVersionBaseline()
  writerStateLoaded = true
  nextTick(paginate)
  if (typeof ResizeObserver !== 'undefined' && previewCanvas.value) {
    resizeObserver = new ResizeObserver(() => updateScale())
    resizeObserver.observe(previewCanvas.value)
  }
})
onBeforeUnmount(() => {
  if (pageTimer) clearTimeout(pageTimer)
  if (saveTimer) clearTimeout(saveTimer)
  if (comboBlurTimer) clearTimeout(comboBlurTimer)
  stopPhotoDrag()
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
})
function importClick() {
  fileInput.value?.click()
}
async function loadExampleResume() {
  if (loadingExample.value) return
  const current = writerState()
  if (
    String(current.markdown || '').trim() &&
    !window.confirm('加载示例将覆盖当前 Markdown 内容，系统会先保存一条可回退版本。是否继续？')
  ) {
    return
  }
  loadingExample.value = true
  try {
    if (String(current.markdown || '').trim()) {
      await backupWriterState(current, '加载脱敏示例前的草稿备份')
    }
    const module = await import('../examples/resume-writer-example.md?raw')
    markdown.value = String(module.default || '').trim()
    fileName.value = 'AI应用开发岗-脱敏示例简历'
    fontSize.value = '12.5px'
    lineHeight.value = '1.72'
    onePage.value = true
    photoUrl.value = ''
    photoSettings.value = normalizePhotoSettings()
    selectedPhoto.value = false
    await saveNow()
    await refreshVersions()
    await nextTick()
    paginate()
    textareaRef.value?.focus()
  } catch (error) {
    alert(error?.message || '示例简历加载失败')
  } finally {
    loadingExample.value = false
  }
}
function cycleTheme() {
  themeIndex.value = (themeIndex.value + 1) % themes.length
}
async function importPhoto(event) {
  const files = Array.from(event.target.files || []).filter((file) => file.type.startsWith('image/'))
  if (!files.length) return
  photoUploading.value = true
  try {
    const uploaded = (
      await Promise.all(
        files.map(async (file) => {
          const data = await uploadResumeAsset(file)
          if (!data?.url) return null
          return {
            id: data.assetId || `${Date.now()}-${Math.random().toString(16).slice(2)}`,
            name: file.name || '照片',
            url: normalizeResumeAssetUrl(data.url),
            createdAt: Date.now(),
          }
        }),
      )
    ).filter(Boolean)
    if (uploaded.length) {
      photoLibrary.value = [
        ...uploaded,
        ...photoLibrary.value.filter((item) => !uploaded.some((row) => row.url === item.url)),
      ]
      photoUrl.value = uploaded[0].url
      selectedPhoto.value = false
    }
    await saveNow()
  } catch (error) {
    alert(error?.message || '照片上传失败')
  } finally {
    photoUploading.value = false
    event.target.value = ''
  }
}
function clearPhoto() {
  photoUrl.value = ''
  selectedPhoto.value = false
  saveNow()
}
function selectPhoto(item) {
  photoUrl.value = normalizeInitialPhotoUrl(typeof item === 'string' ? item : item?.url || '')
  selectedPhoto.value = false
  saveNow()
}
function deletePhoto(item) {
  const url = typeof item === 'string' ? item : item?.url
  const name = typeof item === 'string' ? '该照片' : item?.name || '该照片'
  if (!window.confirm(`确定删除照片“${name}”吗？删除后无法恢复。`)) return
  photoLibrary.value = photoLibrary.value.filter((photo) => photo.url !== url)
  if (photoUrl.value === url) photoUrl.value = photoLibrary.value[0]?.url || ''
  saveNow()
}
function openCombo(name) {
  if (comboBlurTimer) clearTimeout(comboBlurTimer)
  activeCombo.value = name
}
function closeCombo() {
  activeCombo.value = ''
}
function toggleCombo(name) {
  activeCombo.value = activeCombo.value === name ? '' : name
}
function handleComboBlur(name) {
  comboBlurTimer = setTimeout(() => {
    if (name === 'fontSize') normalizeFontSizeInput()
    if (name === 'lineHeight') normalizeLineHeightInput()
    closeCombo()
  }, 120)
}
function handleComboEnter(name) {
  if (name === 'fontSize') normalizeFontSizeInput()
  if (name === 'lineHeight') normalizeLineHeightInput()
  closeCombo()
}
function selectFontSizeOption(value) {
  fontSize.value = value
  closeCombo()
}
function selectLineHeightOption(value) {
  lineHeight.value = value
  closeCombo()
}
function normalizeFontSizeInput() {
  fontSize.value = normalizeFontSizeValue(fontSize.value)
}
function normalizeLineHeightInput() {
  lineHeight.value = normalizeLineHeightValue(lineHeight.value)
}
function normalizeInitialPhotoUrl(value) {
  const url = normalizeResumeAssetUrl(value)
  return !url || isLocalOnlyPhotoUrl(url) ? '' : url
}
function isLocalOnlyPhotoUrl(url) {
  return /^data:|^blob:/i.test(String(url || ''))
}
function readPhotoLibrary(rows = [], currentUrl = '') {
  const normalized = Array.isArray(rows)
    ? rows
        .filter((item) => item?.url && !isLocalOnlyPhotoUrl(item.url))
        .map((item, index) => {
          const url = normalizeResumeAssetUrl(item.url)
          return {
            id: item.id || `${index}-${url}`,
            name: item.name || `照片 ${index + 1}`,
            url,
            createdAt: item.createdAt || Date.now(),
          }
        })
        .filter((item) => item.url)
    : []
  if (currentUrl && !isLocalOnlyPhotoUrl(currentUrl) && !normalized.some((item) => item.url === currentUrl)) {
    normalized.unshift({ id: 'current-photo', name: '当前照片', url: currentUrl, createdAt: Date.now() })
  }
  return normalized
}
function readPhotoSettings(value = {}) {
  return normalizePhotoSettings(value)
}
function normalizePhotoSettings(value = {}) {
  return {
    x: clampPhotoNumber(value.x, -300, 300, 0),
    y: clampPhotoNumber(value.y, -300, 300, 0),
    scale: clampPhotoNumber(value.scale, 0.4, 3, 1),
  }
}
function setPhotoScale(value) {
  selectedPhoto.value = true
  photoSettings.value = normalizePhotoSettings({ ...photoSettings.value, scale: value })
}
function resetPhotoAdjust() {
  photoSettings.value = normalizePhotoSettings({ x: 0, y: 0, scale: 1 })
  selectedPhoto.value = true
}
function handlePreviewClick(event) {
  const frame = event.target?.closest?.('[data-managed-resume-photo="true"]')
  selectedPhoto.value = !!frame
}
function handlePreviewPointerDown(event) {
  const frame = event.target?.closest?.('[data-managed-resume-photo="true"]')
  if (!frame) {
    if (previewCanvas.value?.contains(event.target)) selectedPhoto.value = false
    return
  }
  event.preventDefault()
  selectedPhoto.value = true
  if (photoDragState) stopPhotoDrag(false)
  const isMouseEvent = event.type === 'mousedown'
  const handle = event.target?.dataset?.photoHandle || 'move'
  const currentSettings = normalizePhotoSettings(photoSettings.value)
  photoDragState = {
    mode: handle === 'move' ? 'move' : 'resize',
    handle,
    startX: event.clientX,
    startY: event.clientY,
    originX: currentSettings.x,
    originY: currentSettings.y,
    originScale: currentSettings.scale,
    currentSettings,
    moveEvent: isMouseEvent ? 'mousemove' : 'pointermove',
    upEvent: isMouseEvent ? 'mouseup' : 'pointerup',
  }
  document.addEventListener(photoDragState.moveEvent, handlePhotoPointerMove)
  document.addEventListener(photoDragState.upEvent, stopPhotoDrag, { once: true })
  if (!isMouseEvent) document.addEventListener('pointercancel', stopPhotoDrag, { once: true })
}
function handlePhotoPointerMove(event) {
  if (!photoDragState) return
  event.preventDefault()
  const scale = previewScale.value || 1
  const dx = (event.clientX - photoDragState.startX) / scale
  const dy = (event.clientY - photoDragState.startY) / scale
  const nextSettings =
    photoDragState.mode === 'resize'
      ? normalizePhotoSettings({
          ...photoDragState.currentSettings,
          scale: photoDragState.originScale + photoResizeDelta(photoDragState.handle, dx, dy),
        })
      : normalizePhotoSettings({
          ...photoDragState.currentSettings,
          x: photoDragState.originX + dx,
          y: photoDragState.originY + dy,
        })
  photoDragState.currentSettings = nextSettings
  applyPhotoSettingsToDom(nextSettings)
}
function applyPhotoSettingsToDom(settings) {
  const normalized = normalizePhotoSettings(settings)
  const style = photoTransformStyle(normalized)
  document.querySelectorAll('[data-managed-resume-photo="true"]').forEach((frame) => {
    frame.style.cssText = style
  })
}
function stopPhotoDrag(commit = true) {
  const state = photoDragState
  photoDragState = null
  document.removeEventListener(state?.moveEvent || 'pointermove', handlePhotoPointerMove)
  document.removeEventListener(state?.upEvent || 'pointerup', stopPhotoDrag)
  document.removeEventListener('pointercancel', stopPhotoDrag)
  if (commit && state?.currentSettings) photoSettings.value = normalizePhotoSettings(state.currentSettings)
}
function insertSnippet(text) {
  const el = textareaRef.value
  const start = el?.selectionStart ?? markdown.value.length
  const end = el?.selectionEnd ?? markdown.value.length
  markdown.value = markdown.value.slice(0, start) + text + markdown.value.slice(end)
  saveDraft()
  setTimeout(() => {
    el?.focus()
    if (el) el.selectionStart = el.selectionEnd = start + text.length
  }, 0)
}
async function loadWriterState() {
  applyWriterState(await getWorkspaceState(WRITER_STATE_KEY))
}
function applyWriterState(rawState = {}) {
  const state = rawState && typeof rawState === 'object' ? rawState : {}
  const storedMarkdown = String(state.markdown ?? '')
  const managedPhoto = extractManagedResumePhoto(storedMarkdown)
  markdown.value = stripManagedResumePhoto(storedMarkdown)
  fileName.value = String(state.fileName ?? '')
  fontSize.value = normalizeFontSizeValue(state.fontSize || '12.5px')
  lineHeight.value = normalizeLineHeightValue(state.lineHeight || '1.72')
  onePage.value = Boolean(state.onePage)
  selectedTags.value = Array.isArray(state.tags) ? state.tags : []
  profile.value = normalizeProfile(state.profile)
  photoSettings.value = readPhotoSettings(state.photoSettings)
  photoUrl.value = normalizeInitialPhotoUrl(state.photoUrl || managedPhoto)
  photoLibrary.value = readPhotoLibrary(state.photoLibrary, photoUrl.value)
}
function writerState() {
  return {
    markdown: markdown.value,
    fileName: fileName.value,
    fontSize: fontSize.value,
    lineHeight: lineHeight.value,
    onePage: onePage.value,
    photoUrl: isLocalOnlyPhotoUrl(photoUrl.value) ? '' : normalizeResumeAssetUrl(photoUrl.value),
    photoSettings: normalizePhotoSettings(photoSettings.value),
    photoLibrary: photoLibrary.value.map((item) => ({ ...item, url: normalizeResumeAssetUrl(item.url) })),
    tags: selectedTags.value,
    profile: profile.value,
  }
}
async function saveNow() {
  try {
    await saveWorkspaceState(WRITER_STATE_KEY, writerState())
    savedAt.value = new Date().toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    await maybeCreateAutoVersion()
  } catch (error) {
    alert(error?.message || '简历草稿保存失败')
  }
}
function saveDraft() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(saveNow, 300)
}
async function importMarkdown(event) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    const current = writerState()
    if (String(current.markdown || '').trim()) {
      await backupWriterState(current, `导入「${file.name}」前的草稿备份`)
    }
    const imported = await file.text()
    const importedPhoto = extractManagedResumePhoto(imported)
    if (importedPhoto) photoUrl.value = normalizeInitialPhotoUrl(importedPhoto)
    markdown.value = stripManagedResumePhoto(imported)
    fileName.value = stripFileExt(file.name)
    await saveNow()
    await refreshVersions()
  } catch (error) {
    alert(error?.message || 'Markdown 导入失败')
  } finally {
    event.target.value = ''
  }
}
async function initializeVersionBaseline() {
  try {
    versions.value = await listWriterVersions()
    const latest = versions.value[0]
    if (!latest) {
      lastVersionMarkdown = markdown.value
      lastVersionAt = Date.now()
      return
    }
    const detail = await getWriterVersion(latest.versionId)
    const snapshot = parseSnapshot(detail?.snapshotJson)
    lastVersionMarkdown = String(snapshot?.markdown || markdown.value)
    lastVersionAt = new Date(latest.createdAt || Date.now()).getTime() || Date.now()
  } catch (_) {
    lastVersionMarkdown = markdown.value
    lastVersionAt = Date.now()
  }
}
function markdownChangedSignificantly(previous, current) {
  const before = String(previous || '')
  const after = String(current || '')
  if (before === after) return false
  const delta = Math.abs(after.length - before.length)
  const base = Math.max(before.length, 1)
  return delta >= 200 || delta / base >= 0.05
}
async function maybeCreateAutoVersion() {
  if (!writerStateLoaded || autoVersionSaving || Date.now() - lastVersionAt < AUTO_VERSION_INTERVAL) return
  if (!markdownChangedSignificantly(lastVersionMarkdown, markdown.value)) return
  autoVersionSaving = true
  try {
    await createWriterVersion({
      source: 'auto',
      title: '自动快照',
      resumeId: '',
      snapshot: JSON.stringify(writerState()),
    })
    lastVersionMarkdown = markdown.value
    lastVersionAt = Date.now()
  } catch (error) {
    versionError.value = error?.message || '自动版本保存失败'
  } finally {
    autoVersionSaving = false
  }
}
function openSaveVersionDialog() {
  versionTitle.value = ''
  showSaveVersionDialog.value = true
}
async function saveManualVersion() {
  savingVersion.value = true
  versionError.value = ''
  try {
    await saveWorkspaceState(WRITER_STATE_KEY, writerState())
    await createWriterVersion({
      source: 'manual',
      title: versionTitle.value,
      resumeId: '',
      snapshot: JSON.stringify(writerState()),
    })
    lastVersionMarkdown = markdown.value
    lastVersionAt = Date.now()
    showSaveVersionDialog.value = false
    await refreshVersions()
  } catch (error) {
    versionError.value = error?.message || '版本保存失败'
    alert(versionError.value)
  } finally {
    savingVersion.value = false
  }
}
async function refreshVersions() {
  versionsLoading.value = true
  versionError.value = ''
  try {
    versions.value = await listWriterVersions()
  } catch (error) {
    versionError.value = error?.message || '版本列表加载失败'
  } finally {
    versionsLoading.value = false
  }
}
async function openVersionHistory() {
  showVersionHistory.value = true
  previewVersion.value = null
  previewMarkdown.value = ''
  await refreshVersions()
}
async function previewHistoryVersion(version) {
  versionError.value = ''
  try {
    const detail = await getWriterVersion(version.versionId)
    const snapshot = parseSnapshot(detail?.snapshotJson)
    previewVersion.value = detail
    previewMarkdown.value = String(snapshot.markdown || '')
  } catch (error) {
    versionError.value = error?.message || '版本预览失败'
  }
}
async function restoreHistoryVersion(version) {
  if (!confirm(`确认回退到版本 ${version.versionNo}？当前内容会先自动备份。`)) return
  versionError.value = ''
  try {
    const detail = await restoreWriterVersion(version.versionId, {
      currentSnapshot: JSON.stringify(writerState()),
      currentResumeId: '',
    })
    const snapshot = parseSnapshot(detail?.snapshotJson)
    applyWriterState(snapshot)
    await saveWorkspaceState(WRITER_STATE_KEY, writerState())
    savedAt.value = new Date().toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    lastVersionMarkdown = markdown.value
    lastVersionAt = Date.now()
    await refreshVersions()
    await nextTick(paginate)
    showVersionHistory.value = false
  } catch (error) {
    versionError.value = error?.message || '版本回退失败'
  }
}
async function removeHistoryVersion(version) {
  if (!confirm(`确认删除版本 ${version.versionNo}？`)) return
  versionError.value = ''
  try {
    await deleteWriterVersion(version.versionId)
    if (previewVersion.value?.versionId === version.versionId) {
      previewVersion.value = null
      previewMarkdown.value = ''
    }
    await refreshVersions()
  } catch (error) {
    versionError.value = error?.message || '版本删除失败'
  }
}
function parseSnapshot(value) {
  if (value && typeof value === 'object') return value
  try {
    return JSON.parse(String(value || '{}'))
  } catch (_) {
    throw new Error('版本快照格式无效，无法应用。')
  }
}
function sourceLabel(source) {
  return (
    { manual: '手动保存', auto: '自动快照', import_backup: '导入前备份', restore_backup: '回退前备份' }[source] ||
    source ||
    '未知来源'
  )
}
function formatVersionTime(value) {
  return value ? new Date(value).toLocaleString() : '未知时间'
}
function exportMarkdown() {
  downloadFile(markdown.value, `${exportBaseName()}.md`, 'text/markdown;charset=utf-8')
}
async function exportHtml() {
  downloadFile(await buildHtmlDocument(), `${exportBaseName()}.html`, 'text/html;charset=utf-8')
}
async function photoAsDataUrl() {
  return imageUrlToDataUrl(displayPhotoUrl.value)
}
async function exportPdf() {
  if (exportingPdf.value) return
  const root = document.getElementById('resume-print-root')
  const pageEls = root ? Array.from(root.querySelectorAll('.resume-page')) : []
  if (!pageEls.length) return
  exportingPdf.value = true
  let wrapper = null
  try {
    const [{ default: html2canvas }, { jsPDF }] = await Promise.all([import('html2canvas'), import('jspdf')])
    wrapper = document.createElement('div')
    const style = document.createElement('style')
    style.textContent = resumePrintCss()
    wrapper.className = 'resume-pdf-export-wrapper'
    wrapper.style.position = 'fixed'
    wrapper.style.left = '-10000px'
    wrapper.style.top = '0'
    wrapper.style.width = '210mm'
    wrapper.style.background = '#ffffff'
    wrapper.style.zIndex = '-1'
    wrapper.appendChild(style)
    const photoLayouts = getPdfPhotoLayouts(pageEls)
    const linkLayouts = getPdfLinkLayouts(pageEls)
    const clones = pageEls.map((pageEl, index) => {
      const clone = pageEl.cloneNode(true)
      clone.style.width = '210mm'
      clone.style.minWidth = '210mm'
      clone.style.margin = '0'
      clone.style.boxShadow = 'none'
      clone.style.position = 'relative'
      clone.style.fontFamily = fontFamily.value
      clone.style.fontSize = fontSize.value
      clone.style.lineHeight = lineHeight.value
      clone.style.setProperty('--resume-line-height', lineHeight.value)
      clone.querySelectorAll('.resume-photo-frame.is-selected').forEach((el) => el.classList.remove('is-selected'))
      clone.querySelectorAll('.resume-photo-handle').forEach((el) => el.remove())
      pinPdfPhotoFrames(clone, photoLayouts[index] || [])
      wrapper.appendChild(clone)
      return clone
    })
    document.body.appendChild(wrapper)
    const embeddedPhotoUrl = await photoAsDataUrl()
    if (embeddedPhotoUrl) {
      clones.forEach((clone) =>
        clone.querySelectorAll('img.resume-photo').forEach((img) => {
          img.src = embeddedPhotoUrl
        }),
      )
    }
    await Promise.all(clones.map(waitForImages))
    const pdf = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'portrait', compress: true })
    const pageWmm = 210
    const pageHmm = 297
    // 逐页独立渲染：每个 .resume-page 单独成一张 A4，避免依赖自动分页切片产生空白页。
    for (let i = 0; i < clones.length; i++) {
      const canvas = await html2canvas(clones[i], {
        scale: 2.6,
        useCORS: true,
        backgroundColor: '#ffffff',
        scrollX: 0,
        scrollY: 0,
        letterRendering: true,
      })
      const imgData = canvas.toDataURL('image/jpeg', 1)
      const imgHmm = (canvas.height * pageWmm) / canvas.width
      if (i > 0) pdf.addPage()
      pdf.addImage(imgData, 'JPEG', 0, 0, pageWmm, Math.min(imgHmm, pageHmm), undefined, 'FAST')
      addPdfLinks(pdf, linkLayouts[i] || [], pageEls[i])
    }
    pdf.save(`${exportBaseName()}.pdf`)
  } catch (error) {
    alert(`PDF 导出失败：${error?.message || error}`)
  } finally {
    wrapper?.remove()
    exportingPdf.value = false
  }
}
async function buildHtmlDocument() {
  const dataUrl = await photoAsDataUrl()
  const body = sanitizeResumeHtml(
    applyResumePhotoToHtml(renderResumeMarkdown(markdown.value), dataUrl || displayPhotoUrl.value, photoSettings.value),
  )
  return `<!doctype html><html><head><meta charset="utf-8"><title>${escapeHtmlText(exportBaseName())}</title><style>${resumePrintCss()}

/* 顶部操作按钮垂直居中 */
.resume-clean-actions{
  align-self:center!important;
  align-items:center!important;
}
.resume-clean-actions>button,
.export-group,
.export-group button{
  align-self:center!important;
}
</style></head><body><article class="resume-paper ${currentTheme.value} ${onePage.value ? 'compact' : ''}" style="font-family:${fontFamily.value};font-size:${fontSize.value};line-height:${lineHeight.value};--resume-line-height:${lineHeight.value}">${body}</article></body></html>`
}
function emptyProfile() {
  return { name: '', basic: '', contact: '', blog: '', position: '' }
}
function normalizeProfile(value) {
  const source = value && typeof value === 'object' ? value : {}
  return Object.fromEntries(Object.keys(emptyProfile()).map((key) => [key, String(source[key] ?? '')]))
}
function saveFileName() {
  saveDraft()
}
function normalizeFileName() {
  fileName.value = sanitizeResumeFileName(fileName.value)
  saveDraft()
}
function exportBaseName() {
  return resolveResumeExportFileName(fileName.value)
}
</script>

<style scoped src="./resume-writer/resume-writer.css"></style>
<style scoped src="./resume-writer/resume-writer-layout.css"></style>
