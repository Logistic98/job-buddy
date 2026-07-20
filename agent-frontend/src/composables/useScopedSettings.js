import { computed, ref, watch } from 'vue'
import { getSettings, saveSettings } from '../api/settings'

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value))
}

export function useScopedSettings(scope, normalize = (value) => value || {}) {
  const value = ref(null)
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  const persisted = ref('')

  const dirty = computed(() => value.value != null && JSON.stringify(value.value) !== persisted.value)

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const settings = await getSettings()
      setValue(settings?.[scope])
      return settings
    } catch (err) {
      error.value = err.message || '设置加载失败'
      return null
    } finally {
      loading.value = false
    }
  }

  async function save() {
    if (value.value == null) return null
    saving.value = true
    error.value = ''
    try {
      const normalized = normalize(clone(value.value))
      const settings = await saveSettings({ [scope]: normalized })
      setValue(settings?.[scope] ?? normalized)
      return settings
    } catch (err) {
      error.value = err.message || '设置保存失败'
      return null
    } finally {
      saving.value = false
    }
  }

  function setValue(nextValue) {
    const normalized = normalize(clone(nextValue))
    value.value = normalized
    persisted.value = JSON.stringify(normalized)
  }

  watch(value, (nextValue) => {
    if (nextValue == null) persisted.value = ''
  })

  return { value, loading, saving, error, dirty, load, save, setValue }
}
