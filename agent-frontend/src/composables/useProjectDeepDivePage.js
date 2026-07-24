import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { validateFile, validateInteger, validateLength, validateTags } from '../utils/formValidation'
import {
  addProjectMaterial,
  addProjectQuestion,
  createDeepDiveProject,
  deleteDeepDiveProject,
  deleteProjectMaterial,
  deleteProjectQuestion,
  generateProjectQuestions,
  getDeepDiveProject,
  listDeepDiveProjects,
  projectMaterialBatchDownloadUrl,
  projectMaterialFileUrl,
  updateDeepDiveProject,
  updateProjectQuestion,
} from '../api/projectDeepDive'
import PracticeMarkdown from '../components/interview/PracticeMarkdown.vue'

export function useProjectDeepDivePage() {
  const route = useRoute()
  const router = useRouter()
  const loading = ref(false),
    detailLoading = ref(false),
    saving = ref(false),
    generating = ref(false)
  const error = ref(''),
    detailError = ref(''),
    modalError = ref(''),
    materialError = ref(''),
    materialUploadStatus = ref(''),
    techDraft = ref(''),
    techStackError = ref('')
  const projects = ref([]),
    selectedId = ref(''),
    showModal = ref(false),
    projectStage = ref('info'),
    selectedQuestionId = ref(''),
    projectNameInput = ref(null)
  const projectDetails = reactive({})
  let detailRequestId = 0
  const questionKeyword = ref('')
  const selectedMaterialIds = ref([])
  const questionPage = ref(1)
  const questionPageSize = 6
  const emptyProjectForm = () => ({
    name: '',
    role: '',
    techStack: '',
    projectPeriod: '',
    teamSize: '',
    projectType: '',
    businessDomain: '',
    projectStatus: '',
    summary: '',
    background: '',
    responsibilities: '',
    highlights: '',
    challenges: '',
    outcomes: '',
  })
  const form = reactive(emptyProjectForm())
  const generateForm = reactive({ count: '', focus: '' })
  const deleteDialog = reactive({ visible: false, projectId: '', name: '' })
  const questionModal = reactive({
    visible: false,
    mode: 'create',
    entryType: 'generate',
    questionId: '',
    question: '',
    answer: '',
    category: '',
    difficulty: '',
    error: '',
  })
  const questionDeleteDialog = reactive({ visible: false, questionId: '', name: '' })
  const questionActionError = ref('')
  const questionInput = ref(null)
  const answerEditorMode = ref('edit')
  const projectModalMode = ref('create')
  const projectEditorSection = ref('basic')
  const materialDeleteDialog = reactive({ visible: false, materialId: '', name: '' })

  const libraryStats = computed(() => ({
    materials: projects.value.reduce((sum, item) => sum + materialCount(item), 0),
    questions: projects.value.reduce((sum, item) => sum + questionCount(item), 0),
    averageReadiness: projects.value.length
      ? Math.round(projects.value.reduce((sum, item) => sum + readiness(item).progress, 0) / projects.value.length)
      : 0,
  }))
  const selectedSummary = computed(() => projects.value.find((item) => item.projectId === selectedId.value) || null)
  const selectedProject = computed(() => projectDetails[selectedId.value] || null)
  const formTechLabels = computed(() => parseTechStack(form.techStack))
  const coreOverviewCompletion = computed(() => {
    const project = selectedProject.value
    if (!project) return 0
    return [project.responsibilities, project.highlights, project.challenges, project.outcomes].filter((value) =>
      String(value || '').trim(),
    ).length
  })
  const activeProjectName = computed(() => selectedProject.value?.name || selectedSummary.value?.name || '项目详情')
  const projectSteps = computed(() => [
    { key: 'info', index: 1, label: '项目概览', description: '确认背景与准备状态', done: true },
    {
      key: 'materials',
      index: 2,
      label: '项目材料',
      description: `${materialCount(selectedProject.value)} 份材料`,
      done: materialCount(selectedProject.value) > 0,
    },
    {
      key: 'questions',
      index: 3,
      label: '问题复盘',
      description: `${questionCount(selectedProject.value)} 道问题`,
      done: questionCount(selectedProject.value) > 0,
    },
  ])
  const filteredProjectQuestions = computed(() => {
    const questions = selectedProject.value?.questions || []
    const query = questionKeyword.value.trim().toLowerCase()
    return query
      ? questions.filter((item) =>
          [item.question, item.category, item.difficulty].filter(Boolean).join(' ').toLowerCase().includes(query),
        )
      : questions
  })
  const questionPages = computed(() => Math.max(1, Math.ceil(filteredProjectQuestions.value.length / questionPageSize)))
  const pagedProjectQuestions = computed(() =>
    filteredProjectQuestions.value.slice(
      (questionPage.value - 1) * questionPageSize,
      questionPage.value * questionPageSize,
    ),
  )
  const selectedQuestion = computed(
    () =>
      filteredProjectQuestions.value.find((item) => item.questionId === selectedQuestionId.value) ||
      pagedProjectQuestions.value[0] ||
      null,
  )
  const questionPosition = computed(() =>
    Math.max(
      0,
      filteredProjectQuestions.value.findIndex((item) => item.questionId === selectedQuestion.value?.questionId) + 1,
    ),
  )
  const canGenerate = computed(() => materialCount(selectedProject.value) > 0)
  const allMaterialsSelected = computed(() => {
    const materials = selectedProject.value?.materials || []
    return materials.length > 0 && materials.every((item) => selectedMaterialIds.value.includes(item.materialId))
  })
  const answerMarkdown = computed(() => String(selectedQuestion.value?.answer || '').trim())
  const followUpMarkdown = computed(() =>
    toMarkdownList(selectedQuestion.value?.followUp || selectedQuestion.value?.followUps),
  )
  const evidenceMarkdown = computed(() =>
    String(selectedQuestion.value?.evidence || selectedQuestion.value?.materialEvidence || '').trim(),
  )
  const questionModalSubmitDisabled = computed(() => {
    if (saving.value || generating.value) return true
    if (questionModal.mode === 'edit' || questionModal.entryType === 'manual') return false
    return !canGenerate.value
  })
  const questionModalSubmitText = computed(() => {
    if (questionModal.mode === 'edit') return saving.value ? '保存中' : '保存修改'
    if (questionModal.entryType === 'manual') return saving.value ? '保存中' : '添加问题'
    if (generating.value) return '正在生成'
    return selectedProject.value?.questions?.length ? '重新生成' : '生成问题'
  })

  watch(
    () => [route.query.project, route.query.stage],
    ([projectId, stage]) => {
      const id = typeof projectId === 'string' ? projectId : ''
      const nextStage = stageFromRoute(stage)
      if (id === selectedId.value && nextStage === projectStage.value) return
      selectedId.value = id
      projectStage.value = nextStage
      if (id) void loadProjectDetail(id)
    },
  )
  watch(selectedProject, (project, previous) => {
    if (!project) return
    if (previous && previous.projectId === project.projectId) {
      const materialIds = new Set((project.materials || []).map((item) => item.materialId))
      selectedMaterialIds.value = selectedMaterialIds.value.filter((materialId) => materialIds.has(materialId))
      if (!(project.questions || []).some((item) => item.questionId === selectedQuestionId.value))
        selectedQuestionId.value = project.questions?.[0]?.questionId || ''
      return
    }
    selectedMaterialIds.value = []
    selectedQuestionId.value = project.questions?.[0]?.questionId || ''
    questionKeyword.value = ''
    questionPage.value = 1
  })
  watch(questionKeyword, () => {
    questionPage.value = 1
  })
  watch(questionPages, (pages) => {
    if (questionPage.value > pages) questionPage.value = pages
  })
  onMounted(async () => {
    document.addEventListener('keydown', handleKeydown)
    await loadProjects()
    const id = typeof route.query.project === 'string' ? route.query.project : ''
    if (id && projects.value.some((item) => item.projectId === id)) {
      selectedId.value = id
      projectStage.value = stageFromRoute(route.query.stage)
      void loadProjectDetail(id)
    } else if (id) {
      await router.replace({ path: route.path })
    }
  })
  onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))

  async function loadProjects() {
    loading.value = true
    error.value = ''
    try {
      projects.value = await listDeepDiveProjects()
    } catch (e) {
      error.value = e.message || '项目加载失败'
    } finally {
      loading.value = false
    }
  }
  async function loadProjectDetail(id, force = false) {
    if (!id || (!force && projectDetails[id])) return
    const requestId = ++detailRequestId
    detailLoading.value = true
    detailError.value = ''
    try {
      const detail = await getDeepDiveProject(id)
      if (requestId === detailRequestId) projectDetails[id] = detail
    } catch (e) {
      if (requestId === detailRequestId) detailError.value = e.message || '项目详情加载失败'
    } finally {
      if (requestId === detailRequestId) detailLoading.value = false
    }
  }
  async function openProject(id, stage = 'info') {
    const targetStage = stageFromRoute(stage)
    selectedId.value = id
    projectStage.value = targetStage
    materialError.value = ''
    materialUploadStatus.value = ''
    const query = targetStage === 'info' ? { project: id } : { project: id, stage: targetStage }
    await router.push({ path: route.path, query })
    void loadProjectDetail(id)
  }
  async function backToLibrary() {
    detailRequestId++
    selectedId.value = ''
    detailLoading.value = false
    detailError.value = ''
    await router.push({ path: route.path })
  }
  function readiness(project) {
    const materials = materialCount(project),
      questions = questionCount(project)
    if (!materials) return { label: '待补材料', tone: 'pending', progress: 20, action: '补充材料', stage: 'materials' }
    if (!questions) return { label: '可生成问题', tone: 'ready', progress: 60, action: '生成问题', stage: 'questions' }
    return { label: '可复盘', tone: 'complete', progress: 100, action: '进入复盘', stage: 'questions' }
  }
  function stageFromRoute(stage) {
    return ['materials', 'questions'].includes(stage) ? stage : 'info'
  }
  function parseTechStack(value) {
    return String(value || '')
      .split(/[,，、/|\n]+/)
      .map((item) => item.trim())
      .filter(Boolean)
  }
  function techLabels(project) {
    return parseTechStack(project?.techStack)
  }
  function addFormTech() {
    const tech = techDraft.value.trim()
    if (!tech) return
    const existing = formTechLabels.value
    if (existing.some((item) => item.toLowerCase() === tech.toLowerCase())) {
      techDraft.value = ''
      techStackError.value = ''
      return
    }
    const nextTechStack = [...existing, tech].join(', ')
    if (nextTechStack.length > 512) {
      techStackError.value = '技术栈内容不能超过 512 个字符'
      return
    }
    form.techStack = nextTechStack
    techDraft.value = ''
    techStackError.value = ''
  }
  function removeFormTech(tech) {
    form.techStack = formTechLabels.value.filter((item) => item !== tech).join(', ')
    techStackError.value = ''
  }
  function formatUpdatedAt(value, fallback = '暂无更新时间') {
    if (!value) return fallback
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return fallback
    return `更新于 ${new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric' }).format(date)}`
  }
  function materialTypeInfo(material) {
    const fileName = String(material?.fileName || '')
    const type = String(material?.contentType || '').toLowerCase()
    const extension = fileName.includes('.') ? fileName.split('.').pop().toLowerCase() : ''
    if (extension === 'pdf' || type.includes('pdf')) return { label: 'PDF', tone: 'pdf' }
    if (
      ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz'].includes(extension) ||
      type.includes('zip') ||
      type.includes('compressed')
    )
      return { label: extension.toUpperCase() || 'ZIP', tone: 'archive' }
    if (['doc', 'docx', 'odt', 'rtf'].includes(extension)) return { label: extension.toUpperCase(), tone: 'document' }
    if (['xls', 'xlsx', 'csv'].includes(extension)) return { label: extension.toUpperCase(), tone: 'sheet' }
    if (['ppt', 'pptx', 'key'].includes(extension)) return { label: extension.toUpperCase(), tone: 'slides' }
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(extension) || type.startsWith('image/'))
      return { label: extension.toUpperCase() || 'IMG', tone: 'media' }
    if (
      ['mp3', 'wav', 'm4a', 'mp4', 'mov', 'avi', 'mkv'].includes(extension) ||
      type.startsWith('audio/') ||
      type.startsWith('video/')
    )
      return { label: extension.toUpperCase() || 'MEDIA', tone: 'media' }
    if (['md', 'markdown', 'txt', 'json', 'xml', 'yaml', 'yml'].includes(extension) || type.startsWith('text/'))
      return { label: (extension || 'TXT').toUpperCase().slice(0, 5), tone: 'text' }
    return { label: extension ? extension.toUpperCase().slice(0, 5) : 'FILE', tone: 'file' }
  }
  function formatFileSize(value) {
    const bytes = Number(value)
    if (!Number.isFinite(bytes) || bytes < 0) return '大小未知'
    if (bytes < 1024) return `${bytes} B`
    const units = ['KB', 'MB', 'GB']
    let size = bytes
    let unit = -1
    do {
      size /= 1024
      unit++
    } while (size >= 1024 && unit < units.length - 1)
    return `${size >= 100 ? size.toFixed(0) : size >= 10 ? size.toFixed(1) : size.toFixed(2)} ${units[unit]}`
  }
  function toggleAllMaterials(event) {
    selectedMaterialIds.value = event.target.checked
      ? (selectedProject.value?.materials || []).map((item) => item.materialId)
      : []
  }
  function openMaterialDeleteDialog(material) {
    if (material)
      Object.assign(materialDeleteDialog, { visible: true, materialId: material.materialId, name: material.fileName })
  }
  function closeMaterialDeleteDialog() {
    if (!saving.value) Object.assign(materialDeleteDialog, { visible: false, materialId: '', name: '' })
  }
  async function confirmDeleteMaterial() {
    saving.value = true
    materialError.value = ''
    try {
      const deletedId = materialDeleteDialog.materialId
      await deleteProjectMaterial(deletedId)
      const detail = projectDetails[selectedId.value]
      if (detail)
        replaceProject({
          ...detail,
          materials: (detail.materials || []).filter((item) => item.materialId !== deletedId),
        })
      closeMaterialDeleteDialog()
    } catch (e) {
      materialError.value = e.message || '材料删除失败'
    } finally {
      saving.value = false
      if (materialDeleteDialog.visible) closeMaterialDeleteDialog()
    }
  }
  async function openCreate() {
    projectModalMode.value = 'create'
    projectEditorSection.value = 'basic'
    Object.assign(form, emptyProjectForm())
    techDraft.value = ''
    techStackError.value = ''
    modalError.value = ''
    showModal.value = true
    await nextTick()
    projectNameInput.value?.focus()
  }
  async function openEditProject(section = 'basic') {
    const project = selectedProject.value
    if (!project) return
    projectModalMode.value = 'edit'
    projectEditorSection.value = section
    Object.assign(form, emptyProjectForm(), {
      name: project.name || '',
      role: project.role || '',
      techStack: parseTechStack(project.techStack).join(', '),
      projectPeriod: project.projectPeriod || '',
      teamSize: project.teamSize || '',
      projectType: project.projectType || '',
      businessDomain: project.businessDomain || '',
      projectStatus: project.projectStatus || '',
      summary: project.summary || '',
      background: project.background || '',
      responsibilities: project.responsibilities || '',
      highlights: project.highlights || '',
      challenges: project.challenges || '',
      outcomes: project.outcomes || '',
    })
    techDraft.value = ''
    techStackError.value = ''
    modalError.value = ''
    showModal.value = true
    await nextTick()
    if (section === 'basic') projectNameInput.value?.focus()
  }
  function closeCreate() {
    if (!saving.value) showModal.value = false
  }
  async function saveProject() {
    modalError.value = ''
    try {
      validateLength(form.name, '项目名称', { max: 80, required: true })
      validateLength(form.role, '项目角色', { max: 40 })
      for (const [key, label, max] of [
        ['projectPeriod', '项目周期', 128],
        ['teamSize', '团队规模', 64],
        ['projectType', '项目类型', 128],
        ['businessDomain', '业务领域', 128],
        ['projectStatus', '项目状态', 64],
        ['summary', '项目摘要', 1000],
        ['background', '项目背景', 2000],
        ['responsibilities', '职责范围', 2000],
        ['highlights', '技术亮点', 2000],
        ['challenges', '项目难点', 2000],
        ['outcomes', '项目成果', 2000],
      ])
        validateLength(form[key], label, { max })
      validateTags(formTechLabels.value, '技术栈', { maxCount: 20, maxLength: 64 })
    } catch (e) {
      modalError.value = e.message
      return
    }
    saving.value = true
    modalError.value = ''
    try {
      if (projectModalMode.value === 'edit') {
        const saved = await updateDeepDiveProject(selectedId.value, form)
        replaceProject(saved)
        showModal.value = false
      } else {
        const saved = await createDeepDiveProject(form)
        projectDetails[saved.projectId] = saved
        replaceProject(saved)
        showModal.value = false
        await openProject(saved.projectId)
      }
    } catch (e) {
      modalError.value = e.message || '保存失败'
    } finally {
      saving.value = false
    }
  }
  function openDeleteDialog(project) {
    if (project) Object.assign(deleteDialog, { visible: true, projectId: project.projectId, name: project.name })
  }
  function closeDeleteDialog() {
    if (!saving.value) Object.assign(deleteDialog, { visible: false, projectId: '', name: '' })
  }
  async function confirmDeleteProject() {
    saving.value = true
    error.value = ''
    try {
      const deletedId = deleteDialog.projectId
      await deleteDeepDiveProject(deletedId)
      delete projectDetails[deletedId]
      projects.value = projects.value.filter((item) => item.projectId !== deletedId)
      closeDeleteDialog()
      if (selectedId.value === deletedId) await backToLibrary()
    } catch (e) {
      error.value = e.message || '项目删除失败'
    } finally {
      saving.value = false
      if (deleteDialog.visible) closeDeleteDialog()
    }
  }
  async function uploadMaterialFiles(event) {
    const files = Array.from(event.target.files || [])
    event.target.value = ''
    materialError.value = ''
    materialUploadStatus.value = ''
    if (!files.length || !selectedProject.value) return

    const maxBytes = 1024 * 1024 * 1024
    if (files.length > 20) {
      materialError.value = '单次最多上传 20 个文件'
      return
    }
    const accepted = []
    const failures = []
    for (const file of files) {
      try {
        validateFile(file, file.name || '项目材料', { maxBytes })
        accepted.push(file)
      } catch (err) {
        failures.push(`${file.name}（${err.message}）`)
      }
    }
    let uploaded = 0
    saving.value = true
    try {
      for (const file of accepted) {
        try {
          const updated = await addProjectMaterial(selectedProject.value.projectId, file)
          replaceProject(updated)
          uploaded++
          materialUploadStatus.value = `正在上传 ${uploaded + failures.length} / ${files.length}`
        } catch (error) {
          failures.push(`${file.name}（${error.message || '上传失败'}）`)
        }
      }
      if (uploaded) materialUploadStatus.value = `已完成 ${uploaded} 个文件上传`
      if (failures.length) materialError.value = `${failures.length} 个文件未上传：${failures.join('、')}`
    } finally {
      saving.value = false
    }
  }
  async function generateQuestions() {
    if (!selectedProject.value || !canGenerate.value) return
    try {
      validateInteger(generateForm.count, '生成数量', { min: 4, max: 40 })
      validateLength(generateForm.focus, '关注方向', { max: 500 })
    } catch (err) {
      questionModal.error = err.message
      return
    }
    generating.value = true
    questionModal.error = ''
    questionActionError.value = ''
    try {
      const updated = await generateProjectQuestions(selectedProject.value.projectId, generateForm)
      replaceProject(updated)
      questionKeyword.value = ''
      questionPage.value = 1
      selectedQuestionId.value = updated.questions?.[0]?.questionId || ''
      questionModal.visible = false
    } catch (e) {
      questionModal.error = e.message || '生成失败'
    } finally {
      generating.value = false
    }
  }
  function replaceProject(updated) {
    projectDetails[updated.projectId] = updated
    const summary = {
      ...updated,
      materialCount: updated.materials?.length || 0,
      questionCount: updated.questions?.length || 0,
    }
    delete summary.materials
    delete summary.questions
    const index = projects.value.findIndex((item) => item.projectId === updated.projectId)
    if (index >= 0) projects.value.splice(index, 1, summary)
    else projects.value.unshift(summary)
  }
  function materialCount(project) {
    return Number(project?.materialCount ?? project?.materials?.length ?? 0)
  }
  function questionCount(project) {
    return Number(project?.questionCount ?? project?.questions?.length ?? 0)
  }
  function selectQuestion(item) {
    selectedQuestionId.value = item?.questionId || ''
  }
  function isManualQuestion(question) {
    return String(question?.source || '') === 'manual'
  }
  async function openQuestionModal(question) {
    answerEditorMode.value = 'edit'
    Object.assign(
      questionModal,
      question
        ? {
            visible: true,
            mode: 'edit',
            entryType: 'manual',
            questionId: question.questionId,
            question: question.question || '',
            answer: question.answer || '',
            category: question.category || '',
            difficulty: ['常规', '深入'].includes(question.difficulty) ? question.difficulty : '',
            error: '',
          }
        : {
            visible: true,
            mode: 'create',
            entryType: canGenerate.value ? 'generate' : 'manual',
            questionId: '',
            question: '',
            answer: '',
            category: '',
            difficulty: '',
            error: '',
          },
    )
    if (!question) Object.assign(generateForm, { count: '', focus: '' })
    await nextTick()
    if (questionModal.mode === 'edit' || questionModal.entryType === 'manual') questionInput.value?.focus()
  }
  async function setQuestionEntryType(entryType) {
    if (questionModal.entryType === entryType) return
    questionModal.entryType = entryType
    questionModal.error = ''
    answerEditorMode.value = 'edit'
    if (entryType === 'manual') {
      await nextTick()
      questionInput.value?.focus()
    }
  }
  function closeQuestionModal() {
    if (!saving.value && !generating.value) questionModal.visible = false
  }
  async function submitQuestionModal() {
    if (questionModal.mode === 'create' && questionModal.entryType === 'generate') await generateQuestions()
    else await saveQuestionModal()
  }
  async function saveQuestionModal() {
    try {
      validateLength(questionModal.question, '问题内容', { max: 500, required: true })
      validateLength(questionModal.category, '问题分类', { max: 40 })
      if (!questionModal.difficulty) throw new Error('请选择问题难度')
      validateLength(questionModal.answer, '参考答案', { max: 8000 })
    } catch (err) {
      questionModal.error = err.message
      return
    }
    if (!selectedProject.value) return
    saving.value = true
    questionModal.error = ''
    try {
      const payload = {
        question: questionModal.question,
        answer: questionModal.answer,
        category: questionModal.category,
        difficulty: questionModal.difficulty,
      }
      const updated =
        questionModal.mode === 'edit'
          ? await updateProjectQuestion(questionModal.questionId, payload)
          : await addProjectQuestion(selectedProject.value.projectId, payload)
      const keepId = questionModal.mode === 'edit' ? questionModal.questionId : updated.questions?.[0]?.questionId || ''
      replaceProject(updated)
      if (questionModal.mode === 'create') {
        questionKeyword.value = ''
        questionPage.value = 1
      }
      selectedQuestionId.value = keepId
      questionActionError.value = ''
      questionModal.visible = false
    } catch (e) {
      questionModal.error = e.message || '问题保存失败'
    } finally {
      saving.value = false
    }
  }
  function openQuestionDeleteDialog(question) {
    if (question)
      Object.assign(questionDeleteDialog, { visible: true, questionId: question.questionId, name: question.question })
  }
  function closeQuestionDeleteDialog() {
    if (!saving.value) Object.assign(questionDeleteDialog, { visible: false, questionId: '', name: '' })
  }
  async function confirmDeleteQuestion() {
    saving.value = true
    questionActionError.value = ''
    try {
      const deletedId = questionDeleteDialog.questionId
      await deleteProjectQuestion(deletedId)
      const detail = projectDetails[selectedId.value]
      if (detail) {
        const remaining = (detail.questions || []).filter((item) => item.questionId !== deletedId)
        replaceProject({ ...detail, questions: remaining })
        if (selectedQuestionId.value === deletedId) selectedQuestionId.value = remaining[0]?.questionId || ''
      }
      closeQuestionDeleteDialog()
    } catch (e) {
      questionActionError.value = e.message || '问题删除失败'
    } finally {
      saving.value = false
      if (questionDeleteDialog.visible) closeQuestionDeleteDialog()
    }
  }
  function toMarkdownList(value) {
    const items = Array.isArray(value)
      ? value.map((item) => String(item || '').trim()).filter(Boolean)
      : String(value || '')
          .split('\n')
          .map((item) => item.trim())
          .filter(Boolean)
    if (!items.length) return ''
    return items.map((item) => (/^([-*+]|\d+[.、)])\s?/.test(item) ? item : `- ${item}`)).join('\n')
  }
  function handleKeydown(event) {
    if (!['Escape', 'Esc'].includes(event.key)) return
    if (materialDeleteDialog.visible) closeMaterialDeleteDialog()
    else if (questionDeleteDialog.visible) closeQuestionDeleteDialog()
    else if (questionModal.visible) closeQuestionModal()
    else if (deleteDialog.visible) closeDeleteDialog()
    else if (showModal.value) closeCreate()
  }

  return {
    route,
    router,
    loading,
    detailLoading,
    saving,
    generating,
    error,
    detailError,
    modalError,
    materialError,
    materialUploadStatus,
    techDraft,
    techStackError,
    projects,
    selectedId,
    showModal,
    projectStage,
    selectedQuestionId,
    projectNameInput,
    projectDetails,
    detailRequestId,
    questionKeyword,
    selectedMaterialIds,
    questionPage,
    questionPageSize,
    emptyProjectForm,
    form,
    generateForm,
    deleteDialog,
    questionModal,
    questionDeleteDialog,
    questionActionError,
    questionInput,
    answerEditorMode,
    projectModalMode,
    projectEditorSection,
    materialDeleteDialog,
    libraryStats,
    selectedSummary,
    selectedProject,
    formTechLabels,
    coreOverviewCompletion,
    activeProjectName,
    projectSteps,
    filteredProjectQuestions,
    questionPages,
    pagedProjectQuestions,
    selectedQuestion,
    questionPosition,
    canGenerate,
    allMaterialsSelected,
    answerMarkdown,
    followUpMarkdown,
    evidenceMarkdown,
    questionModalSubmitDisabled,
    questionModalSubmitText,
    loadProjects,
    loadProjectDetail,
    openProject,
    backToLibrary,
    readiness,
    stageFromRoute,
    parseTechStack,
    techLabels,
    addFormTech,
    removeFormTech,
    formatUpdatedAt,
    materialTypeInfo,
    formatFileSize,
    toggleAllMaterials,
    openMaterialDeleteDialog,
    closeMaterialDeleteDialog,
    confirmDeleteMaterial,
    openCreate,
    openEditProject,
    closeCreate,
    saveProject,
    openDeleteDialog,
    closeDeleteDialog,
    confirmDeleteProject,
    uploadMaterialFiles,
    generateQuestions,
    replaceProject,
    materialCount,
    questionCount,
    selectQuestion,
    isManualQuestion,
    openQuestionModal,
    setQuestionEntryType,
    closeQuestionModal,
    submitQuestionModal,
    saveQuestionModal,
    openQuestionDeleteDialog,
    closeQuestionDeleteDialog,
    confirmDeleteQuestion,
    toMarkdownList,
    handleKeydown,
    projectMaterialBatchDownloadUrl,
    projectMaterialFileUrl,
    PracticeMarkdown,
  }
}
