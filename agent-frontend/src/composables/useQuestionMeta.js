// Question-bank metadata shared by the bank panel and practice desk.

import { computed, reactive } from 'vue'
import { getQuestionMeta } from '../api/interview'

const bankTypeDisplayName = { leetcode: '算法题库', qa: '问答题库' }

export function useQuestionMeta() {
  const questionMeta = reactive({ bankTypeOptions: [], categories: [], difficulties: [], questionTypes: [] })

  async function loadQuestionMeta(bankType) {
    const data = await getQuestionMeta({ bankType, _ts: Date.now() })
    Object.assign(questionMeta, {
      bankTypeOptions: Array.isArray(data.bankTypeOptions) ? data.bankTypeOptions : [],
      categories: Array.isArray(data.categories) ? data.categories : [],
      difficulties: Array.isArray(data.difficulties) ? data.difficulties : [],
      questionTypes: Array.isArray(data.questionTypes) ? data.questionTypes : [],
    })
  }

  const bankTypeOptions = computed(() =>
    questionMeta.bankTypeOptions.map((item) => ({
      ...item,
      label: bankTypeDisplayName[item.value] || item.label,
    })),
  )
  const categories = computed(() => [...questionMeta.categories].sort())
  const difficulties = computed(() => questionMeta.difficulties)
  const questionTypes = computed(() => questionMeta.questionTypes)

  function bankTypeLabel(value) {
    const option = bankTypeOptions.value.find((item) => item.value === value)
    if (option) return option.label
    return bankTypeDisplayName[value] || value || '题库'
  }

  return { questionMeta, loadQuestionMeta, bankTypeOptions, categories, difficulties, questionTypes, bankTypeLabel }
}
