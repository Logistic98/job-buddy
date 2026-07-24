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
        <span class="form-required">文件名</span>
        <input
          v-model="fileName"
          aria-required="true"
          maxlength="120"
          aria-label="导出文件名"
          placeholder="请输入导出文件名，最多 120 字且不能包含路径非法字符"
          @input="saveFileName"
          @blur="normalizeFileName"
        />
        <small v-if="fileNameError" class="form-error-alert" role="alert" aria-live="assertive">{{
          fileNameError
        }}</small>
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
          ><span class="form-required">版本说明</span
          ><input
            v-model.trim="versionTitle"
            aria-required="true"
            maxlength="256"
            placeholder="例如：补充项目量化成果"
            @keydown.enter.prevent="saveManualVersion"
        /></label>
        <p v-if="versionError" class="form-error-alert" role="alert" aria-live="assertive">{{ versionError }}</p>
        <div class="writer-dialog-actions">
          <button class="primary-btn" :disabled="savingVersion" @click="saveManualVersion">
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
import { useResumeWriterPage } from '../composables/useResumeWriterPage'

const {
  photoUrl,
  photoUploading,
  photoSettings,
  selectedPhoto,
  markdown,
  fileName,
  savedAt,
  exportingPdf,
  loadingExample,
  showPhotoModal,
  showIconModal,
  showVersionHistory,
  showSaveVersionDialog,
  versionTitle,
  versions,
  previewVersion,
  previewMarkdown,
  versionsLoading,
  versionError,
  fileNameError,
  savingVersion,
  editorMode,
  fileInput,
  photoInput,
  textareaRef,
  measureRef,
  previewCanvas,
  pages,
  pagesScaleStyle,
  paperClasses,
  themeName,
  fontFamily,
  fontSizeOptions,
  lineHeightOptions,
  fontSize,
  lineHeight,
  currentFontSizeLabel,
  currentLineHeightLabel,
  activeCombo,
  displayPhotoLibrary,
  html,
  paperStyle,
  importClick,
  loadExampleResume,
  cycleTheme,
  importPhoto,
  clearPhoto,
  selectPhoto,
  deletePhoto,
  openCombo,
  closeCombo,
  toggleCombo,
  handleComboBlur,
  handleComboEnter,
  selectFontSizeOption,
  selectLineHeightOption,
  setPhotoScale,
  resetPhotoAdjust,
  handlePreviewClick,
  handlePreviewPointerDown,
  insertSnippet,
  saveDraft,
  importMarkdown,
  openSaveVersionDialog,
  saveManualVersion,
  openVersionHistory,
  previewHistoryVersion,
  restoreHistoryVersion,
  removeHistoryVersion,
  sourceLabel,
  formatVersionTime,
  exportMarkdown,
  exportHtml,
  exportPdf,
  saveFileName,
  normalizeFileName,
  ResumeIconModal,
  ResumePhotoModal,
} = useResumeWriterPage()
</script>

<style scoped src="../styles/modules/resume-writer-controls.css"></style>
<style scoped src="../styles/modules/resume-writer-preview.css"></style>
<style scoped src="../styles/modules/resume-writer-layout.css"></style>
