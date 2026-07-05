<template>
  <section class="resume-writer-clean">
    <header class="resume-clean-topbar">
      <div class="resume-title-area">
        <div>
          <strong>简历撰写</strong>
          <small>Markdown 在线编辑、实时预览、照片与图标弹窗维护、多格式导出</small>
        </div>
      </div>
      <nav class="resume-clean-menu">
        <button class="import-md-btn" @click="importClick">导入</button>
        <button @click="showPhotoModal = true">照片维护</button>
        <button @click="showIconModal = true">图标列表</button>
        <button @click="cycleTheme">主题：{{ themeName }}</button>
      </nav>
      <div class="resume-clean-actions">
        <label class="resume-filename-field" title="导出 PDF、MD、HTML 时使用该文件名">
          <span>文件名</span>
          <input v-model="fileName" placeholder="请输入导出文件名" @input="saveFileName" @blur="normalizeFileName" />
        </label>
        <div class="export-group">
          <span class="export-label">导出</span>
          <button class="primary-btn" :disabled="exportingPdf" @click="exportPdf">{{ exportingPdf ? '生成中' : 'PDF' }}</button>
          <button class="secondary-btn" @click="exportMarkdown">MD</button>
          <button class="secondary-btn" @click="exportHtml">HTML</button>
        </div>
      </div>
      <input ref="fileInput" type="file" accept=".md,.markdown,.txt" hidden @change="importMarkdown" />
      <input ref="photoInput" type="file" accept="image/*" multiple hidden @change="importPhoto" />
    </header>

    <div class="resume-clean-toolbar">
      <button :class="{ active: editorMode === 'source' }" @click="editorMode = 'source'">源码模式</button>
      <button :class="{ active: editorMode === 'split' }" @click="editorMode = 'split'">左右分屏</button>
      <button :class="{ active: editorMode === 'preview' }" @click="editorMode = 'preview'">预览模式</button>
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
            placeholder="12.5px"
            :title="`当前字号：${currentFontSizeLabel}，可下拉选择也可手输，例如 13 或 13px`"
            @focus="openCombo('fontSize')"
            @input="openCombo('fontSize')"
            @blur="handleComboBlur('fontSize')"
            @keydown.enter.prevent="normalizeFontSizeInput(); closeCombo()"
            @keydown.esc.prevent="closeCombo()"
          />
          <button type="button" class="editable-select-arrow" aria-label="展开字号选项" tabindex="-1" @mousedown.prevent="toggleCombo('fontSize')"></button>
          <div v-if="activeCombo === 'fontSize'" class="editable-select-menu">
            <button v-for="item in fontSizeOptions" :key="item.value" type="button" :class="{ active: item.value === fontSize }" @mousedown.prevent="selectFontSizeOption(item.value)">{{ item.label }}</button>
          </div>
        </div>
      </div>
      <div class="font-control-group compact-select-group line-control-group">
        <span>行距</span>
        <div class="editable-select" @mousedown.stop>
          <input
            v-model.trim="lineHeight"
            placeholder="1.72"
            :title="`当前行距：${currentLineHeightLabel}，可下拉选择也可手输，例如 1.6 或 1.85`"
            @focus="openCombo('lineHeight')"
            @input="openCombo('lineHeight')"
            @blur="handleComboBlur('lineHeight')"
            @keydown.enter.prevent="normalizeLineHeightInput(); closeCombo()"
            @keydown.esc.prevent="closeCombo()"
          />
          <button type="button" class="editable-select-arrow" aria-label="展开行距选项" tabindex="-1" @mousedown.prevent="toggleCombo('lineHeight')"></button>
          <div v-if="activeCombo === 'lineHeight'" class="editable-select-menu">
            <button v-for="item in lineHeightOptions" :key="item.value" type="button" :class="{ active: item.value === lineHeight }" @mousedown.prevent="selectLineHeightOption(item.value)">{{ item.label }}</button>
          </div>
        </div>
      </div>
      <div v-if="selectedPhoto" class="photo-adjust-group">
        <span>照片缩放</span>
        <input type="range" min="0.4" max="3" step="0.05" :value="photoSettings.scale" @input="setPhotoScale($event.target.value)" />
        <b>{{ Math.round(photoSettings.scale * 100) }}%</b>
        <button type="button" @click="resetPhotoAdjust">重置照片</button>
      </div>
      <span class="save-state">{{ savedAt ? `已保存 ${savedAt}` : '本地自动保存' }}</span>
    </div>

    <main :class="['resume-clean-workbench', `mode-${editorMode}`]">
      <section v-show="editorMode !== 'preview'" class="resume-clean-editor">
        <textarea ref="textareaRef" v-model="markdown" spellcheck="false" @input="saveDraft" />
      </section>
      <section v-show="editorMode !== 'source'" class="resume-clean-preview">
        <div ref="previewCanvas" class="resume-preview-canvas" @pointerdown="handlePreviewPointerDown" @mousedown="handlePreviewPointerDown" @click="handlePreviewClick">
          <div id="resume-print-root" class="resume-pages" :style="pagesScaleStyle">
            <!-- eslint-disable vue/no-v-html page 源自 sanitizeResumeHtml 清洗后的简历 HTML，已剥离脚本与事件处理器 -->
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
      <!-- eslint-disable-next-line vue/no-v-html html 源自 sanitizeResumeHtml 清洗后的简历 HTML，已剥离脚本与事件处理器 -->
      <article ref="measureRef" :class="paperClasses" :style="paperStyle" class="resume-paper resume-measure" v-html="html"></article>
    </main>

    <ResumePhotoModal
      v-if="showPhotoModal"
      :photo-url="photoUrl"
      :photos="photoLibrary"
      :uploading="photoUploading"
      @close="showPhotoModal = false"
      @pick-local="photoInput?.click()"
      @clear="clearPhoto"
      @select-photo="selectPhoto"
      @delete-photo="deletePhoto"
    />

    <ResumeIconModal v-if="showIconModal" @close="showIconModal = false" @insert="insertSnippet" />
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { uploadResumeAsset } from '../api/resume'
import { applyResumePhotoToHtml, extractManagedResumePhoto, photoTransformStyle, renderResumeMarkdown, resumePrintCss, stripManagedResumePhoto } from '../utils/resumeRender'
import { sanitizeResumeHtml } from '../utils/sanitizeHtml'
import { clampPhotoNumber, downloadFile, escapeHtmlText, normalizeFontSizeValue, normalizeLineHeightValue, stripFileExt, waitForImages } from '../utils/resumeWriterFormat'
import { imageUrlToDataUrl } from '../utils/imageData'
import { collectPageSegments, groupSegmentsIntoPages, renderPageSegments } from '../utils/resumePagination'
import { addPdfLinks, getPdfLinkLayouts, getPdfPhotoLayouts, photoResizeDelta, pinPdfPhotoFrames } from '../utils/resumePdf'
import ResumeIconModal from './resume-writer/ResumeIconModal.vue'
import ResumePhotoModal from './resume-writer/ResumePhotoModal.vue'

const STORAGE_KEY = 'job-buddy.resume-writer.markdown'
const FILE_NAME_KEY = 'job-buddy.resume-writer.filename'
const FONT_SIZE_KEY = 'job-buddy.resume-writer.font-size'
const LINE_HEIGHT_KEY = 'job-buddy.resume-writer.line-height'
const PHOTO_KEY = 'job-buddy.resume-writer.photo'
const PHOTO_SETTINGS_KEY = 'job-buddy.resume-writer.photo-settings'
const PHOTO_LIBRARY_KEY = 'job-buddy.resume-writer.photo-library'
const DEFAULT_PHOTO_URL = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="110" height="140"><rect width="110" height="140" fill="#1890ff"/><text x="55" y="74" font-family="sans-serif" font-size="16" fill="#ffffff" text-anchor="middle" dominant-baseline="middle">PHOTO</text></svg>')}`
const TAG_KEY = 'job-buddy.resume-writer.tags'
const PROFILE_KEY = 'job-buddy.resume-writer.profile'
const selectedTags = ref(JSON.parse(localStorage.getItem(TAG_KEY) || '[]'))
if (!selectedTags.value.length) selectedTags.value = ['AI原生', '全栈', '端到端闭环', '产品思维', '统筹管理']
const profile = ref(JSON.parse(localStorage.getItem(PROFILE_KEY) || 'null') || {
  name: '林澈',
  basic: '男 / 1997.8 / 浙江杭州 / 汉族',
  contact: '13800001234 / linche.demo@example.com',
  blog: 'https://demo-lin.example.com / https://notes-lin.example.com',
  position: '云原生平台工程师 / 后端研发工程师 / DevOps 工程师',
})
const storedMarkdown = localStorage.getItem(STORAGE_KEY) || ''
const managedPhotoFromMarkdown = extractManagedResumePhoto(storedMarkdown)
const photoUrl = ref(normalizeInitialPhotoUrl(localStorage.getItem(PHOTO_KEY) || managedPhotoFromMarkdown || DEFAULT_PHOTO_URL))
const photoLibrary = ref(readPhotoLibrary(photoUrl.value))
const photoUploading = ref(false)
const photoSettings = ref(readPhotoSettings())
const selectedPhoto = ref(false)
const markdown = ref(stripManagedResumePhoto(storedMarkdown || defaultMarkdown()))
if (storedMarkdown && markdown.value !== storedMarkdown) localStorage.setItem(STORAGE_KEY, markdown.value)
if (!localStorage.getItem(PHOTO_KEY) && managedPhotoFromMarkdown) localStorage.setItem(PHOTO_KEY, managedPhotoFromMarkdown)
const fileName = ref(localStorage.getItem(FILE_NAME_KEY) || defaultResumeFileName())
const savedAt = ref('')
const exportingPdf = ref(false)
const onePage = ref(false)
const showPhotoModal = ref(false)
const showIconModal = ref(false)
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
const fontSize = ref(normalizeFontSizeValue(localStorage.getItem(FONT_SIZE_KEY) || '12.5px'))
const lineHeight = ref(normalizeLineHeightValue(localStorage.getItem(LINE_HEIGHT_KEY) || '1.72'))
const currentFontSizeLabel = computed(() => fontSizeOptions.find(item => item.value === fontSize.value)?.label || fontSize.value)
const currentLineHeightLabel = computed(() => lineHeightOptions.find(item => item.value === lineHeight.value)?.label || lineHeight.value)
const activeCombo = ref('')
let comboBlurTimer = null
let saveTimer = null
let photoDragState = null
const html = computed(() => sanitizeResumeHtml(applyResumePhotoToHtml(renderResumeMarkdown(markdown.value), photoUrl.value, photoSettings.value, { selected: selectedPhoto.value })))
const paperStyle = computed(() => ({
  fontFamily: fontFamily.value,
  fontSize: fontSize.value,
  lineHeight: lineHeight.value,
  '--resume-line-height': lineHeight.value,
}))
watch(photoUrl, value => localStorage.setItem(PHOTO_KEY, value || ''))
watch(photoLibrary, value => localStorage.setItem(PHOTO_LIBRARY_KEY, JSON.stringify(value || [])), { deep: true })
watch(photoSettings, value => localStorage.setItem(PHOTO_SETTINGS_KEY, JSON.stringify(normalizePhotoSettings(value))), { deep: true })
watch(selectedTags, value => localStorage.setItem(TAG_KEY, JSON.stringify(value)), { deep: true })
watch(profile, value => localStorage.setItem(PROFILE_KEY, JSON.stringify(value)), { deep: true })
watch(fontSize, value => localStorage.setItem(FONT_SIZE_KEY, value || '12.5px'))
watch(lineHeight, value => localStorage.setItem(LINE_HEIGHT_KEY, value || '1.72'))

let pageTimer = null
let resizeObserver = null
function schedulePaginate() { if (pageTimer) clearTimeout(pageTimer); pageTimer = setTimeout(() => nextTick(paginate), 220) }
function paginate() {
  const el = measureRef.value
  if (!el) { pages.value = [html.value]; return }
  const rect = el.getBoundingClientRect()
  const pxPerMm = rect.width / 210
  if (!pxPerMm) { pages.value = [html.value]; return }
  pageWidthPx.value = rect.width
  const padMm = onePage.value ? 12 : 14
  // 留出安全余量，避免渲染取整误差把内容挤到下一页或导出时多出空白页。
  const usable = (297 - padMm * 2) * pxPerMm - 10
  const blocks = collectPageSegments(el, rect)
  if (!blocks.length) { pages.value = ['']; return }
  pages.value = groupSegmentsIntoPages(blocks, usable).map(renderPageSegments)
  updateScale()
}
function updateScale() {
  const canvas = previewCanvas.value
  if (!canvas || !pageWidthPx.value) { previewScale.value = 1; return }
  const available = canvas.clientWidth - 36
  previewScale.value = available >= pageWidthPx.value ? 1 : Math.max(0.4, available / pageWidthPx.value)
}
watch([html, fontSize, lineHeight, fontFamily, currentTheme, onePage], schedulePaginate)
watch(editorMode, () => nextTick(updateScale))
onMounted(() => {
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
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
})
function importClick() { fileInput.value?.click() }
function cycleTheme() { themeIndex.value = (themeIndex.value + 1) % themes.length }
async function importPhoto(event) {
  const files = Array.from(event.target.files || []).filter(file => file.type.startsWith('image/'))
  if (!files.length) return
  photoUploading.value = true
  try {
    const uploaded = (await Promise.all(files.map(async file => {
      const data = await uploadResumeAsset(file)
      if (!data?.url) return null
      return {
        id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
        name: file.name || '照片',
        url: data.url,
        createdAt: Date.now(),
      }
    }))).filter(Boolean)
    if (uploaded.length) {
      photoLibrary.value = [...uploaded, ...photoLibrary.value.filter(item => !uploaded.some(row => row.url === item.url))]
    }
    saveNow()
  } catch (error) {
    alert(error?.message || '照片上传失败')
  } finally {
    photoUploading.value = false
    event.target.value = ''
  }
}
function clearPhoto() { photoUrl.value = ''; selectedPhoto.value = false; saveNow() }
function selectPhoto(item) { photoUrl.value = typeof item === 'string' ? item : (item?.url || ''); selectedPhoto.value = false; saveNow() }
function deletePhoto(item) {
  const url = typeof item === 'string' ? item : item?.url
  photoLibrary.value = photoLibrary.value.filter(photo => photo.url !== url)
  if (photoUrl.value === url) photoUrl.value = photoLibrary.value[0]?.url || ''
  saveNow()
}
function openCombo(name) {
  if (comboBlurTimer) clearTimeout(comboBlurTimer)
  activeCombo.value = name
}
function closeCombo() { activeCombo.value = '' }
function toggleCombo(name) { activeCombo.value = activeCombo.value === name ? '' : name }
function handleComboBlur(name) {
  comboBlurTimer = setTimeout(() => {
    if (name === 'fontSize') normalizeFontSizeInput()
    if (name === 'lineHeight') normalizeLineHeightInput()
    closeCombo()
  }, 120)
}
function selectFontSizeOption(value) { fontSize.value = value; closeCombo() }
function selectLineHeightOption(value) { lineHeight.value = value; closeCombo() }
function normalizeFontSizeInput() { fontSize.value = normalizeFontSizeValue(fontSize.value) }
function normalizeLineHeightInput() { lineHeight.value = normalizeLineHeightValue(lineHeight.value) }
function normalizeInitialPhotoUrl(value) {
  const url = String(value || '').trim()
  if (!url || isLocalOnlyPhotoUrl(url)) return DEFAULT_PHOTO_URL
  return url
}
function isLocalOnlyPhotoUrl(url) { return /^data:|^blob:/i.test(String(url || '')) }
function readPhotoLibrary(currentUrl = '') {
  let rows = []
  try { rows = JSON.parse(localStorage.getItem(PHOTO_LIBRARY_KEY) || '[]') } catch { rows = [] }
  const normalized = Array.isArray(rows) ? rows.filter(item => item?.url && !isLocalOnlyPhotoUrl(item.url)).map((item, index) => ({
    id: item.id || `${index}-${item.url}`,
    name: item.name || `照片 ${index + 1}`,
    url: item.url,
    createdAt: item.createdAt || Date.now(),
  })) : []
  if (currentUrl && !isLocalOnlyPhotoUrl(currentUrl) && !normalized.some(item => item.url === currentUrl) && currentUrl !== DEFAULT_PHOTO_URL) {
    normalized.unshift({ id: 'current-photo', name: '当前照片', url: currentUrl, createdAt: Date.now() })
  }
  return normalized
}
function readPhotoSettings() {
  try { return normalizePhotoSettings(JSON.parse(localStorage.getItem(PHOTO_SETTINGS_KEY) || '{}')) } catch { return normalizePhotoSettings({}) }
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
  const nextSettings = photoDragState.mode === 'resize'
    ? normalizePhotoSettings({ ...photoDragState.currentSettings, scale: photoDragState.originScale + photoResizeDelta(photoDragState.handle, dx, dy) })
    : normalizePhotoSettings({ ...photoDragState.currentSettings, x: photoDragState.originX + dx, y: photoDragState.originY + dy })
  photoDragState.currentSettings = nextSettings
  applyPhotoSettingsToDom(nextSettings)
}
function applyPhotoSettingsToDom(settings) {
  const normalized = normalizePhotoSettings(settings)
  const style = photoTransformStyle(normalized)
  document.querySelectorAll('[data-managed-resume-photo="true"]').forEach(frame => { frame.style.cssText = style })
}
function stopPhotoDrag(commit = true) {
  const state = photoDragState
  photoDragState = null
  document.removeEventListener(state?.moveEvent || 'pointermove', handlePhotoPointerMove)
  document.removeEventListener(state?.upEvent || 'pointerup', stopPhotoDrag)
  document.removeEventListener('pointercancel', stopPhotoDrag)
  if (commit && state?.currentSettings) photoSettings.value = normalizePhotoSettings(state.currentSettings)
}
function insertSnippet(text) { const el = textareaRef.value; const start = el?.selectionStart ?? markdown.value.length; const end = el?.selectionEnd ?? markdown.value.length; markdown.value = markdown.value.slice(0, start) + text + markdown.value.slice(end); saveDraft(); setTimeout(() => { el?.focus(); if (el) el.selectionStart = el.selectionEnd = start + text.length }, 0) }
function saveNow() { localStorage.setItem(STORAGE_KEY, markdown.value); saveFileName(); savedAt.value = new Date().toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) }
function saveDraft() { if (saveTimer) clearTimeout(saveTimer); saveTimer = setTimeout(saveNow, 300) }
function importMarkdown(event) { const file = event.target.files?.[0]; if (!file) return; const reader = new FileReader(); reader.onload = () => { const imported = String(reader.result || ''); const importedPhoto = extractManagedResumePhoto(imported); if (importedPhoto) photoUrl.value = importedPhoto; markdown.value = stripManagedResumePhoto(imported); fileName.value = stripFileExt(file.name); saveNow(); event.target.value = '' }; reader.readAsText(file, 'utf-8') }
function exportMarkdown() { downloadFile(markdown.value, `${exportBaseName()}.md`, 'text/markdown;charset=utf-8') }
async function exportHtml() { downloadFile(await buildHtmlDocument(), `${exportBaseName()}.html`, 'text/html;charset=utf-8') }
async function photoAsDataUrl() {
  return imageUrlToDataUrl(photoUrl.value)
}
async function exportPdf() {
  if (exportingPdf.value) return
  const root = document.getElementById('resume-print-root')
  const pageEls = root ? Array.from(root.querySelectorAll('.resume-page')) : []
  if (!pageEls.length) return
  exportingPdf.value = true
  let wrapper = null
  try {
    const [{ default: html2canvas }, { jsPDF }] = await Promise.all([
      import('html2canvas'),
      import('jspdf'),
    ])
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
      clone.querySelectorAll('.resume-photo-frame.is-selected').forEach(el => el.classList.remove('is-selected'))
      clone.querySelectorAll('.resume-photo-handle').forEach(el => el.remove())
      pinPdfPhotoFrames(clone, photoLayouts[index] || [])
      wrapper.appendChild(clone)
      return clone
    })
    document.body.appendChild(wrapper)
    const embeddedPhotoUrl = await photoAsDataUrl()
    if (embeddedPhotoUrl) {
      clones.forEach(clone => clone.querySelectorAll('img.resume-photo').forEach(img => { img.src = embeddedPhotoUrl }))
    }
    await Promise.all(clones.map(waitForImages))
    const pdf = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'portrait', compress: true })
    const pageWmm = 210
    const pageHmm = 297
    // 逐页独立渲染：每个 .resume-page 单独成一张 A4，避免依赖自动分页切片产生空白页。
    for (let i = 0; i < clones.length; i++) {
      const canvas = await html2canvas(clones[i], { scale: 2.6, useCORS: true, backgroundColor: '#ffffff', scrollX: 0, scrollY: 0, letterRendering: true })
      const imgData = canvas.toDataURL('image/jpeg', 1)
      const imgHmm = canvas.height * pageWmm / canvas.width
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
async function buildHtmlDocument() { const dataUrl = await photoAsDataUrl(); const body = applyResumePhotoToHtml(renderResumeMarkdown(markdown.value), dataUrl || photoUrl.value, photoSettings.value); return `<!doctype html><html><head><meta charset="utf-8"><title>${escapeHtmlText(exportBaseName())}</title><style>${resumePrintCss()}

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
</style></head><body><article class="resume-paper ${currentTheme.value} ${onePage.value ? 'compact' : ''}" style="font-family:${fontFamily.value};font-size:${fontSize.value};line-height:${lineHeight.value};--resume-line-height:${lineHeight.value}">${body}</article></body></html>` }
function defaultResumeFileName() { return `${profile.value?.name || '我的'}-resume` }
function saveFileName() { localStorage.setItem(FILE_NAME_KEY, fileName.value || '') }
function normalizeFileName() { fileName.value = exportBaseName(); saveFileName() }
function exportBaseName() { return sanitizeFileName(fileName.value || defaultResumeFileName()) }
function sanitizeFileName(value) { return String(value || '').trim().replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, ' ').replace(/^-+|-+$/g, '') || defaultResumeFileName() }
function profileBlock() { const tags = selectedTags.value.join('、'); return `## 个人资料\n\n:::left\nicon:user 基本信息：${profile.value.name} / ${profile.value.basic}\n\nicon:phone 联系方式：${profile.value.contact}\n\nicon:blog 技术博客：${profile.value.blog}\n\nicon:leaf 个人标签：${tags}\n\nicon:gear 职业定位：${profile.value.position}\n:::` }
function defaultMarkdown() { return `${profileBlock()}\n\n## 教育背景\n\n:::left\n**西湖应用技术学院 - 全日制本科 - 软件工程**\n:::\n:::right\n**2015.09 - 2019.06**\n:::\n\n- 参与校内“云课堂排课系统”实验项目，负责接口联调、部署脚本和测试数据整理，获得课程项目优秀展示。\n\n## 工作履历\n\n:::left\n**杭州雾杉数字科技有限公司 - 杭州 - 基础平台部 - 后端研发工程师**\n:::\n:::right\n**2022.04 - 至今**\n:::\n\n- 负责内部运维平台的服务治理、发布审批、日志检索和权限配置模块开发，支持 20 余个业务系统接入。\n- 推进接口超时、重试和异常告警规范化，减少线上问题定位时间。\n\n:::left\n**上海蓝橙信息服务有限公司 - 上海 - 研发中心 - Java 开发工程师**\n:::\n:::right\n**2019.07 - 2022.03**\n:::\n\n- 参与企业协同办公系统开发，负责审批流、消息通知、报表导出等业务模块。\n- 维护 Jenkins 流水线和测试环境部署脚本，配合测试团队完成版本交付。\n\n## 项目经历\n\n:::left\n**轻量化发布运维平台**\n:::\n:::right\n**核心开发**\n:::\n\n- 设计应用发布、环境变量、部署记录和回滚审批功能，统一管理测试、预发、生产环境发布流程。\n- 接入 Docker、Nginx、GitLab Webhook 和企业微信通知，实现发布状态可追踪。\n- 构建日志关键字检索与异常摘要能力，帮助值班人员快速定位失败节点。\n\n:::left\n**企业知识库检索助手**\n:::\n:::right\n**后端开发**\n:::\n\n- 实现文档上传、分段解析、标签管理和全文检索接口，支持产品文档、运维手册、FAQ 快速查询。\n- 使用任务队列异步处理大文件解析，降低接口等待时间。\n- 提供后台管理页面所需的数据接口，包括文档状态、命中统计和热门问题排行。\n\n## 专业技能\n\n- Java：Spring Boot、MyBatis Plus、Maven、REST API、权限控制。\n- Python：FastAPI、脚本自动化、日志处理、简单数据清洗。\n- 数据库：MySQL、Redis、Elasticsearch，熟悉常见索引和慢查询排查。\n- 工程：Docker、Linux、Nginx、GitLab CI、Jenkins、Prometheus 基础监控。` }
</script>

<style scoped src="./resume-writer/resume-writer.css"></style>
