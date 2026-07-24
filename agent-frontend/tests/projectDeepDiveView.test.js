import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectDeepDive from '../src/components/ProjectDeepDive.vue'
import PracticeMarkdown from '../src/components/interview/PracticeMarkdown.vue'

const mocks = vi.hoisted(() => ({
  route: { path: '/project-deep-dive', query: {} },
  push: vi.fn().mockResolvedValue(),
  replace: vi.fn().mockResolvedValue(),
  list: vi.fn(),
  detail: vi.fn(),
  createProject: vi.fn(),
  updateProject: vi.fn(),
  deleteMaterial: vi.fn(),
  addMaterial: vi.fn(),
  generateQuestions: vi.fn(),
  addQuestion: vi.fn(),
  updateQuestion: vi.fn(),
  deleteQuestion: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
}))

vi.mock('../src/api/projectDeepDive', () => ({
  listDeepDiveProjects: mocks.list,
  getDeepDiveProject: mocks.detail,
  createDeepDiveProject: mocks.createProject,
  deleteDeepDiveProject: vi.fn(),
  updateDeepDiveProject: mocks.updateProject,
  deleteProjectMaterial: mocks.deleteMaterial,
  addProjectMaterial: mocks.addMaterial,
  generateProjectQuestions: mocks.generateQuestions,
  projectMaterialFileUrl: (materialId) => `/api/project-materials/${materialId}/file`,
  projectMaterialBatchDownloadUrl: (materialIds) =>
    `/api/project-materials/batch-file?${materialIds.map((id) => `materialIds=${id}`).join('&')}`,
  addProjectQuestion: mocks.addQuestion,
  updateProjectQuestion: mocks.updateQuestion,
  deleteProjectQuestion: mocks.deleteQuestion,
}))

const summaries = [
  {
    projectId: 'p1',
    name: 'Agent 平台',
    role: '核心开发',
    techStack: 'Java, LangGraph',
    summary: '智能体平台',
    materialCount: 2,
    questionCount: 12,
    updatedAt: '2026-07-19T10:00:00Z',
  },
  {
    projectId: 'p2',
    name: '数据平台',
    role: '后端开发',
    techStack: 'Java, Doris',
    summary: '数据治理平台',
    materialCount: 0,
    questionCount: 0,
    updatedAt: '2026-07-18T10:00:00Z',
  },
]
const detail = {
  ...summaries[0],
  projectPeriod: '2024.03 - 2025.06',
  teamSize: '8 人',
  projectType: '内部研发平台',
  businessDomain: 'AI 基础设施',
  projectStatus: '持续迭代',
  background: '解决模型研发流程割裂问题',
  responsibilities: '负责平台架构与核心服务',
  highlights: '统一训练与推理工作流',
  challenges: '解决异构资源调度和任务恢复问题',
  outcomes: '交付周期缩短 40%',
  materials: [
    {
      materialId: 'm1',
      fileName: '项目说明.md',
      contentType: 'text/markdown',
      sizeBytes: 2048,
      createdAt: '2026-07-19T10:00:00Z',
    },
  ],
  questions: [
    {
      questionId: 'q1',
      question: '项目架构是什么？',
      answer: '**答：** 分层架构\n\n- 接入层负责路由\n- 服务层负责编排',
      category: '架构设计',
      difficulty: '常规',
      followUps: ['为什么分层？', '如何演进？'],
    },
  ],
}

beforeEach(() => {
  mocks.route.query = {}
  mocks.push.mockClear()
  mocks.replace.mockClear()
  mocks.list.mockReset().mockImplementation(async () => JSON.parse(JSON.stringify(summaries)))
  mocks.detail.mockReset().mockImplementation(async () => JSON.parse(JSON.stringify(detail)))
  mocks.createProject.mockReset()
  mocks.updateProject.mockReset()
  mocks.generateQuestions.mockReset()
  mocks.addMaterial.mockReset()
  mocks.addQuestion.mockReset()
})

describe('ProjectDeepDive two-level workflow', () => {
  it('opens on the project library without fetching project detail', async () => {
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    expect(wrapper.find('.project-library-grid').exists()).toBe(true)
    expect(wrapper.find('.project-library-grid').classes()).not.toContain('two-row')
    expect(wrapper.findAll('.project-library-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('更新于 2026')
    const summaryItems = wrapper.findAll('.project-library-summary > div')
    expect(summaryItems).toHaveLength(4)
    expect(summaryItems[2].text()).toBe('深挖问题12')
    expect(summaryItems[3].text()).toBe('平均准备度60%')
    expect(wrapper.text()).not.toContain('项目平均问题')
    expect(wrapper.text()).not.toContain('待补充材料')
    expect(wrapper.text()).not.toContain('可复盘项目')
    expect(wrapper.find('.project-library-toolbar').exists()).toBe(false)
    expect(wrapper.find('.project-library-search').exists()).toBe(false)
    expect(wrapper.find('.project-status-filters').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('搜索项目')
    expect(wrapper.find('.project-library-card .primary-btn').text()).toBe('进入复盘')
    expect(wrapper.find('.project-workbench-body').exists()).toBe(false)
    expect(mocks.detail).not.toHaveBeenCalled()
  })

  it('guides first-time users when the project library is empty', async () => {
    mocks.list.mockResolvedValue([])
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    expect(wrapper.find('.project-library-summary').exists()).toBe(false)
    expect(wrapper.find('.project-library-empty').exists()).toBe(true)
    expect(wrapper.find('.project-empty-hero h2').text()).toBe('创建你的第一个项目档案')
    expect(wrapper.findAll('.project-empty-steps li')).toHaveLength(3)
    expect(wrapper.text()).toContain('只需填写项目名称，其他信息可以稍后补充')

    await wrapper.find('.project-empty-create').trigger('click')
    await flushPromises()

    expect(wrapper.find('.project-editor-card').exists()).toBe(true)
    expect(wrapper.find('.project-editor-card h2').text()).toBe('新增项目')
  })

  it('adds and removes project technologies as individual tags when creating a project', async () => {
    mocks.createProject.mockResolvedValue({
      ...detail,
      projectId: 'p3',
      name: '大模型应用开发平台',
      techStack: 'Spring Boot',
      materials: [],
      questions: [],
    })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.find('.project-create-btn').trigger('click')
    await flushPromises()

    const modal = wrapper.find('.project-editor-card')
    expect(modal.find('.project-editor-actions .primary-btn').text()).toBe('创建')
    expect(modal.find('input[placeholder="Agent 应用开发 / LLM 工程"]').element.value).toBe('')
    expect(modal.text()).not.toContain('使用逗号分隔')

    const techInput = modal.find('.project-tech-input-row input')
    await techInput.setValue('Spring Boot')
    await techInput.trigger('keydown', { key: 'Enter' })
    await techInput.setValue('Java')
    await modal.find('.project-tech-input-row button').trigger('click')

    expect(modal.findAll('.project-tech-tag-list > span').map((tag) => tag.text())).toEqual(['Spring Boot ×', 'Java ×'])

    await techInput.setValue('spring boot')
    await techInput.trigger('keydown', { key: 'Enter' })
    expect(modal.findAll('.project-tech-tag-list > span')).toHaveLength(2)

    await modal.find('button[aria-label="移除技术栈 Java"]').trigger('click')
    expect(modal.findAll('.project-tech-tag-list > span').map((tag) => tag.text())).toEqual(['Spring Boot ×'])

    await modal.find('input[placeholder="例如：企业知识库检索助手"]').setValue('大模型应用开发平台')
    await modal.find('.project-editor-actions .primary-btn').trigger('click')
    await flushPromises()

    expect(mocks.createProject).toHaveBeenCalledWith(expect.objectContaining({ techStack: 'Spring Boot' }))
  })

  it('opens the overview when clicking a project card and returns to the library', async () => {
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.find('.project-library-card').trigger('click')
    await flushPromises()

    expect(mocks.push).toHaveBeenCalledWith({ path: '/project-deep-dive', query: { project: 'p1' } })
    expect(mocks.detail).toHaveBeenCalledWith('p1')
    expect(wrapper.find('.project-workbench-body').exists()).toBe(true)
    expect(wrapper.findAll('.project-workbench-steps button')[0].classes()).toContain('active')
    expect(wrapper.find('.project-overview-layout').exists()).toBe(true)
    expect(wrapper.find('.project-stage-heading').exists()).toBe(false)
    expect(wrapper.find('.project-workbench-title').text()).toBe('Project WorkspaceAgent 平台')
    expect(wrapper.find('.project-workbench-title .history-header-actions').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('刷新详情')
    expect(wrapper.text()).not.toContain('删除项目')

    await wrapper.find('.project-workbench-breadcrumb button').trigger('click')
    await flushPromises()
    expect(mocks.push).toHaveBeenLastCalledWith({ path: '/project-deep-dive' })
    expect(wrapper.find('.project-library-grid').exists()).toBe(true)
  })

  it('opens the review stage only from the project action button', async () => {
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.find('.project-library-card .primary-btn').trigger('click')
    await flushPromises()

    expect(mocks.push).toHaveBeenCalledWith({
      path: '/project-deep-dive',
      query: { project: 'p1', stage: 'questions' },
    })
    expect(wrapper.findAll('.project-workbench-steps button')[2].classes()).toContain('active')
    expect(wrapper.text()).toContain('项目架构是什么？')
  })

  it('restores a review stage from the route query', async () => {
    mocks.route.query = { project: 'p1', stage: 'questions' }
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    expect(wrapper.findAll('.project-workbench-steps button')[2].classes()).toContain('active')
    expect(wrapper.text()).toContain('项目架构是什么？')
  })

  it('renders question answers as markdown instead of raw text', async () => {
    mocks.route.query = { project: 'p1' }
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.findAll('.project-workbench-steps button')[2].trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 80))
    await flushPromises()

    const answerBlock = wrapper.find('.question-detail-block .deep-markdown')
    expect(answerBlock.exists()).toBe(true)
    expect(answerBlock.html()).toContain('<strong')
    expect(answerBlock.text()).not.toContain('**')
    expect(answerBlock.html()).toContain('<li')
    expect(wrapper.text()).toContain('为什么分层？')
  })

  it('keeps plain text before a dash separator consistent in detail and editor preview', async () => {
    const answer = '一句话：我做的不是“套壳 ChatGPT”，而是把研发流程工程化。\n---'
    mocks.route.query = { project: 'p1', stage: 'questions' }
    mocks.detail.mockResolvedValue({
      ...JSON.parse(JSON.stringify(detail)),
      questions: [{ ...detail.questions[0], answer }],
    })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 80))
    await flushPromises()

    const detailAnswer = wrapper.find('.question-detail-block .deep-markdown')
    expect(detailAnswer.find('h2').exists()).toBe(false)
    expect(detailAnswer.find('p').text()).toContain('一句话：我做的不是')
    expect(detailAnswer.find('hr').exists()).toBe(true)

    await wrapper.find('.question-detail-actions .secondary-btn').trigger('click')
    await wrapper.findAll('[aria-label="参考答案编辑模式"] button')[1].trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 80))
    await flushPromises()

    const preview = wrapper.find('[aria-label="参考答案 Markdown 预览"]')
    expect(preview.find('h2').exists()).toBe(false)
    expect(preview.find('p').text()).toContain('一句话：我做的不是')
    expect(preview.find('hr').exists()).toBe(true)
  })

  it('edits project info from the overview stage', async () => {
    mocks.route.query = { project: 'p1' }
    mocks.updateProject.mockResolvedValue({ ...detail, summary: '更新后的项目摘要' })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.findAll('.project-workbench-steps button')[0].trigger('click')
    expect(wrapper.find('.project-info-stage .project-stage-heading').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('项目档案')
    expect(wrapper.find('.project-summary-edit').text()).toBe('编辑')
    expect(wrapper.findAll('.project-focus-card')).toHaveLength(4)
    expect(wrapper.findAll('.project-focus-card-head em')).toHaveLength(0)
    expect(wrapper.find('.project-focus-card > strong').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('我负责什么')
    expect(wrapper.text()).not.toContain('最终带来什么')
    expect(wrapper.find('.project-experience-title').text()).toContain('4/4')
    expect(wrapper.find('.project-focus-card.challenge').text()).toContain('项目难点')
    expect(wrapper.find('.project-focus-card.challenge').text()).toContain('解决异构资源调度和任务恢复问题')
    expect(wrapper.find('.project-context-row').exists()).toBe(true)
    const overviewChildren = wrapper.find('.project-overview-main').element.children
    expect(Array.from(overviewChildren).indexOf(wrapper.find('.project-context-row').element)).toBeLessThan(
      Array.from(overviewChildren).indexOf(wrapper.find('.project-experience-panel').element),
    )
    expect(wrapper.find('.project-facts-panel').exists()).toBe(true)
    expect(wrapper.findAll('.project-facts-list > div')).toHaveLength(6)
    expect(wrapper.find('.project-facts-list').text()).toContain('内部研发平台')
    expect(wrapper.find('.project-facts-list').text()).toContain('AI 基础设施')
    expect(wrapper.find('.project-facts-list').text()).toContain('持续迭代')
    expect(wrapper.text()).toContain('2024.03 - 2025.06')
    expect(wrapper.text()).toContain('解决异构资源调度和任务恢复问题')
    expect(wrapper.text()).toContain('交付周期缩短 40%')
    expect(wrapper.text()).not.toContain('材料已就绪，可以继续问题复盘')
    await wrapper.find('.project-focus-card.technology').trigger('click')
    await wrapper.find('.project-context-row').trigger('click')
    await flushPromises()
    expect(wrapper.find('.project-modal-card').exists()).toBe(false)

    expect(wrapper.find('.project-context-edit').text()).toBe('编辑')
    await wrapper.find('.project-context-edit').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.project-editor-tabs button')[1].classes()).toContain('active')
    expect(wrapper.find('.project-detail-form').exists()).toBe(true)
    await wrapper.find('.project-editor-card .close').trigger('click')

    await wrapper.find('.project-summary-edit').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.project-editor-tabs button')[1].classes()).toContain('active')
    expect(wrapper.find('.project-detail-form').exists()).toBe(true)
    await wrapper.find('.project-editor-card .close').trigger('click')
    await wrapper.find('.project-facts-panel .project-section-title button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.project-modal-card').exists()).toBe(true)
    expect(wrapper.findAll('.project-editor-tabs button')).toHaveLength(2)
    expect(wrapper.findAll('.project-editor-tabs button')[0].classes()).toContain('active')
    expect(wrapper.find('.project-modal-card input').element.value).toBe('Agent 平台')
    expect(wrapper.find('.project-modal-card').text()).not.toContain('必填')
    expect(wrapper.findAll('.project-tech-tag-list > span').map((tag) => tag.text())).toEqual(['Java ×', 'LangGraph ×'])

    const techInput = wrapper.find('.project-tech-input-row input')
    await techInput.setValue('MongoDB')
    await techInput.trigger('keydown', { key: 'Enter' })
    await wrapper.find('.project-modal-card textarea').setValue('更新后的项目摘要')
    const projectInputs = wrapper.findAll('.project-modal-card input')
    expect(projectInputs[4].element.value).toBe('内部研发平台')
    expect(projectInputs[5].element.value).toBe('AI 基础设施')
    expect(projectInputs[6].element.value).toBe('持续迭代')
    await projectInputs[2].setValue('2024.03 - 2026.01')
    await projectInputs[4].setValue('商业交付项目')
    await projectInputs[5].setValue('企业服务')
    await projectInputs[6].setValue('已交付')
    await wrapper.find('.project-modal-card .modal-actions .primary-btn').trigger('click')
    await flushPromises()

    expect(mocks.updateProject).toHaveBeenCalledWith(
      'p1',
      expect.objectContaining({
        name: 'Agent 平台',
        summary: '更新后的项目摘要',
        projectPeriod: '2024.03 - 2026.01',
        projectType: '商业交付项目',
        businessDomain: '企业服务',
        projectStatus: '已交付',
        techStack: 'Java, LangGraph, MongoDB',
        responsibilities: '负责平台架构与核心服务',
        outcomes: '交付周期缩短 40%',
      }),
    )
    expect(wrapper.find('.project-modal-card').exists()).toBe(false)
    expect(wrapper.text()).toContain('更新后的项目摘要')

    await wrapper.findAll('.project-facts-metrics button')[0].trigger('click')
    expect(wrapper.find('.project-material-section').exists()).toBe(true)
    await wrapper.findAll('.project-workbench-steps button')[0].trigger('click')
    await wrapper.findAll('.project-facts-metrics button')[1].trigger('click')
    expect(wrapper.find('.project-question-section').exists()).toBe(true)
  })

  it('keeps incomplete overview content compact and actionable', async () => {
    mocks.route.query = { project: 'p1' }
    mocks.detail.mockResolvedValue({
      ...detail,
      projectType: '',
      businessDomain: '',
      projectStatus: '',
      background: '',
      responsibilities: '',
      highlights: '',
      challenges: '',
      outcomes: '',
    })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.findAll('.project-workbench-steps button')[0].trigger('click')

    expect(wrapper.findAll('.project-focus-card.empty')).toHaveLength(4)
    expect(wrapper.findAll('.project-facts-list dd.missing')).toHaveLength(3)
    expect(wrapper.findAll('.project-facts-list dd.empty')).toHaveLength(0)
    expect(wrapper.find('.project-context-row').classes()).toContain('empty')
    expect(wrapper.find('.project-experience-title').text()).toContain('0/4')
    expect(wrapper.text()).toContain('补充关键设计、技术选型与创新方案')
    expect(wrapper.text()).toContain('补充核心难题、方案取舍与落地过程')
  })

  it('uses one full upload area when the project has no materials', async () => {
    mocks.route.query = { project: 'p2', stage: 'materials' }
    mocks.detail.mockResolvedValue({ ...summaries[1], materials: [], questions: [] })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    const materialStage = wrapper.find('.project-material-section')
    expect(materialStage.classes()).toContain('is-empty')
    expect(materialStage.findAll('.material-upload-card')).toHaveLength(1)
    expect(materialStage.find('.material-upload-card').text()).toContain('批量上传项目文件')
    expect(materialStage.find('.empty-state').exists()).toBe(false)
    expect(materialStage.find('.material-upload-note').exists()).toBe(false)
    expect(materialStage.find('.project-material-library').exists()).toBe(false)
  })

  it('selects and uploads multiple arbitrary files one by one', async () => {
    mocks.route.query = { project: 'p1' }
    const zip = new File(['zip'], 'source.zip', { type: 'application/zip' })
    const binary = new File(['binary'], 'artifact.bin', { type: 'application/octet-stream' })
    mocks.addMaterial
      .mockResolvedValueOnce({
        ...detail,
        materials: [...detail.materials, { materialId: 'm2', fileName: zip.name, sizeBytes: zip.size }],
      })
      .mockResolvedValueOnce({
        ...detail,
        materials: [
          ...detail.materials,
          { materialId: 'm2', fileName: zip.name, sizeBytes: zip.size },
          { materialId: 'm3', fileName: binary.name, sizeBytes: binary.size },
        ],
      })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.findAll('.project-workbench-steps button')[1].trigger('click')
    const input = wrapper.find('.material-file-input')
    expect(wrapper.find('.project-material-section').classes()).not.toContain('is-empty')
    expect(wrapper.find('.project-material-library').exists()).toBe(true)
    expect(wrapper.find('.material-upload-note').exists()).toBe(true)
    expect(wrapper.find('.material-upload-card').text()).toContain('不限制文件格式；单个文件最大 1GB')
    expect(wrapper.find('.material-upload-card').text()).not.toContain('支持 ZIP')
    expect(input.attributes('multiple')).toBeDefined()
    expect(input.attributes('accept')).toBeUndefined()
    Object.defineProperty(input.element, 'files', { configurable: true, value: [zip, binary] })
    await input.trigger('change')
    await flushPromises()

    expect(mocks.addMaterial).toHaveBeenNthCalledWith(1, 'p1', zip)
    expect(mocks.addMaterial).toHaveBeenNthCalledWith(2, 'p1', binary)
    expect(wrapper.text()).toContain('已完成 2 个文件上传')
  })

  it('selects all project files for one batch download', async () => {
    mocks.route.query = { project: 'p1' }
    mocks.detail.mockResolvedValue({
      ...detail,
      materials: [
        ...detail.materials,
        {
          materialId: 'm2',
          fileName: '源码.zip',
          contentType: 'application/zip',
          sizeBytes: 4096,
          createdAt: '2026-07-19T10:00:00Z',
        },
      ],
    })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.findAll('.project-workbench-steps button')[1].trigger('click')
    await wrapper.find('.material-batch-actions input').setValue(true)

    expect(wrapper.findAll('.material-card.selected')).toHaveLength(2)
    expect(wrapper.find('.material-batch-actions a').text()).toBe('批量下载（2）')
    expect(wrapper.find('.material-batch-actions a').attributes('href')).toBe(
      '/api/project-materials/batch-file?materialIds=m1&materialIds=m2',
    )
  })

  it('renders project file metadata and deletes a file after confirmation', async () => {
    mocks.route.query = { project: 'p1' }
    mocks.deleteMaterial.mockResolvedValue({ name: 'materialId', value: 'm1' })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.findAll('.project-workbench-steps button')[1].trigger('click')
    const card = wrapper.find('.material-card')
    expect(card.exists()).toBe(true)
    expect(card.find('.material-file-mark').text()).toBe('MD')
    expect(card.findAll('.material-file-mark')).toHaveLength(1)
    expect(card.find('.material-type-badge').exists()).toBe(false)
    expect(card.text()).toContain('2.00 KB')
    expect(card.find('.material-card-preview').exists()).toBe(false)
    expect(card.findAll('.material-card-actions a')).toHaveLength(1)
    expect(card.find('.material-card-actions a').text()).toBe('下载')
    expect(card.find('.material-card-actions a').attributes('href')).toBe('/api/project-materials/m1/file')
    expect(wrapper.text()).not.toContain('查看')
    expect(wrapper.find('.material-batch-actions button').attributes('disabled')).toBeDefined()

    await card.find('.material-card-select input').setValue(true)
    expect(wrapper.find('.material-batch-actions a').text()).toBe('批量下载（1）')
    expect(wrapper.find('.material-batch-actions a').attributes('href')).toBe(
      '/api/project-materials/batch-file?materialIds=m1',
    )

    await card.find('.material-card-delete').trigger('click')
    expect(wrapper.find('.history-delete-modal').exists()).toBe(true)
    await wrapper.find('.history-delete-modal .danger-btn').trigger('click')
    await flushPromises()

    expect(mocks.deleteMaterial).toHaveBeenCalledWith('m1')
    expect(wrapper.find('.material-card').exists()).toBe(false)
  })

  it('does not render internal object storage metadata', async () => {
    mocks.route.query = { project: 'p1' }
    mocks.detail.mockResolvedValue({
      ...detail,
      materials: [
        {
          materialId: 'm2',
          fileName: '项目附件.pdf',
          contentType: 'application/pdf',
          sizeBytes: 4096,
          storagePath: 'imports/projects/p1/private-key.pdf',
        },
      ],
    })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.findAll('.project-workbench-steps button')[1].trigger('click')
    const card = wrapper.find('.material-card')
    expect(card.find('.material-file-mark').text()).toBe('PDF')
    expect(card.find('.material-type-badge').exists()).toBe(false)
    expect(card.find('.material-card-preview').exists()).toBe(false)
    expect(card.text()).toContain('4.00 KB')
    expect(card.text()).not.toContain('imports/projects')
  })

  it('fills the empty review workspace and exposes material and question actions', async () => {
    mocks.route.query = { project: 'p2', stage: 'questions' }
    mocks.detail.mockResolvedValue({ ...summaries[1], materials: [], questions: [] })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    const emptyState = wrapper.find('.project-question-empty')
    expect(emptyState.exists()).toBe(true)
    expect(emptyState.classes()).toContain('compact')
    const actions = emptyState.findAll('.empty-state-actions button')
    expect(actions).toHaveLength(2)
    expect(actions.map((button) => button.text())).toEqual(['添加材料', '添加问题'])

    await actions[0].trigger('click')
    expect(wrapper.find('.project-material-section').exists()).toBe(true)

    await wrapper.findAll('.project-workbench-steps button')[2].trigger('click')
    await wrapper.find('.project-question-empty .question-add-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.question-editor-card').exists()).toBe(true)
    expect(wrapper.findAll('.question-create-methods button')[0].attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.question-create-methods button')[1].classes()).toContain('active')
  })

  it('uses one add-question modal for generated and manual questions', async () => {
    mocks.route.query = { project: 'p1', stage: 'questions' }
    mocks.generateQuestions.mockResolvedValue({
      ...detail,
      questions: [
        {
          questionId: 'q-ai',
          question: '智能生成的问题',
          answer: '生成答案',
          category: '架构设计',
          difficulty: '深入',
        },
      ],
    })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    expect(wrapper.find('.generate-bar').exists()).toBe(false)
    expect(wrapper.find('.project-question-tool-actions .question-add-btn').text()).toBe('添加问题')
    await wrapper.find('.project-question-tool-actions .question-add-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.question-editor-card h2').text()).toBe('添加问题')
    expect(wrapper.findAll('.question-create-methods button')).toHaveLength(2)
    expect(wrapper.findAll('.question-create-methods button')[0].classes()).toContain('active')
    expect(wrapper.find('.question-generate-form input[type="number"]').element.value).toBe('')
    expect(wrapper.find('.question-generate-form input[type="number"]').attributes('placeholder')).toBe(
      '请输入 4-40 的整数',
    )
    expect(wrapper.find('.question-generate-form input:not([type])').element.value).toBe('')
    expect(wrapper.findAll('.question-editor-card .modal-actions button')).toHaveLength(1)
    expect(wrapper.find('.question-editor-card .modal-actions').text()).not.toContain('取消')
    await wrapper.find('.question-generate-form input[type="number"]').setValue(12)
    await wrapper.find('.question-editor-card .modal-actions .question-add-btn').trigger('click')
    await flushPromises()

    expect(mocks.generateQuestions).toHaveBeenCalledWith('p1', expect.objectContaining({ count: 12 }))
    expect(wrapper.find('.question-editor-card').exists()).toBe(false)
  })

  it('adds a manual question from the unified question modal', async () => {
    mocks.route.query = { project: 'p1', stage: 'questions' }
    mocks.addQuestion.mockResolvedValue({
      ...detail,
      questions: [
        {
          questionId: 'q-new',
          question: '手动补充的问题',
          answer: '手动答案',
          category: '自定义',
          difficulty: '常规',
          source: 'manual',
        },
        ...detail.questions,
      ],
    })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.find('.project-question-tool-actions .question-add-btn').trigger('click')
    await wrapper.findAll('.question-create-methods button')[1].trigger('click')
    await flushPromises()

    expect(wrapper.find('.question-editor-card').text()).not.toContain('必填')
    expect(wrapper.find('.question-editor-card select').element.value).toBe('')
    await wrapper.find('.question-editor-card textarea').setValue('手动补充的问题')
    await wrapper.find('.question-editor-card select').setValue('常规')
    await wrapper.find('.question-editor-card .modal-actions .question-add-btn').trigger('click')
    await flushPromises()

    expect(mocks.addQuestion).toHaveBeenCalledWith('p1', expect.objectContaining({ question: '手动补充的问题' }))
    expect(wrapper.find('.question-editor-card').exists()).toBe(false)
    expect(wrapper.text()).toContain('手动补充的问题')
    expect(wrapper.text()).toContain('手动维护')
  })

  it('previews a manual question answer with the practice center markdown interaction', async () => {
    mocks.route.query = { project: 'p1', stage: 'questions' }
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.find('.project-question-tool-actions .question-add-btn').trigger('click')
    await wrapper.findAll('.question-create-methods button')[1].trigger('click')
    await flushPromises()

    const answerEditor = wrapper.find('.question-manual-form > .project-answer-editor')
    const answerSource = wrapper.find('#project-question-answer-markdown')
    const answerTabs = wrapper.find('[aria-label="参考答案编辑模式"]')
    expect(answerEditor.exists()).toBe(true)
    expect(answerEditor.classes()).toContain('wide')
    expect(answerSource.exists()).toBe(true)
    expect(wrapper.find('[aria-label="参考答案 Markdown 预览"]').exists()).toBe(false)

    await answerSource.setValue('**核心结论**\n\n- 使用幂等键\n- 事务提交后确认')
    await answerTabs.findAll('[role="tab"]')[1].trigger('click')
    await flushPromises()

    const answerPreview = wrapper.find('[aria-label="参考答案 Markdown 预览"]')
    expect(answerPreview.exists()).toBe(true)
    expect(answerPreview.findComponent(PracticeMarkdown).props('content')).toContain('**核心结论**')

    await wrapper.find('.question-editor-card .close').trigger('click')
    await wrapper.find('.question-detail-actions .secondary-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('#project-question-answer-markdown').exists()).toBe(true)
    expect(wrapper.find('[aria-label="参考答案 Markdown 预览"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('deletes a question after confirmation', async () => {
    mocks.route.query = { project: 'p1', stage: 'questions' }
    mocks.deleteQuestion.mockResolvedValue({ name: 'questionId', value: 'q1' })
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    await wrapper.find('.question-detail-actions .danger-text').trigger('click')
    expect(wrapper.find('.history-delete-modal').exists()).toBe(true)

    await wrapper.find('.history-delete-modal .danger-btn').trigger('click')
    await flushPromises()

    expect(mocks.deleteQuestion).toHaveBeenCalledWith('q1')
    expect(wrapper.text()).not.toContain('项目架构是什么？')
  })

  it('restores a project workspace from the query parameter', async () => {
    mocks.route.query = { project: 'p1' }
    const wrapper = mount(ProjectDeepDive)
    await flushPromises()

    expect(mocks.detail).toHaveBeenCalledWith('p1')
    expect(wrapper.find('.project-workbench-body').exists()).toBe(true)
    expect(wrapper.text()).toContain('Agent 平台')
  })
})
