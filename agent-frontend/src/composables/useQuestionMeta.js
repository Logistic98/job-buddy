// Question-bank metadata (bank types, categories, difficulties, question types) shared by the
// bank panel and the practice desk. Loads from the interview meta API with static fallbacks;
// an optional fallbackCategoriesSource lets the bank panel derive categories from loaded rows.

import { computed, reactive } from 'vue'
import { getQuestionMeta } from '../api/interview'

const bankTypeDisplayName = { leetcode: '算法题库', baguwen: '问答题库' }

export function useQuestionMeta(fallbackCategoriesSource = () => []) {
  const questionMeta = reactive({ bankTypeOptions: [], categories: [], difficulties: [], questionTypes: [] })

  async function loadQuestionMeta(bankType) {
    try {
      const data = await getQuestionMeta({ bankType, _ts: Date.now() })
      Object.assign(questionMeta, {
        bankTypeOptions: Array.isArray(data.bankTypeOptions) ? data.bankTypeOptions : questionMeta.bankTypeOptions,
        categories: Array.isArray(data.categories) ? data.categories : [],
        difficulties: Array.isArray(data.difficulties) ? data.difficulties : [],
        questionTypes: Array.isArray(data.questionTypes) ? data.questionTypes : [],
      })
    } catch (_) {}
  }

  const bankTypeOptions = computed(() => {
    const options = questionMeta.bankTypeOptions?.length ? questionMeta.bankTypeOptions : [
      { value: 'leetcode', label: bankTypeDisplayName.leetcode },
      { value: 'baguwen', label: bankTypeDisplayName.baguwen },
    ]
    return options.map(item => ({ ...item, label: bankTypeDisplayName[item.value] || item.label }))
  })
  const categories = computed(() => (questionMeta.categories?.length ? questionMeta.categories : fallbackCategoriesSource()).sort())
  const difficulties = computed(() => (questionMeta.difficulties?.length ? questionMeta.difficulties : ['简单', '中等', '困难']))
  const questionTypes = computed(() => (questionMeta.questionTypes?.length ? questionMeta.questionTypes : ['编程题', '单选', '多选', '判断', '简答']))

  function bankTypeLabel(value) {
    const option = bankTypeOptions.value.find(item => item.value === value)
    if (option) return option.label
    return bankTypeDisplayName[value] || value || '题库'
  }

  return { questionMeta, loadQuestionMeta, bankTypeOptions, categories, difficulties, questionTypes, bankTypeLabel }
}
