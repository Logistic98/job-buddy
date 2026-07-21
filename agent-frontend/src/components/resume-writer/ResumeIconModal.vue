<template>
  <div class="resume-modal-mask" @click.self="$emit('close')">
    <section class="resume-modal-card icon-modal-card">
      <header>
        <div>
          <h3>图标快捷写法</h3>
          <p>点击图标会自动复制 <code>icon:name</code>，并插入到当前 Markdown 光标位置。</p>
        </div>
        <button @click="$emit('close')">×</button>
      </header>
      <input v-model.trim="iconKeyword" class="icon-search-input" placeholder="搜索 icon" />
      <div class="icon-grid rich-icon-grid">
        <button v-for="icon in pagedIcons" :key="icon" @click="copyIcon(icon)">
          <!-- eslint-disable-next-line vue/no-v-html iconSvg 仅返回内置图标枚举的 SVG，经 sanitizeResumeHtml 清洗后渲染 -->
          <span class="icon-preview" v-html="safeIconSvg(icon)"></span>
          <b>{{ icon }}</b>
          <small>icon:{{ icon }}</small>
        </button>
      </div>
      <div class="icon-pager">
        <button :disabled="iconPage <= 1" @click="iconPage--">‹</button>
        <button
          v-for="page in iconPageCount"
          :key="page"
          :class="{ active: iconPage === page }"
          @click="iconPage = page"
        >
          {{ page }}
        </button>
        <button :disabled="iconPage >= iconPageCount" @click="iconPage++">›</button>
      </div>
      <p v-if="iconCopied" class="icon-copy-hint">已复制并插入：{{ iconCopied }}</p>
    </section>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { iconSvg } from '../../utils/resumeRender'
import { sanitizeResumeHtml } from '../../utils/sanitizeHtml'

const emit = defineEmits(['close', 'insert'])

function safeIconSvg(icon) {
  return sanitizeResumeHtml(iconSvg(icon))
}

const ICON_PAGE_SIZE = 20
const iconOptions = [
  'info',
  'phone',
  'email',
  'blog',
  'github',
  'weixin',
  'yuque',
  'zhihu',
  'gitee',
  'weibo',
  'qq',
  'twitter',
  'facebook',
  'csdn',
  'juejin',
  'tag',
  'sun',
  'star',
  'code',
  'project',
  'user',
  'briefcase',
  'company',
  'school',
  'certificate',
  'award',
  'skill',
  'tool',
  'database',
  'server',
  'cloud',
  'robot',
  'chart',
  'rocket',
  'target',
  'location',
  'calendar',
  'time',
  'money',
  'link',
  'download',
  'upload',
  'edit',
  'check',
  'shield',
  'heart',
  'flag',
  'light',
  'book',
  'search',
  'message',
  'meeting',
  'growth',
  'terminal',
  'bug',
  'language',
  'java',
  'python',
  'api',
  'portfolio',
]
const iconKeyword = ref('')
const iconPage = ref(1)
const iconCopied = ref('')
const filteredIcons = computed(() => {
  const q = iconKeyword.value.toLowerCase()
  return q ? iconOptions.filter((icon) => icon.includes(q)) : iconOptions
})
const iconPageCount = computed(() => Math.max(1, Math.ceil(filteredIcons.value.length / ICON_PAGE_SIZE)))
const pagedIcons = computed(() =>
  filteredIcons.value.slice((iconPage.value - 1) * ICON_PAGE_SIZE, iconPage.value * ICON_PAGE_SIZE),
)
watch(iconKeyword, () => {
  iconPage.value = 1
})
watch(iconPageCount, (count) => {
  if (iconPage.value > count) iconPage.value = count
})

async function copyIcon(icon) {
  const token = `icon:${icon}`
  try {
    await navigator.clipboard?.writeText(token)
  } catch (_) {}
  emit('insert', `${token} `)
  iconCopied.value = token
}
</script>

<style scoped>
.resume-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.42);
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 24px;
}
.resume-modal-card {
  width: min(720px, 92vw);
  max-height: 86vh;
  overflow: auto;
  background: #fff;
  border: 1px solid #e5ebf5;
  border-radius: 22px;
  box-shadow: 0 34px 110px rgba(0, 0, 0, 0.24);
  padding: 20px;
}
.resume-modal-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.resume-modal-card h3 {
  margin: 0;
  color: #172033;
}
.resume-modal-card header button {
  border: 0;
  background: transparent;
  font-size: 26px;
  color: #475467;
}
.icon-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.icon-grid button {
  border: 1px solid #dfe6f2;
  background: #fff;
  color: #475467;
  border-radius: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-start;
  padding: 10px 12px;
  font-size: 12px;
  font-weight: 800;
}
</style>
