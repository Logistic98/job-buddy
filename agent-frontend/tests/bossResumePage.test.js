import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BossResumePage from '../src/components/BossResumePage.vue'

const mocks = vi.hoisted(() => ({
  generateJobProfileSummary: vi.fn(),
  getJobProfile: vi.fn(),
  saveJobProfile: vi.fn(),
}))

vi.mock('../src/api/resume', () => ({
  deleteResume: vi.fn(),
  generateJobProfileSummary: mocks.generateJobProfileSummary,
  getJobProfile: mocks.getJobProfile,
  getResume: vi.fn(),
  listResumes: vi.fn(),
  saveJobProfile: mocks.saveJobProfile,
  syncBossOnlineResume: vi.fn(),
  updateResumeParsed: vi.fn(),
  uploadResume: vi.fn(),
}))

vi.mock('../src/api/workspace', () => ({
  getWorkspaceState: vi.fn(),
  saveWorkspaceState: vi.fn(),
}))

const profile = {
  resumeId: 'profile-1',
  parsed: {
    education_experiences: [
      { school: '示例理工大学', major: '计算机科学', degree: '本科', startDate: '2014-09', endDate: '2018-06' },
      { school: '示例科技大学', major: '软件工程', degree: '硕士', startDate: '2018-09', endDate: '2021-06' },
    ],
    work_experiences: [
      { company: '示例云服务公司', position: 'Java 工程师', startDate: '2021-07', endDate: '2023-06' },
      { company: '示例智能科技公司', position: 'Agent 平台工程师', startDate: '2023-07', endDate: '2026-07' },
    ],
    project_experiences: [
      {
        name: '示例知识库助手',
        role: '项目技术负责人',
        startDate: '2024-01',
        endDate: '',
        techStack: 'Agent, RAG, LangGraph, Spring Boot, Redis',
        background: '面向企业知识服务的示例知识库助手',
        responsibility: '负责平台架构设计',
        achievement: '推动平台按期交付',
      },
      { name: '示例数据问答项目', role: '项目研发负责人', techStack: 'Text2SQL, DeepResearch, FastAPI' },
    ],
  },
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  mocks.getJobProfile.mockResolvedValue(profile)
  mocks.generateJobProfileSummary.mockResolvedValue({
    oldSummary: '',
    newSummary: 'AI 提取后的画像摘要',
    highlights: ['项目经历'],
    provider: 'AI',
  })
  mocks.saveJobProfile.mockResolvedValue({ resumeId: 'profile-1', parsed: profile.parsed })
})

describe('BossResumePage profile overview', () => {
  it('counts only experience entries containing a non-blank field', async () => {
    mocks.getJobProfile.mockResolvedValueOnce({
      resumeId: 'profile-empty',
      parsed: {
        education_experiences: [],
        work_experiences: [],
        project_experiences: [],
      },
    })
    const wrapper = mount(BossResumePage)
    await flushPromises()
    const sectionTab = (label) =>
      wrapper.findAll('.profile-section-nav button').find((button) => button.text().includes(label))

    expect(sectionTab('教育经历').get('small').text()).toBe('0')
    expect(sectionTab('工作经历').get('small').text()).toBe('0')
    expect(sectionTab('项目经历').get('small').text()).toBe('0')

    await sectionTab('教育经历').trigger('click')
    const schoolInput = wrapper.get('.profile-experience-section input[placeholder="学校名称"]')
    await schoolInput.setValue('   ')
    expect(sectionTab('教育经历').get('small').text()).toBe('0')

    await schoolInput.setValue('示例理工大学')
    expect(sectionTab('教育经历').get('small').text()).toBe('1')
  })

  it('moves the overview out of section navigation and opens it from the page header', async () => {
    const wrapper = mount(BossResumePage)
    await flushPromises()

    const sectionLabels = wrapper.findAll('.profile-section-nav button').map((button) => button.text())
    expect(sectionLabels).not.toContain('画像概览')
    expect(sectionLabels).not.toContain('技能标签')
    expect(sectionLabels[0]).toContain('个人简介')
    const profileCards = wrapper.findAll('.profile-section-content > section')
    const skillCardIndex = profileCards.findIndex((section) => section.find('h2').text() === '技能标签')
    expect(profileCards[skillCardIndex - 1].find('h2').text()).toBe('求职期望')
    expect(profileCards[skillCardIndex].attributes('style') || '').not.toContain('display: none')
    expect(wrapper.find('.profile-overview-modal-card').exists()).toBe(false)

    await wrapper.get('.profile-overview-trigger').trigger('click')
    expect(wrapper.get('.profile-overview-modal-card').text()).toContain('AI 提取画像')
    expect(wrapper.get('.profile-overview-modal-head').exists()).toBe(true)
    expect(wrapper.get('.profile-overview-modal-body').exists()).toBe(true)
    expect(wrapper.get('.profile-overview-actions').exists()).toBe(true)
    expect(wrapper.findAll('.profile-overview-modal-card .profile-overview-close')).toHaveLength(1)
    expect(wrapper.findAll('.profile-overview-actions button')).toHaveLength(1)
    expect(wrapper.get('.profile-overview-actions button').text()).toBe('保存')
    expect(wrapper.get('.profile-overview-actions button').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('.profile-overview-status').text()).toBe('已同步')
    expect(wrapper.get('.profile-overview-meta .saved').text()).toContain('已保存')

    await wrapper.get('.profile-overview-editor textarea').setValue('手动编辑后的画像摘要')
    expect(wrapper.get('.profile-save-indicator').text()).toContain('有未保存的修改')
    expect(wrapper.get('.profile-overview-status').text()).toBe('待保存')
    expect(wrapper.get('.profile-overview-meta .dirty').text()).toContain('保存后生效')

    await wrapper.get('.profile-overview-actions button').trigger('click')
    await flushPromises()
    expect(mocks.saveJobProfile).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.profile-overview-modal-card').exists()).toBe(true)
    expect(wrapper.get('.profile-overview-actions button').text()).toBe('保存')
  })

  it('saves a cleared overview without automatically generating a replacement', async () => {
    mocks.getJobProfile.mockResolvedValueOnce({
      ...profile,
      parsed: { ...profile.parsed, summary: '已有画像摘要' },
    })
    const wrapper = mount(BossResumePage)
    await flushPromises()

    await wrapper.get('.profile-overview-trigger').trigger('click')
    await wrapper.get('.profile-overview-editor textarea').setValue('')
    await wrapper.get('.profile-overview-actions button').trigger('click')
    await flushPromises()

    expect(mocks.saveJobProfile).toHaveBeenCalledTimes(1)
    expect(mocks.saveJobProfile.mock.calls[0][0].summary).toBe('')
    expect(mocks.generateJobProfileSummary).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('求职画像已保存。')
  })

  it('extracts the overview from the latest unsaved profile information', async () => {
    const wrapper = mount(BossResumePage)
    await flushPromises()

    const expectedPositionField = wrapper.findAll('label').find((label) => label.text().includes('期望岗位'))
    await expectedPositionField.get('input').setValue('Java 大模型应用开发工程师')
    await wrapper.get('.profile-overview-trigger').trigger('click')
    await wrapper.get('.profile-summary-ai-button').trigger('click')
    await flushPromises()

    expect(mocks.generateJobProfileSummary).toHaveBeenCalledTimes(1)
    expect(mocks.saveJobProfile).not.toHaveBeenCalled()
    expect(mocks.generateJobProfileSummary.mock.calls[0][0].expected_titles).toEqual(['Java', '大模型应用开发工程师'])
    expect(wrapper.get('.profile-overview-editor textarea').element.value).toBe('AI 提取后的画像摘要')
  })

  it('keeps summary compare controls fixed and does not render AI highlights', async () => {
    mocks.getJobProfile.mockResolvedValueOnce({
      ...profile,
      parsed: { ...profile.parsed, summary: '当前画像摘要' },
    })
    const wrapper = mount(BossResumePage)
    await flushPromises()

    await wrapper.get('.profile-overview-trigger').trigger('click')
    await wrapper.get('.profile-summary-ai-button').trigger('click')
    await flushPromises()

    const modal = wrapper.get('.profile-ai-modal-card')
    expect(modal.get('.profile-ai-modal-head .close').attributes('aria-label')).toBe('关闭摘要对比')
    expect(modal.get('.profile-ai-modal-body').text()).toContain('差异对比')
    expect(modal.get('.profile-ai-modal-actions').text()).toContain('使用 AI 版本')
    expect(modal.text()).not.toContain('AI 提炼依据')
    expect(modal.find('.summary-highlights').exists()).toBe(false)
  })
})

describe('BossResumePage editable profile fields', () => {
  it('keeps custom salary and industry values and provides an uncertain arrival option', async () => {
    mocks.getJobProfile.mockResolvedValueOnce({
      ...profile,
      parsed: {
        ...profile.parsed,
        job_status: { arrivalTime: '不确定' },
        job_expectations: {
          position: 'Java 大模型应用开发工程师',
          salary: '40-50K·14薪',
          industry: '大模型基础设施',
        },
      },
    })
    const wrapper = mount(BossResumePage)
    await flushPromises()

    const salaryField = wrapper.findAll('label').find((label) => label.text().includes('期望薪资'))
    const industryField = wrapper.findAll('label').find((label) => label.text().includes('期望行业'))
    const arrivalField = wrapper.findAll('label').find((label) => label.text().includes('到岗时间'))

    expect(salaryField.get('input').element.value).toBe('40-50K·14薪')
    expect(salaryField.get('input').attributes('role')).toBe('combobox')
    expect(industryField.get('input').element.value).toBe('大模型基础设施')
    expect(arrivalField.get('select').element.value).toBe('不确定')
    expect(arrivalField.findAll('option').some((option) => option.text() === '不确定')).toBe(true)

    await salaryField.get('.profile-editable-select-toggle').trigger('click')
    const salaryOptions = salaryField.findAll('.profile-editable-select-menu [role="option"]')
    expect(salaryOptions).toHaveLength(9)
    await salaryOptions.find((option) => option.text() === '50K以上').trigger('mousedown')
    expect(salaryField.get('input').element.value).toBe('50K以上')
    expect(salaryField.find('.profile-editable-select-menu').exists()).toBe(false)

    await industryField.get('.profile-editable-select-toggle').trigger('click')
    expect(industryField.findAll('.profile-editable-select-menu [role="option"]')).toHaveLength(8)
    await industryField.get('input').setValue('大模型应用')
    expect(industryField.get('input').element.value).toBe('大模型应用')
  })

  it('uses the shared unbounded month picker for education and supports college', async () => {
    const wrapper = mount(BossResumePage)
    await flushPromises()
    const educationTab = wrapper
      .findAll('.profile-section-nav button')
      .find((button) => button.text().includes('教育经历'))
    await educationTab.trigger('click')

    const educationCard = wrapper.find('.profile-experience-card')
    const startField = educationCard.findAll('label').find((label) => label.text().includes('入学时间'))
    const endField = educationCard.findAll('label').find((label) => label.text().includes('毕业时间'))

    expect(startField.get('.app-date-picker-trigger').attributes('aria-label')).toBe('请选择入学时间')
    expect(endField.get('.app-date-picker-trigger').attributes('aria-label')).toBe('请选择毕业时间')
    expect(startField.find('input[type="month"]').exists()).toBe(false)
    expect(educationCard.findAll('label').map((label) => label.find('span').text())).toEqual([
      '学校名称',
      '学院',
      '专业',
      '学历',
      '入学时间',
      '毕业时间',
      '是否全日制',
      '学历状态',
    ])
    expect(educationCard.text()).toContain('学院')
    expect(educationCard.text()).not.toContain('在校时间')

    const collegeField = educationCard.findAll('label').find((label) => label.text().includes('学院'))
    await collegeField.get('input').setValue('计算机学院')
    startField.getComponent({ name: 'AppDatePicker' }).vm.$emit('update:modelValue', '2031-09')
    endField.getComponent({ name: 'AppDatePicker' }).vm.$emit('update:modelValue', '2032-06')
    await wrapper.vm.$nextTick()
    expect(startField.get('.app-date-picker-trigger').text()).toContain('2031年9月')

    await wrapper.get('.profile-save-state .primary-btn').trigger('click')
    await flushPromises()
    const savedEducation = mocks.saveJobProfile.mock.calls[0][0].education_experiences[0]
    expect(savedEducation.college).toBe('计算机学院')
    expect(savedEducation.startDate).toBe('2031-09')
    expect(savedEducation).not.toHaveProperty('period')
  })
})

describe('BossResumePage project experience', () => {
  it('removes the clear-profile action and keeps projects collapsed initially', async () => {
    const wrapper = mount(BossResumePage)
    await flushPromises()

    expect(wrapper.text()).not.toContain('清空画像')
    const projectTab = wrapper
      .findAll('.profile-section-nav button')
      .find((button) => button.text().includes('项目经历'))
    await projectTab.trigger('click')

    const cards = wrapper.findAll('.profile-project-card')
    expect(cards).toHaveLength(2)
    expect(cards.every((card) => !card.element.open)).toBe(true)
    expect(cards[0].text()).toContain('项目技术负责人')
    expect(cards[0].text()).toContain('2024.01-至今')
    expect(cards[0].text()).toContain('LangGraph')
    expect(cards[0].text()).toContain('+1')
  })

  it('edits project dates and tech tags and saves introductions, responsibilities and contributions separately', async () => {
    const wrapper = mount(BossResumePage)
    await flushPromises()
    const projectTab = wrapper
      .findAll('.profile-section-nav button')
      .find((button) => button.text().includes('项目经历'))
    await projectTab.trigger('click')

    const card = wrapper.find('.profile-project-card')
    card.element.open = true
    await card.trigger('toggle')
    const startField = card.findAll('label').find((label) => label.text().includes('开始时间'))
    const endField = card.findAll('label').find((label) => label.text().includes('结束时间'))
    expect(startField.getComponent({ name: 'AppDatePicker' }).exists()).toBe(true)
    expect(endField.getComponent({ name: 'AppDatePicker' }).exists()).toBe(true)
    expect(card.find('input[type="month"]').exists()).toBe(false)
    expect(card.text()).toContain('项目简介')
    expect(card.text()).toContain('项目职责')
    expect(card.text()).toContain('主要贡献')
    const projectTextareas = Object.fromEntries(
      card
        .findAll('label')
        .filter((label) => ['项目简介', '项目职责', '主要贡献'].includes(label.find('span').text()))
        .map((label) => [label.find('span').text(), label.get('textarea')]),
    )
    expect(projectTextareas['项目简介'].attributes('rows')).toBe('3')
    expect(projectTextareas['项目职责'].attributes('rows')).toBe('2')
    expect(projectTextareas['主要贡献'].attributes('rows')).toBe('8')

    await card.get('.profile-project-tech-input-row input').setValue('ClickHouse')
    await card.get('.profile-project-tech-input-row button').trigger('click')
    expect(card.findAll('.profile-project-tech-tag-list > span').some((tag) => tag.text().includes('ClickHouse'))).toBe(
      true,
    )

    await wrapper.get('.profile-save-state .primary-btn').trigger('click')
    await flushPromises()
    const savedProject = mocks.saveJobProfile.mock.calls[0][0].project_experiences[0]
    expect(savedProject.startDate).toBe('2024-01')
    expect(savedProject.endDate).toBe('')
    expect(savedProject.techStack).toContain('ClickHouse')
    expect(savedProject).not.toHaveProperty('techDraft')
    expect(savedProject.background).toBe('面向企业知识服务的示例知识库助手')
    expect(savedProject.responsibility).toBe('负责平台架构设计')
    expect(savedProject.achievement).toBe('推动平台按期交付')
  })

  it('loads a historical project introduction alias into the dedicated field', async () => {
    mocks.getJobProfile.mockResolvedValueOnce({
      resumeId: 'profile-project-introduction',
      parsed: {
        project_experiences: [
          {
            name: '历史项目',
            project_introduction: '历史字段中的项目简介',
          },
        ],
      },
    })
    const wrapper = mount(BossResumePage)
    await flushPromises()
    const projectTab = wrapper
      .findAll('.profile-section-nav button')
      .find((button) => button.text().includes('项目经历'))
    await projectTab.trigger('click')

    const card = wrapper.get('.profile-project-card')
    card.element.open = true
    await card.trigger('toggle')
    const introduction = card.findAll('label').find((label) => label.text().includes('项目简介'))
    expect(introduction.get('textarea').element.value).toBe('历史字段中的项目简介')
  })

  it('allows multiple project cards to stay expanded', async () => {
    const wrapper = mount(BossResumePage)
    await flushPromises()
    const projectTab = wrapper
      .findAll('.profile-section-nav button')
      .find((button) => button.text().includes('项目经历'))
    await projectTab.trigger('click')

    const cards = wrapper.findAll('.profile-project-card')
    cards[0].element.open = true
    await cards[0].trigger('toggle')
    cards[1].element.open = true
    await cards[1].trigger('toggle')
    await flushPromises()

    expect(cards[0].element.open).toBe(true)
    expect(cards[1].element.open).toBe(true)
    expect(cards.every((card) => card.text().includes('收起'))).toBe(true)
    expect(cards[1].text()).toContain('删除这段项目经历')
  })

  it.each([
    ['教育经历', 'education'],
    ['工作经历', 'work'],
  ])('allows multiple %s cards to stay expanded', async (sectionLabel, sectionKey) => {
    const wrapper = mount(BossResumePage)
    await flushPromises()
    const sectionTab = wrapper
      .findAll('.profile-section-nav button')
      .find((button) => button.text().includes(sectionLabel))
    await sectionTab.trigger('click')

    const section = wrapper
      .findAll('.profile-experience-section')
      .find((item) => item.find('h2').text() === sectionLabel)
    const cards = section.findAll('.profile-experience-card')
    expect(cards).toHaveLength(2)
    expect(cards[0].element.open).toBe(true)
    cards[1].element.open = true
    await cards[1].trigger('toggle')
    await flushPromises()

    expect(cards[0].element.open).toBe(true)
    expect(cards[1].element.open).toBe(true)
    expect(cards.every((card) => card.text().includes('收起'))).toBe(true)
    expect(wrapper.text()).toContain(sectionKey === 'education' ? '示例科技大学' : '示例智能科技公司')
  })

  it('opens a newly added experience without collapsing existing cards', async () => {
    const wrapper = mount(BossResumePage)
    await flushPromises()
    const workTab = wrapper.findAll('.profile-section-nav button').find((button) => button.text().includes('工作经历'))
    await workTab.trigger('click')

    const addButton = wrapper
      .findAll('.profile-section-title button')
      .find((button) => button.text().includes('添加工作经历'))
    await addButton.trigger('click')
    await flushPromises()

    const workSection = wrapper
      .findAll('.profile-experience-section')
      .find((item) => item.find('h2').text() === '工作经历')
    const cards = workSection.findAll('.profile-experience-card')
    expect(cards).toHaveLength(3)
    expect(cards[0].element.open).toBe(true)
    expect(cards[2].element.open).toBe(true)
  })
})
