<template>
  <div>
    <p v-if="error" class="error settings-error">{{ error }}</p>
    <div v-if="loading" class="empty-state">
      <strong>正在加载设置</strong>
      <p>请稍候。</p>
    </div>
    <section v-if="blacklist" class="settings-section">
      <div class="setting-card">
        <div class="memory-card-head">
          <div>
            <h3>岗位屏蔽规则</h3>
            <p>公司与关键词分别匹配，避免公司名称误命中岗位描述，或岗位关键词误命中公司名称。</p>
          </div>
          <span v-if="dirty" class="blacklist-dirty-hint">有未保存修改</span>
        </div>
        <div class="blacklist-master">
          <label class="switch-field">
            <input v-model="blacklist.enabled" type="checkbox" />
            <span class="switch-track" aria-hidden="true"></span>
            <span class="switch-text"
              ><strong>{{ blacklist.enabled ? '岗位屏蔽已启用' : '岗位屏蔽已关闭' }}</strong
              ><small>{{
                blacklist.enabled ? '命中规则的岗位会在推荐和打分前过滤。' : '规则暂不生效，下方修改仍会保留。'
              }}</small></span
            >
          </label>
          <div class="blacklist-match-mode"><strong>公司规则</strong><small>仅匹配公司或品牌名称。</small></div>
          <div class="blacklist-match-mode">
            <strong>关键词规则</strong><small>仅匹配岗位名称、描述、标签和福利信息。</small>
          </div>
        </div>
        <p v-if="!blacklist.enabled" class="blacklist-disabled-banner">
          岗位屏蔽当前已关闭，以下公司和关键词暂不参与岗位过滤。
        </p>
      </div>

      <div class="blacklist-rule-grid">
        <div v-for="type in types" :key="type.key" class="setting-card blacklist-rule-card">
          <div class="blacklist-rule-head">
            <div>
              <span :class="['blacklist-type-label', type.key]">{{ type.shortLabel }}</span>
              <h3>{{ type.title }}</h3>
              <p>{{ type.description }}</p>
            </div>
            <strong>{{ scopedItems(type.key).length }} {{ type.unit }}</strong>
          </div>
          <div class="blacklist-add-row">
            <input
              v-model.trim="forms[type.key]"
              :aria-label="type.inputLabel"
              maxlength="100"
              :placeholder="type.placeholder"
              @keyup.enter="addItem(type.key)"
            />
            <button class="primary-btn" :disabled="!forms[type.key]" @click="addItem(type.key)">
              {{ type.addLabel }}
            </button>
          </div>
          <p v-if="feedback[type.key].text" :class="['blacklist-feedback', feedback[type.key].level]">
            {{ feedback[type.key].text }}
          </p>
          <div class="blacklist-toolbar">
            <input
              v-model.trim="filters[type.key]"
              :aria-label="type.searchLabel"
              maxlength="100"
              :placeholder="type.searchPlaceholder"
            />
          </div>
          <div
            :class="['source-list', 'memory-list', 'blacklist-item-list', { 'blacklist-muted': !blacklist.enabled }]"
          >
            <article
              v-for="item in visibleItems(type.key)"
              :key="item.id || `${type.key}-${item.name}`"
              class="source-item memory-item"
            >
              <div>
                <strong>{{ item.name }}</strong>
                <p>{{ item.reason || type.defaultReason }}</p>
                <small>{{ item.source === 'system' ? '系统初始化' : '手动维护' }}</small>
              </div>
              <div class="source-badges">
                <span :class="['source-state', item.enabled ? 'enabled' : 'disabled']">{{
                  item.enabled ? '启用' : '停用'
                }}</span
                ><button class="secondary-btn" @click="toggleItem(item)">{{ item.enabled ? '停用' : '启用' }}</button
                ><button v-if="item.source !== 'system'" class="danger-text" @click="removeItem(item)">删除</button>
              </div>
            </article>
            <div v-if="!visibleItems(type.key).length" class="empty-state compact">
              <strong>{{ scopedItems(type.key).length ? type.noMatch : type.empty }}</strong>
              <p>{{ scopedItems(type.key).length ? type.retry : type.emptyHelp }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import {
  createBlacklistItem,
  filterBlacklistItems,
  findBlacklistDuplicate,
} from '../../composables/useCompanyBlacklist'
import { useScopedSettings } from '../../composables/useScopedSettings'
import { validateLength } from '../../utils/formValidation'

const emit = defineEmits(['state-change'])
const normalizeBlacklist = (value) => ({
  enabled: value?.enabled ?? true,
  matchMode: value?.matchMode || 'contains',
  items: Array.isArray(value?.items) ? value.items : [],
})
const {
  value: blacklist,
  loading,
  saving,
  error,
  dirty,
  load,
  save,
} = useScopedSettings('blacklist', normalizeBlacklist)
const forms = ref({ company: '', keyword: '' })
const filters = ref({ company: '', keyword: '' })
const feedback = ref(emptyFeedback())
const types = [
  {
    key: 'company',
    shortLabel: '公司',
    title: '屏蔽公司',
    description: '只检查岗位的公司或品牌名称，不扫描岗位描述。',
    unit: '家',
    inputLabel: '屏蔽公司名称',
    placeholder: '输入公司或品牌名称，2-100 字',
    addLabel: '添加公司',
    searchLabel: '搜索屏蔽公司',
    searchPlaceholder: '搜索公司名称或说明',
    defaultReason: '公司名称屏蔽',
    noMatch: '没有匹配的公司',
    empty: '暂无屏蔽公司',
    retry: '换个名称搜索。',
    emptyHelp: '添加后将只按公司名称过滤岗位。',
  },
  {
    key: 'keyword',
    shortLabel: '关键词',
    title: '屏蔽关键词',
    description: '检查岗位名称、描述、标签和福利，不匹配公司名称。',
    unit: '个',
    inputLabel: '屏蔽关键词',
    placeholder: '例如：外包、驻场，1-100 字',
    addLabel: '添加关键词',
    searchLabel: '搜索屏蔽关键词',
    searchPlaceholder: '搜索关键词或说明',
    defaultReason: '岗位内容关键词屏蔽',
    noMatch: '没有匹配的关键词',
    empty: '暂无屏蔽关键词',
    retry: '换个关键词搜索。',
    emptyHelp: '添加后将按岗位内容过滤岗位。',
  },
]

watch(
  [dirty, saving, loading],
  () => emit('state-change', { key: 'blacklist', dirty: dirty.value, saving: saving.value, loading: loading.value }),
  { immediate: true },
)
load()
defineExpose({ save })

function items() {
  return blacklist.value?.items || []
}
function scopedItems(type) {
  return filterBlacklistItems(items(), { scope: type })
}
function visibleItems(type) {
  return filterBlacklistItems(items(), { scope: type, keyword: filters.value[type] })
}
function emptyFeedback() {
  return { company: { level: '', text: '' }, keyword: { level: '', text: '' } }
}
function setFeedback(type, level, text) {
  feedback.value[type] = { level, text }
}
function typeLabel(type) {
  return type === 'keyword' ? '关键词' : '公司'
}
function addItem(type) {
  const name = forms.value[type]
  if (!blacklist.value?.items) return
  try {
    validateLength(name, typeLabel(type), { min: type === 'company' ? 2 : 1, max: 100, required: true })
    if (/[\r\n\t]/.test(name)) throw new Error(`${typeLabel(type)}不能包含换行或制表符`)
  } catch (err) {
    setFeedback(type, 'warning', err.message)
    return
  }
  const duplicate = findBlacklistDuplicate(blacklist.value.items, type, name)
  if (duplicate) {
    if (duplicate.enabled) return setFeedback(type, 'warning', `「${duplicate.name}」已启用，无需重复添加。`)
    duplicate.enabled = true
    blacklist.value.items = [duplicate, ...blacklist.value.items.filter((row) => row !== duplicate)]
    setFeedback(type, 'success', `「${duplicate.name}」已重新启用，保存设置后生效。`)
  } else {
    blacklist.value.items.unshift(createBlacklistItem(type, name))
    setFeedback(type, 'success', `已添加${typeLabel(type)}，保存设置后生效。`)
  }
  forms.value[type] = ''
}
function toggleItem(item) {
  item.enabled = !item.enabled
  setFeedback(item.type, 'success', `「${item.name}」已${item.enabled ? '启用' : '停用'}，保存设置后生效。`)
}
function removeItem(item) {
  if (!window.confirm(`确认删除${typeLabel(item.type)}「${item.name}」？保存设置后生效。`)) return
  blacklist.value.items = blacklist.value.items.filter((row) => row !== item)
  setFeedback(item.type, 'success', `已删除「${item.name}」，保存设置后生效。`)
}
</script>
