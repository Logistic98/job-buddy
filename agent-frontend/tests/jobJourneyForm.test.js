import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import JobJourney from '../src/components/JobJourney.vue'

const mocks = vi.hoisted(() => ({
  analyzeProgress: vi.fn(),
  createRecord: vi.fn(),
  listRecords: vi.fn(),
  loadFavorites: vi.fn(),
}))

vi.mock('../src/api/journey', () => ({
  analyzeJourneyProgress: mocks.analyzeProgress,
  createJourneyRecord: mocks.createRecord,
  deleteJourneyRecord: vi.fn(),
  listJourneyRecords: mocks.listRecords,
  updateJourneyRecord: vi.fn(),
}))

vi.mock('../src/stores/job', () => ({
  useJobStore: () => ({
    favorites: [],
    loadFavorites: mocks.loadFavorites,
  }),
}))

beforeEach(() => {
  mocks.analyzeProgress.mockReset().mockResolvedValue({})
  mocks.createRecord.mockReset().mockResolvedValue({ recordId: 'record-created' })
  mocks.listRecords.mockReset().mockResolvedValue([])
  mocks.loadFavorites.mockReset().mockResolvedValue()
})

async function mountJourney() {
  const wrapper = mount(JobJourney)
  await flushPromises()
  return wrapper
}

describe('JobJourney form placeholders', () => {
  it('stretches the analysis loading state across the available modal body', async () => {
    let resolveAnalysis
    mocks.analyzeProgress.mockReturnValue(
      new Promise((resolve) => {
        resolveAnalysis = resolve
      }),
    )
    const wrapper = await mountJourney()

    await wrapper.find('.history-header-actions .secondary-btn').trigger('click')

    expect(wrapper.get('.journey-analysis-body').classes()).toContain('is-loading')
    expect(wrapper.get('.journey-analysis-body > .favorite-analysis-loading').exists()).toBe(true)

    resolveAnalysis({})
    await flushPromises()
  })

  it('removes the redundant list heading and paginates journey records', async () => {
    mocks.listRecords.mockResolvedValue(
      Array.from({ length: 12 }, (_, index) => ({
        recordId: `record-${index + 1}`,
        company: `示例企业 ${index + 1}`,
        positionName: `岗位 ${index + 1}`,
      })),
    )
    const wrapper = await mountJourney()

    expect(wrapper.find('.journey-toolbar-title').exists()).toBe(false)
    expect(wrapper.findAll('.journey-table-row')).toHaveLength(10)
    expect(wrapper.find('.journey-pagination').text()).toContain('第 1 / 2 页，共 12 条')

    const nextButton = wrapper.findAll('.journey-pagination button').find((button) => button.text() === '下一页')
    await nextButton.trigger('click')

    expect(wrapper.findAll('.journey-table-row')).toHaveLength(2)
    expect(wrapper.find('.journey-pagination').text()).toContain('第 2 / 2 页，共 12 条')
  })

  it('hides manual refresh and reloads records automatically after saving', async () => {
    const wrapper = await mountJourney()

    expect(wrapper.findAll('.page-header button').map((button) => button.text())).not.toContain('刷新')

    await wrapper.find('.history-header-actions .primary-btn').trigger('click')
    await wrapper.find('.journey-modal .detail-actions .primary-btn').trigger('click')
    await flushPromises()

    expect(mocks.createRecord).toHaveBeenCalledTimes(1)
    expect(mocks.listRecords).toHaveBeenCalledTimes(2)
  })

  it('shows descriptive hints for empty fields in every form group', async () => {
    const wrapper = await mountJourney()
    await wrapper.find('.history-header-actions .primary-btn').trigger('click')

    expect(wrapper.find('input[placeholder="请输入企业名称，例如：字节跳动"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请输入所在地域，例如：上海"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请输入岗位名称，例如：Agent 与大模型应用开发工程师"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请选择或输入企业性质"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请选择或输入企业规模"]').exists()).toBe(true)

    await wrapper.findAll('.journey-modal-groups button')[1].trigger('click')
    expect(wrapper.find('input[placeholder="请选择或输入类型 / 轮次"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请选择或输入面试形式"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请选择或输入面试结果"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请选择或输入当前状态"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请选择或输入优先级"]').exists()).toBe(true)
    expect(wrapper.find('input[aria-label="请选择面试时间"]').exists()).toBe(true)

    await wrapper.findAll('.journey-modal-groups button')[2].trigger('click')
    expect(wrapper.find('textarea[placeholder="请输入面试或笔试内容、题型和过程"]').exists()).toBe(true)
    expect(wrapper.find('textarea[placeholder="请输入岗位职责、任职要求和技术栈"]').exists()).toBe(true)
    expect(wrapper.find('textarea[placeholder="请输入面试流程、笔试范围和后续安排"]').exists()).toBe(true)

    await wrapper.findAll('.journey-modal-groups button')[3].trigger('click')
    expect(wrapper.find('textarea[placeholder="请输入准备情况、暴露问题和后续改进方向"]').exists()).toBe(true)
    expect(wrapper.find('textarea[placeholder="请输入下一步动作，例如：补充 Agent 题库并安排复习"]').exists()).toBe(
      true,
    )
    expect(wrapper.find('input[placeholder="输入一个标签，例如：重点跟进"]').exists()).toBe(true)
    expect(wrapper.find('.journey-tag-input-row button').text()).toBe('添加标签')
  })

  it('adds, de-duplicates, removes and submits progress tags individually', async () => {
    const wrapper = await mountJourney()
    await wrapper.find('.history-header-actions .primary-btn').trigger('click')
    await wrapper.findAll('.journey-modal-groups button')[3].trigger('click')

    const tagInput = wrapper.find('input[placeholder="输入一个标签，例如：重点跟进"]')
    await tagInput.setValue('重点跟进')
    await tagInput.trigger('keydown.enter')
    await tagInput.setValue('Java')
    await wrapper.find('.journey-tag-input-row button').trigger('click')
    await tagInput.setValue('java')
    await tagInput.trigger('keydown.enter')

    expect(wrapper.findAll('.journey-tag-list > span').map((tag) => tag.text())).toEqual(['重点跟进 ×', 'Java ×'])

    await wrapper.find('button[aria-label="移除标签 重点跟进"]').trigger('click')
    expect(wrapper.findAll('.journey-tag-list > span').map((tag) => tag.text())).toEqual(['Java ×'])

    await tagInput.setValue('待复盘')
    await wrapper.find('.journey-modal .detail-actions .primary-btn').trigger('click')
    await flushPromises()

    expect(mocks.createRecord).toHaveBeenCalledWith(expect.objectContaining({ tags: ['Java', '待复盘'] }))
    expect(mocks.createRecord.mock.calls[0][0]).not.toHaveProperty('tagsText')
  })

  it('opens a new record with every business field empty', async () => {
    const wrapper = await mountJourney()
    await wrapper.find('.history-header-actions .primary-btn').trigger('click')

    for (const field of wrapper.findAll('.journey-group-form input')) {
      expect(field.element.value).toBe('')
    }
    expect(wrapper.find('.journey-group-form select').element.value).toBe('')

    await wrapper.findAll('.journey-modal-groups button')[1].trigger('click')
    for (const field of wrapper.findAll('.journey-group-form input')) {
      expect(field.element.value).toBe('')
    }
  })

  it('accepts custom values outside the suggested options and submits them unchanged', async () => {
    const wrapper = await mountJourney()
    await wrapper.find('.history-header-actions .primary-btn').trigger('click')

    await wrapper.find('input[list="journey-company-nature-options"]').setValue('科研院所')
    await wrapper.find('input[list="journey-company-scale-options"]').setValue('超大型')
    await wrapper.findAll('.journey-modal-groups button')[1].trigger('click')
    await wrapper.find('input[list="journey-interview-round-options"]').setValue('技术加面')
    await wrapper.find('input[list="journey-interview-format-options"]').setValue('视频会议')
    await wrapper.find('input[list="journey-result-options"]').setValue('等待 HC')
    await wrapper.find('input[list="journey-status-options"]').setValue('背调中')
    await wrapper.find('input[list="journey-priority-options"]').setValue('最高')
    await wrapper.find('.journey-modal .detail-actions .primary-btn').trigger('click')
    await flushPromises()

    expect(mocks.createRecord).toHaveBeenCalledWith(
      expect.objectContaining({
        companyNature: '科研院所',
        companyScale: '超大型',
        interviewRound: '技术加面',
        interviewFormat: '视频会议',
        result: '等待 HC',
        status: '背调中',
        priority: '最高',
      }),
    )
  })

  it('normalizes missing optional values to empty editable fields', async () => {
    mocks.listRecords.mockResolvedValue([
      {
        recordId: 'record-1',
        company: '示例企业',
        companyNature: null,
        companyScale: null,
        favoriteKey: null,
        interviewRound: null,
        interviewTime: null,
        interviewFormat: null,
        result: null,
        status: null,
        priority: null,
        tags: null,
      },
    ])
    const wrapper = await mountJourney()
    await wrapper.find('.journey-table-row .row-actions button:nth-child(2)').trigger('click')

    expect(wrapper.find('input[list="journey-company-nature-options"]').element.value).toBe('')
    expect(wrapper.find('input[list="journey-company-scale-options"]').element.value).toBe('')
    expect(wrapper.find('.journey-group-form select').element.value).toBe('')

    await wrapper.findAll('.journey-modal-groups button')[1].trigger('click')
    for (const field of wrapper.findAll('.journey-group-form input')) {
      expect(field.element.value).toBe('')
    }
    expect(wrapper.find('input[type="datetime-local"]').classes()).toContain('is-placeholder')
  })
})
