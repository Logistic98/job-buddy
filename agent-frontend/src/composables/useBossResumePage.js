import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { generateJobProfileSummary } from '../api/resume'
import { useResumeStore } from '../stores/resume'
import { validateInteger, validateLength, validateMonthRange, validateTags } from '../utils/formValidation'

export function useBossResumePage() {
  const resume = useResumeStore()
  const saving = ref(false)
  const generatingSummary = ref(false)
  const error = ref('')
  const saveHint = ref('')
  const warningMessage = ref('')
  const profileOverviewVisible = ref(false)
  let lastWarning = ''
  const summaryCompare = reactive({ visible: false, oldSummary: '', newSummary: '', highlights: [], provider: '' })
  const summaryDiff = computed(() => buildSummaryDiff(summaryCompare.oldSummary, summaryCompare.newSummary))
  const form = reactive(createEmptyForm())
  const activeSection = ref('profile')
  const dirty = ref(false)
  const skillDraft = ref('')
  const expectationDropdown = ref('')
  const expandedEntries = reactive({ education: [], work: [], projects: [] })
  let resettingForm = false
  const profile = computed(() => resume.jobProfile || null)
  const profileSections = computed(() => [
    { key: 'profile', label: '个人简介' },
    { key: 'education', label: '教育经历', count: form.educationExperiences.length },
    { key: 'work', label: '工作经历', count: form.workExperiences.length },
    { key: 'projects', label: '项目经历', count: form.projectExperiences.length },
  ])
  const skillTags = computed(() => skillListValue(form.skills))
  const summaryStatus = computed(() => (form.summary.trim() ? '已生成，可编辑' : '待 AI 提取'))
  const profileSaveState = computed(() => {
    if (saving.value) return '正在保存修改'
    if (error.value) return '保存失败，请重试'
    if (dirty.value) return '有未保存的修改'
    return '所有修改已保存'
  })
  const sourceProvider = computed(() => profile.value?.parsed?.source?.provider || '手动填写')
  const degreeOptions = ['大专', '本科', '硕士', '博士']
  const fullTimeOptions = ['全日制', '非全日制']
  const educationStatusOptions = ['已毕业', '在读', '肄业', '结业']
  const monthOptions = Array.from({ length: 12 }, (_, i) => String(i + 1).padStart(2, '0'))
  const currentYear = new Date().getFullYear()
  const monthYears = Array.from({ length: 45 }, (_, i) => currentYear + 3 - i)
  const workYearOptions = ['应届生', '1年以内', '1-3年', '3-5年', '5-10年', '10年以上']
  const jobStatusOptions = ['离职-随时到岗', '在职-月内到岗', '在职-考虑机会', '在职-暂不考虑']
  const arrivalOptions = ['随时', '1周内', '2周内', '1个月内', '3个月内', '不确定']
  const jobTypeOptions = ['全职', '兼职', '实习']
  const workModeOptions = ['到岗办公', '远程办公', '混合办公']
  const salaryOptions = ['面议', '3K以下', '3-5K', '5-10K', '10-15K', '15-20K', '20-30K', '30-50K', '50K以上']
  const industryOptions = ['不限', '互联网', '人工智能', '企业服务', '金融科技', '医疗健康', '教育科技', '智能制造']

  onMounted(() => {
    window.addEventListener('beforeunload', handleBeforeUnload)
    document.addEventListener('click', handleExpectationOutsideClick)
    resume.loadProfile().catch((err) => {
      error.value = err?.message || '求职画像加载失败，请稍后重试。'
      saveHint.value = '画像暂未加载成功，你仍可以先填写内容，保存时会重新连接后端。'
    })
  })
  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', handleBeforeUnload)
    document.removeEventListener('click', handleExpectationOutsideClick)
  })
  watch(() => profile.value?.resumeId, resetForm, { immediate: true })
  watch(() => profile.value?.parsed, resetForm)
  watch(
    form,
    () => {
      if (!resettingForm) dirty.value = true
    },
    { deep: true },
  )

  function createEmptyForm() {
    return {
      basic: {
        name: '',
        gender: '',
        age: '',
        birthYear: '',
        city: '',
        degree: '',
        workYears: '',
        currentTitle: '',
        phone: '',
        email: '',
      },
      personalAdvantage: '',
      status: { status: '', arrivalTime: '', jobType: '', workMode: '', description: '' },
      expectation: {
        city: '',
        position: '',
        salary: '',
        industry: '',
        jobType: '',
        negativeExcludes: '',
        rejectExcludes: '',
      },
      workExperiences: [newWork()],
      projectExperiences: [newProject()],
      educationExperiences: [newEducation()],
      skills: '',
      jobIntentions: '',
      summary: '',
    }
  }
  function newWork(data = {}) {
    return {
      id: crypto.randomUUID(),
      company: '',
      position: '',
      startDate: '',
      endDate: '',
      department: '',
      industry: '',
      description: '',
      achievement: '',
      ...data,
    }
  }
  function newProject(data = {}) {
    return {
      id: crypto.randomUUID(),
      name: '',
      role: '',
      startDate: '',
      endDate: '',
      background: '',
      techStack: '',
      techDraft: '',
      responsibility: '',
      achievement: '',
      ...data,
    }
  }
  function newEducation(data = {}) {
    return {
      id: crypto.randomUUID(),
      school: '',
      college: '',
      major: '',
      degree: '',
      fullTime: '',
      status: '',
      startDate: '',
      endDate: '',
      description: '',
      ...data,
    }
  }
  function resetForm() {
    resettingForm = true
    const parsed = profile.value?.parsed || {}
    const basic = objectValue(parsed.basic_info)
    Object.assign(form.basic, {
      name: textValue(parsed.name || basic.name),
      gender: normalizeGender(basic.gender),
      age: textValue(basic.age),
      birthYear: textValue(basic.birthYear || basic.birth_year),
      city: textValue(basic.city),
      degree: normalizeDegree(basic.degree || basic.education),
      workYears: normalizeWorkYears(parsed.years_experience || basic.workYears || basic.work_years),
      currentTitle: textValue(parsed.current_title || basic.currentTitle || basic.current_title),
      phone: textValue(basic.phone),
      email: textValue(basic.email),
    })
    form.personalAdvantage = textValue(parsed.personal_advantage)
    const status = objectValue(parsed.job_status)
    Object.assign(form.status, {
      status: normalizeOption(status.status || status.statusDesc || parsed.job_status, jobStatusOptions),
      arrivalTime: normalizeOption(status.arrivalTime || status.arrival_time, arrivalOptions),
      jobType: normalizeOption(status.jobType || status.job_type, jobTypeOptions),
      workMode: normalizeOption(status.workMode || status.work_mode, workModeOptions),
      description: textValue(status.description),
    })
    const expectation = objectValue(parsed.job_expectations)
    Object.assign(form.expectation, {
      city: textValue(expectation.city),
      position: textValue(expectation.position || expectation.positionName || parsed.expected_titles),
      salary: textValue(expectation.salary),
      industry: textValue(expectation.industry),
      jobType: normalizeOption(expectation.jobType || expectation.job_type, jobTypeOptions),
      negativeExcludes: textValue(
        expectation.negativeExcludes ||
          expectation.negative_excludes ||
          expectation.softExcludes ||
          expectation.soft_excludes ||
          expectation.deprioritizedExcludes ||
          expectation.deprioritized_excludes,
      ),
      rejectExcludes: textValue(
        expectation.rejectExcludes ||
          expectation.reject_excludes ||
          expectation.hardExcludes ||
          expectation.hard_excludes ||
          expectation.excludes,
      ),
    })
    form.workExperiences = normalizeItems(parsed.work_experiences, mapWork, newWork)
    form.projectExperiences = normalizeItems(parsed.project_experiences, mapProject, newProject)
    form.educationExperiences = normalizeItems(parsed.education_experiences, mapEducation, newEducation)
    form.skills = arrayText(parsed.skills)
    form.jobIntentions = textValue(parsed.job_intentions)
    expandedEntries.education = form.educationExperiences[0]?.id ? [form.educationExperiences[0].id] : []
    expandedEntries.work = form.workExperiences[0]?.id ? [form.workExperiences[0].id] : []
    expandedEntries.projects = []
    form.summary = textValue(parsed.summary)
    saveHint.value = ''
    error.value = ''
    dirty.value = false
    nextTick(() => {
      resettingForm = false
      dirty.value = false
    })
  }
  function objectValue(value) {
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
  }
  function textValue(value) {
    if (value === null || value === undefined) return ''
    if (Array.isArray(value))
      return value.map((item) => (typeof item === 'object' ? JSON.stringify(item) : String(item))).join('\n')
    if (typeof value === 'object') return JSON.stringify(value, null, 2)
    return String(value)
  }
  function arrayText(value) {
    return Array.isArray(value) ? value.map(textValue).join('\n') : textValue(value)
  }
  function normalizeItems(value, mapper, factory) {
    const rows = Array.isArray(value) ? value : value ? [value] : []
    const mapped = rows.map((item) => mapper(objectValue(item), textValue(item))).filter(Boolean)
    return mapped.length ? mapped : [factory()]
  }
  function mapWork(item, fallback) {
    return newWork({
      company: textValue(item.company || item.companyName),
      position: textValue(item.position || item.positionName),
      startDate: normalizeMonth(item.startDate || item.start_date),
      endDate: normalizeMonth(item.endDate || item.end_date),
      department: textValue(item.department),
      industry: textValue(item.industry),
      description: textValue(item.description || item.content || fallback),
      achievement: textValue(item.achievement),
    })
  }
  function mapProject(item, fallback) {
    return newProject({
      name: textValue(item.name || item.projectName),
      role: textValue(item.role),
      startDate: normalizeMonth(item.startDate || item.start_date),
      endDate: normalizeMonth(item.endDate || item.end_date),
      background: textValue(item.background),
      techStack: arrayText(item.techStack || item.tech_stack),
      responsibility: textValue(item.responsibility || item.description || fallback),
      achievement: textValue(item.achievement),
    })
  }
  function mapEducation(item, fallback) {
    const startDate = normalizeMonth(item.startDate || item.start_date)
    const endDate = normalizeMonth(item.endDate || item.end_date)
    return newEducation({
      school: textValue(item.school || item.schoolName),
      college: textValue(item.college || item.collegeName || item.college_name || item.department),
      major: textValue(item.major),
      degree: normalizeDegree(item.degree),
      fullTime: normalizeFullTime(item.fullTime || item.full_time || item.educationType || item.education_type),
      status: normalizeEducationStatus(item.status || item.educationStatus || item.education_status),
      startDate,
      endDate,
      description: textValue(item.description || fallback),
    })
  }
  function normalizeOption(value, options) {
    const text = textValue(value).trim()
    if (!text) return ''
    return options.includes(text) ? text : ''
  }
  function normalizeGender(value) {
    const text = textValue(value).trim()
    if (['男', '男性', 'male', 'M'].includes(text)) return '男'
    if (['女', '女性', 'female', 'F'].includes(text)) return '女'
    return normalizeOption(text, ['男', '女'])
  }
  function normalizeDegree(value) {
    const text = textValue(value).trim()
    if (!text) return ''
    if (text.includes('博士')) return '博士'
    if (text.includes('硕士') || text.includes('研究生')) return '硕士'
    if (text.includes('本科')) return '本科'
    if (text.includes('大专') || text.includes('专科')) return '大专'
    return normalizeOption(text, degreeOptions)
  }
  function normalizeFullTime(value) {
    const text = textValue(value).trim()
    if (!text) return ''
    if (/非全日|非统招|成人|自考|函授|网络教育/i.test(text)) return '非全日制'
    if (/全日|统招/i.test(text)) return '全日制'
    return normalizeOption(text, fullTimeOptions)
  }
  function normalizeEducationStatus(value) {
    const text = textValue(value).trim()
    if (!text) return ''
    if (/在读|就读/i.test(text)) return '在读'
    if (/肄业/i.test(text)) return '肄业'
    if (/结业/i.test(text)) return '结业'
    if (/毕业|已获|完成/i.test(text)) return '已毕业'
    return normalizeOption(text, educationStatusOptions)
  }
  function normalizeWorkYears(value) {
    const text = textValue(value).trim()
    if (!text) return ''
    if (workYearOptions.includes(text)) return text
    if (/应届|毕业生/.test(text)) return '应届生'
    const match = text.match(/(\d+)/)
    if (!match) return ''
    const years = Number(match[1])
    if (years <= 0) return '1年以内'
    if (years <= 3) return '1-3年'
    if (years <= 5) return '3-5年'
    if (years <= 10) return '5-10年'
    return '10年以上'
  }
  function normalizeMonth(value) {
    const text = textValue(value).trim()
    const match = text.match(/(\d{4})[.\/-]?(\d{1,2})/)
    if (!match) return ''
    const month = String(Math.max(1, Math.min(12, Number(match[2])))).padStart(2, '0')
    return `${match[1]}-${month}`
  }
  function displayMonth(value) {
    return textValue(value).replace('-', '.')
  }
  function monthYear(value) {
    const match = normalizeMonth(value).match(/^(\d{4})-(\d{2})$/)
    return match ? match[1] : ''
  }
  function monthMonth(value) {
    const match = normalizeMonth(value).match(/^(\d{4})-(\d{2})$/)
    return match ? match[2] : ''
  }
  function updateMonth(item, key, part, value) {
    const year = part === 'year' ? value : monthYear(item[key])
    const month = part === 'month' ? value : monthMonth(item[key])
    item[key] = year && month ? `${year}-${month}` : ''
  }
  function monthRange(start, end) {
    return [displayMonth(start), displayMonth(end)].filter(Boolean).join('-')
  }
  function projectMonthRange(start, end) {
    const startMonth = displayMonth(start)
    const endMonth = displayMonth(end)
    if (startMonth && !endMonth) return `${startMonth}-至今`
    return [startMonth, endMonth].filter(Boolean).join('-')
  }
  function listValue(value) {
    return String(value || '')
      .split(/[,，、\n\r\t ]+/)
      .map((item) => item.trim())
      .filter(Boolean)
  }
  function skillListValue(value) {
    return String(value || '')
      .split(/[,，、\n\r\t]+/)
      .map((item) => item.trim())
      .filter(Boolean)
  }
  function projectTechTags(value) {
    return [
      ...new Set(
        String(value || '')
          .split(/[,，、;；\n\r\t]+/)
          .map((item) => item.trim())
          .filter(Boolean),
      ),
    ]
  }
  function filledRows(rows) {
    return rows.filter((row) =>
      Object.entries(row).some(([key, value]) => !['id', 'techDraft'].includes(key) && String(value || '').trim()),
    )
  }
  function expectationPayload() {
    const negativeList = listValue(form.expectation.negativeExcludes)
    const rejectList = listValue(form.expectation.rejectExcludes)
    return {
      city: form.expectation.city,
      position: form.expectation.position,
      salary: form.expectation.salary,
      industry: form.expectation.industry,
      jobType: form.expectation.jobType,
      negativeExcludes: form.expectation.negativeExcludes,
      rejectExcludes: form.expectation.rejectExcludes,
      negative_excludes: negativeList,
      reject_excludes: rejectList,
      soft_excludes: negativeList,
      hard_excludes: rejectList,
      excludes: rejectList,
    }
  }
  function buildParsed(provider = sourceProvider.value) {
    const work = filledRows(form.workExperiences).map((item) => ({ ...item }))
    const projects = filledRows(form.projectExperiences).map((item) => {
      const project = { ...item }
      delete project.techDraft
      return project
    })
    const education = filledRows(form.educationExperiences).map((item) => ({ ...item }))
    const expectation = expectationPayload()
    return {
      name: form.basic.name.trim(),
      summary: form.summary.trim(),
      current_title: form.basic.currentTitle.trim(),
      years_experience: form.basic.workYears.trim(),
      expected_titles: listValue(form.expectation.position),
      skills: skillListValue(form.skills),
      basic_info: { ...form.basic },
      personal_advantage: form.personalAdvantage.trim(),
      job_status: { ...form.status },
      job_expectations: expectation,
      work_experiences: work,
      project_experiences: projects,
      education_experiences: education,
      job_intentions: form.jobIntentions.trim(),
      education,
      experiences: work,
      projects,
      expectations: expectation,
      source: {
        type: 'job_profile',
        provider,
        synced_at: new Date().toISOString(),
        raw: profile.value?.parsed?.source?.raw || {},
      },
    }
  }
  async function generateSummary() {
    if (generatingSummary.value) return
    const hasSummary = Boolean(form.summary.trim())
    await requestAiSummary({ autoApply: !hasSummary, showCompare: hasSummary, saveAfterApply: false })
  }
  async function requestAiSummary({ autoApply, showCompare, saveAfterApply }) {
    generatingSummary.value = true
    error.value = ''
    saveHint.value = 'AI 正在生成画像摘要'
    try {
      const parsed = buildParsed('AI 提炼摘要')
      const result = await generateJobProfileSummary(parsed)
      const newSummary = String(result?.newSummary || '').trim()
      if (!newSummary) throw new Error('AI 返回的画像摘要为空')
      if (autoApply) {
        form.summary = newSummary
        dirty.value = true
        saveHint.value = saveAfterApply ? 'AI 已生成画像摘要，正在保存' : 'AI 已生成画像摘要，请确认后保存。'
        if (saveAfterApply) {
          await resume.saveProfile(buildParsed('AI 自动生成摘要'))
          dirty.value = false
          saveHint.value = '求职画像已保存，并已自动补全画像摘要。'
        }
        return
      }
      if (showCompare) {
        summaryCompare.oldSummary = String(result?.oldSummary || form.summary || '')
        summaryCompare.newSummary = newSummary
        summaryCompare.highlights = Array.isArray(result?.highlights) ? result.highlights : []
        summaryCompare.provider = result?.provider || 'AI'
        summaryCompare.visible = true
        saveHint.value = 'AI 已生成新摘要，请在弹窗中对比后决定是否更新。'
      }
    } catch (err) {
      error.value = err?.message || String(err)
      showWarning(error.value || 'AI 画像摘要生成失败')
    } finally {
      generatingSummary.value = false
    }
  }
  function validateProfileForm() {
    validateLength(form.basic.name, '姓名', { max: 64 })
    if (form.basic.age !== '') validateInteger(form.basic.age, '年龄', { min: 16, max: 80 })
    validateLength(form.basic.city, '所在城市', { max: 64 })
    validateLength(form.expectation.position, '期望岗位', { max: 120 })
    validateLength(form.personalAdvantage, '个人优势', { max: 5000 })
    validateLength(form.summary, '画像摘要', { max: 5000 })
    if (form.basic.phone && !/^1\d{10}$/.test(form.basic.phone.trim())) throw new Error('手机号格式不正确')
    if (form.basic.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.basic.email.trim()))
      throw new Error('邮箱格式不正确')
    validateTags(skillTags.value, '技能标签', { maxCount: 50, maxLength: 64 })
    for (const item of form.educationExperiences) {
      if (![item.school, item.college, item.major, item.startDate, item.endDate].some(Boolean)) continue
      validateLength(item.school, '学校名称', { max: 120 })
      validateMonthRange(item.startDate, item.endDate, '教育经历')
    }
    for (const item of form.workExperiences) {
      if (![item.company, item.position, item.startDate, item.endDate].some(Boolean)) continue
      validateLength(item.company, '公司名称', { max: 120 })
      validateLength(item.position, '职位名称', { max: 120 })
      validateMonthRange(item.startDate, item.endDate, '工作经历')
    }
    for (const item of form.projectExperiences) {
      if (![item.name, item.role, item.startDate, item.endDate].some(Boolean)) continue
      validateLength(item.name, '项目名称', { max: 120 })
      validateLength(item.role, '项目角色', { max: 120 })
      validateMonthRange(item.startDate, item.endDate, '项目经历')
    }
  }

  async function saveProfile() {
    error.value = ''
    saveHint.value = ''
    try {
      validateProfileForm()
    } catch (err) {
      error.value = err.message
      showWarning(error.value)
      return false
    }
    saving.value = true
    const summaryWasEmpty = !form.summary.trim()
    try {
      await resume.saveProfile(buildParsed('手动填写'))
      dirty.value = false
      saveHint.value = summaryWasEmpty ? '求职画像已保存，正在生成画像摘要' : '求职画像已保存。'
      if (summaryWasEmpty) void requestAiSummary({ autoApply: true, showCompare: false, saveAfterApply: true })
      return true
    } catch (err) {
      error.value = err.message
      showWarning(error.value || '求职画像保存失败')
      return false
    } finally {
      saving.value = false
    }
  }
  async function saveOverviewProfile() {
    await saveProfile()
  }
  function buildSummaryDiff(oldText, newText) {
    const oldTokens = diffTokens(oldText)
    const newTokens = diffTokens(newText)
    if (!oldTokens.length && !newTokens.length) return []
    const dp = Array.from({ length: oldTokens.length + 1 }, () => Array(newTokens.length + 1).fill(0))
    for (let i = oldTokens.length - 1; i >= 0; i--) {
      for (let j = newTokens.length - 1; j >= 0; j--) {
        dp[i][j] = oldTokens[i] === newTokens[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
      }
    }
    const parts = []
    let i = 0,
      j = 0
    while (i < oldTokens.length && j < newTokens.length) {
      if (oldTokens[i] === newTokens[j]) {
        pushDiffPart(parts, 'same', oldTokens[i])
        i++
        j++
      } else if (dp[i + 1][j] >= dp[i][j + 1]) {
        pushDiffPart(parts, 'removed', oldTokens[i])
        i++
      } else {
        pushDiffPart(parts, 'added', newTokens[j])
        j++
      }
    }
    while (i < oldTokens.length) pushDiffPart(parts, 'removed', oldTokens[i++])
    while (j < newTokens.length) pushDiffPart(parts, 'added', newTokens[j++])
    return parts
  }
  function diffTokens(value) {
    return String(value || '').match(/[A-Za-z0-9_+#./-]+|[\u4e00-\u9fa5]{1,4}|\s+|[^\s]/g) || []
  }
  function pushDiffPart(parts, type, text) {
    const last = parts[parts.length - 1]
    if (last && last.type === type) last.text += text
    else parts.push({ type, text })
  }
  function closeSummaryCompare() {
    summaryCompare.visible = false
  }
  function applyAiSummary() {
    form.summary = summaryCompare.newSummary
    dirty.value = true
    summaryCompare.visible = false
    saveHint.value = '已使用 AI 版本，请保存修改后生效。'
  }
  function showWarning(message) {
    const text = String(message || '').trim()
    if (!text || text === lastWarning) return
    lastWarning = text
    warningMessage.value = text
  }
  function closeWarning() {
    warningMessage.value = ''
    lastWarning = ''
    if (resume.error) resume.error = ''
  }
  function openExpectationDropdown(kind) {
    expectationDropdown.value = kind
  }
  function closeExpectationDropdown() {
    expectationDropdown.value = ''
  }
  function toggleExpectationDropdown(kind) {
    expectationDropdown.value = expectationDropdown.value === kind ? '' : kind
  }
  function selectExpectationOption(kind, value) {
    form.expectation[kind] = value
    closeExpectationDropdown()
  }
  function handleExpectationOutsideClick(event) {
    if (!event.target.closest('[data-profile-editable-select]')) closeExpectationDropdown()
  }
  function addSkills() {
    const normalized = skillDraft.value.trim()
    if (!normalized) return
    if (/[,，、;；\n\r\t]/.test(normalized)) {
      showWarning('请一次添加一个技能标签。')
      return
    }
    const existing = skillTags.value
    if (!existing.some((skill) => skill.toLowerCase() === normalized.toLowerCase())) {
      form.skills = [...existing, normalized].join('\n')
    }
    skillDraft.value = ''
  }
  function removeSkill(skill) {
    form.skills = skillTags.value.filter((item) => item !== skill).join('\n')
  }
  function handleSkillKeydown(event) {
    if (event.key === 'Enter' || event.key === ',' || event.key === '，' || event.key === '、') {
      event.preventDefault()
      addSkills()
    }
  }
  function addProjectTech(item) {
    const normalized = item.techDraft.trim()
    if (!normalized) return
    if (/[,，、;；\n\r\t]/.test(normalized)) {
      showWarning('请一次添加一个项目技术标签。')
      return
    }
    const existing = projectTechTags(item.techStack)
    if (!existing.some((tech) => tech.toLowerCase() === normalized.toLowerCase())) {
      item.techStack = [...existing, normalized].join('\n')
    }
    item.techDraft = ''
  }
  function removeProjectTech(item, tech) {
    item.techStack = projectTechTags(item.techStack)
      .filter((itemTech) => itemTech !== tech)
      .join('\n')
  }
  function handleBeforeUnload(event) {
    if (!dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  function isEntryExpanded(kind, id) {
    return expandedEntries[kind].includes(id)
  }
  function setEntryExpanded(kind, id, expanded) {
    const entries = expandedEntries[kind]
    if (expanded && !entries.includes(id)) entries.push(id)
    if (!expanded) expandedEntries[kind] = entries.filter((entryId) => entryId !== id)
  }
  function toggleEntry(kind, id, event) {
    setEntryExpanded(kind, id, event.target.open)
  }
  function addWork() {
    const item = newWork()
    form.workExperiences.push(item)
    setEntryExpanded('work', item.id, true)
  }
  function removeWork(index) {
    if (!window.confirm('确认删除这段工作经历？')) return
    const [removed] = form.workExperiences.splice(index, 1)
    if (removed) setEntryExpanded('work', removed.id, false)
    if (!form.workExperiences.length) addWork()
  }
  function addProject() {
    const item = newProject()
    form.projectExperiences.push(item)
    setEntryExpanded('projects', item.id, true)
  }
  function removeProject(index) {
    if (!window.confirm('确认删除这段项目经历？')) return
    const [removed] = form.projectExperiences.splice(index, 1)
    if (removed) setEntryExpanded('projects', removed.id, false)
    if (!form.projectExperiences.length) addProject()
  }
  function addEducation() {
    const item = newEducation()
    form.educationExperiences.push(item)
    setEntryExpanded('education', item.id, true)
  }
  function removeEducation(index) {
    if (!window.confirm('确认删除这段教育经历？')) return
    const [removed] = form.educationExperiences.splice(index, 1)
    if (removed) setEntryExpanded('education', removed.id, false)
    if (!form.educationExperiences.length) addEducation()
  }

  return {
    resume,
    saving,
    generatingSummary,
    error,
    saveHint,
    warningMessage,
    profileOverviewVisible,
    lastWarning,
    summaryCompare,
    summaryDiff,
    form,
    activeSection,
    dirty,
    skillDraft,
    expectationDropdown,
    expandedEntries,
    resettingForm,
    profile,
    profileSections,
    skillTags,
    summaryStatus,
    profileSaveState,
    sourceProvider,
    degreeOptions,
    fullTimeOptions,
    educationStatusOptions,
    monthOptions,
    currentYear,
    monthYears,
    workYearOptions,
    jobStatusOptions,
    arrivalOptions,
    jobTypeOptions,
    workModeOptions,
    salaryOptions,
    industryOptions,
    createEmptyForm,
    newWork,
    newProject,
    newEducation,
    resetForm,
    objectValue,
    textValue,
    arrayText,
    normalizeItems,
    mapWork,
    mapProject,
    mapEducation,
    normalizeOption,
    normalizeGender,
    normalizeDegree,
    normalizeFullTime,
    normalizeEducationStatus,
    normalizeWorkYears,
    normalizeMonth,
    displayMonth,
    monthYear,
    monthMonth,
    updateMonth,
    monthRange,
    projectMonthRange,
    listValue,
    skillListValue,
    projectTechTags,
    filledRows,
    expectationPayload,
    buildParsed,
    generateSummary,
    requestAiSummary,
    validateProfileForm,
    saveProfile,
    saveOverviewProfile,
    buildSummaryDiff,
    diffTokens,
    pushDiffPart,
    closeSummaryCompare,
    applyAiSummary,
    showWarning,
    closeWarning,
    openExpectationDropdown,
    closeExpectationDropdown,
    toggleExpectationDropdown,
    selectExpectationOption,
    handleExpectationOutsideClick,
    addSkills,
    removeSkill,
    handleSkillKeydown,
    addProjectTech,
    removeProjectTech,
    handleBeforeUnload,
    isEntryExpanded,
    setEntryExpanded,
    toggleEntry,
    addWork,
    removeWork,
    addProject,
    removeProject,
    addEducation,
    removeEducation,
  }
}
