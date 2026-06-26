<template>
  <section class="system-page boss-resume-page">
    <header class="page-header profile-page-header">
      <div>
        <p class="eyebrow">Job Seeker Profile</p>
        <h1>求职画像</h1>
        <p>维护对岗位筛选有用的信息，保存后用于推荐、匹配和问答上下文。</p>
      </div>
      <div class="profile-action-buttons">
        <button class="secondary-btn" :disabled="syncing || saving" @click="syncBoss">{{ syncing ? '同步中' : '同步 Boss 在线简历' }}</button>
        <button class="primary-btn" :disabled="saving || syncing" @click="saveProfile">{{ saving ? '保存中' : '保存画像' }}</button>
        <button class="secondary-btn" :disabled="saving || syncing" @click="clearForm">清空</button>
      </div>
    </header>

    <div class="boss-resume-layout">
      <section class="boss-section-card glass-card profile-summary-card">
        <div class="card-title">
          <div><h2>画像摘要</h2><span>优先展示在顶部，用于推荐、匹配和问答上下文</span></div>
          <button type="button" :disabled="generatingSummary" @click="generateSummary">{{ generatingSummary ? 'AI 生成中' : 'AI 提炼摘要' }}</button>
        </div>
        <textarea v-model="form.summary" class="full-textarea" rows="5" placeholder="可以手动填写。若保存时为空，系统会自动异步生成；若已有摘要，可点击 AI 提炼摘要查看前后对比后再决定是否更新。" />
      </section>

      <section class="boss-section-card glass-card">
        <div class="card-title"><h2>基本信息</h2><span>填写用于岗位推荐和简历匹配的基础信息</span></div>
        <div class="boss-fixed-grid compact-profile-grid">
          <label><span>姓名</span><input v-model="form.basic.name" placeholder="请输入姓名" /></label>
          <label><span>性别</span><select v-model="form.basic.gender"><option value="" disabled>请选择</option><option>男</option><option>女</option></select></label>
          <label><span>年龄</span><input v-model="form.basic.age" placeholder="例如：28" /></label>
          <label><span>所在城市</span><input v-model="form.basic.city" placeholder="例如：上海" /></label>
          <label><span>最高学历</span><select v-model="form.basic.degree"><option value="" disabled>请选择</option><option v-for="item in degreeOptions" :key="item">{{ item }}</option></select></label>
          <label><span>工作年限</span><select v-model="form.basic.workYears"><option value="" disabled>请选择</option><option v-for="item in workYearOptions" :key="item">{{ item }}</option></select></label>
          <label class="wide"><span>当前职位 / 方向</span><input v-model="form.basic.currentTitle" placeholder="例如：AI 应用开发工程师" /></label>
        </div>
      </section>

      <section class="boss-section-card glass-card">
        <div class="card-title"><h2>个人优势</h2><span>概括核心能力、项目亮点和职业优势</span></div>
        <textarea v-model="form.personalAdvantage" class="full-textarea" rows="6" placeholder="个人优势、自我介绍、核心竞争力" />
      </section>

      <section class="boss-section-card glass-card">
        <div class="card-title"><h2>求职状态</h2><span>离职/在职、到岗时间等</span></div>
        <div class="boss-fixed-grid">
          <label><span>当前状态</span><select v-model="form.status.status"><option value="" disabled>请选择</option><option v-for="item in jobStatusOptions" :key="item">{{ item }}</option></select></label>
          <label><span>到岗时间</span><select v-model="form.status.arrivalTime"><option value="" disabled>请选择</option><option v-for="item in arrivalOptions" :key="item">{{ item }}</option></select></label>
          <label><span>求职类型</span><select v-model="form.status.jobType"><option value="" disabled>请选择</option><option v-for="item in jobTypeOptions" :key="item">{{ item }}</option></select></label>
          <label><span>工作模式</span><select v-model="form.status.workMode"><option value="" disabled>请选择</option><option v-for="item in workModeOptions" :key="item">{{ item }}</option></select></label>
        </div>
      </section>

      <section class="boss-section-card glass-card">
        <div class="card-title"><h2>求职期望</h2><span>期望城市、岗位、薪资、行业</span></div>
        <div class="boss-fixed-grid">
          <label><span>期望城市</span><input v-model="form.expectation.city" placeholder="例如：上海、杭州" /></label>
          <label><span>期望岗位</span><input v-model="form.expectation.position" placeholder="例如：AI 应用开发工程师" /></label>
          <label><span>期望薪资</span><select v-model="form.expectation.salary"><option value="" disabled>请选择</option><option v-for="item in salaryOptions" :key="item">{{ item }}</option></select></label>
          <label><span>期望行业</span><select v-model="form.expectation.industry"><option value="" disabled>请选择</option><option v-for="item in industryOptions" :key="item">{{ item }}</option></select></label>
          <label class="wide"><span>强减分项</span><input v-model="form.expectation.negativeExcludes" placeholder="可接受但显著降低匹配，例如：出差、大小周、加班多" /></label>
          <label class="wide"><span>硬性拒绝项</span><input v-model="form.expectation.rejectExcludes" placeholder="出现就直接拒绝，例如：外包、劳务派遣、驻场" /></label>
        </div>
      </section>

      <section class="boss-section-card glass-card">
        <div class="card-title"><h2>教育经历</h2><button @click="addEducation">添加教育经历</button></div>
        <div v-for="(item, index) in form.educationExperiences" :key="item.id" class="repeat-card">
          <div class="repeat-head"><strong>教育经历 {{ index + 1 }}</strong><button class="row-delete" @click="removeEducation(index)">删除</button></div>
          <div class="boss-fixed-grid">
            <label><span>学校名称</span><input v-model="item.school" placeholder="学校名称" /></label>
            <label><span>专业</span><input v-model="item.major" placeholder="专业" /></label>
            <label><span>入学时间</span><div class="pretty-month-field"><select :value="monthYear(item.startDate)" @change="updateMonth(item, 'startDate', 'year', $event.target.value)"><option value="">年份</option><option v-for="year in monthYears" :key="year" :value="year">{{ year }}年</option></select><select :value="monthMonth(item.startDate)" @change="updateMonth(item, 'startDate', 'month', $event.target.value)"><option value="">月份</option><option v-for="month in monthOptions" :key="month" :value="month">{{ Number(month) }}月</option></select></div></label>
            <label><span>毕业时间</span><div class="pretty-month-field"><select :value="monthYear(item.endDate)" @change="updateMonth(item, 'endDate', 'year', $event.target.value)"><option value="">年份</option><option v-for="year in monthYears" :key="year" :value="year">{{ year }}年</option></select><select :value="monthMonth(item.endDate)" @change="updateMonth(item, 'endDate', 'month', $event.target.value)"><option value="">月份</option><option v-for="month in monthOptions" :key="month" :value="month">{{ Number(month) }}月</option></select></div></label>
            <label><span>学历</span><select v-model="item.degree"><option value="" disabled>请选择</option><option v-for="degree in degreeOptions" :key="degree">{{ degree }}</option></select></label>
            <label><span>是否全日制</span><select v-model="item.fullTime"><option value="" disabled>请选择</option><option v-for="opt in fullTimeOptions" :key="opt">{{ opt }}</option></select></label>
            <label><span>学历状态</span><select v-model="item.status"><option value="" disabled>请选择</option><option v-for="opt in educationStatusOptions" :key="opt">{{ opt }}</option></select></label>
            <label><span>在校时间</span><input :value="monthRange(item.startDate, item.endDate)" disabled placeholder="自动生成" /></label>
          </div>
        </div>
      </section>

      <section class="boss-section-card glass-card">
        <div class="card-title"><h2>工作经历</h2><button @click="addWork">添加工作经历</button></div>
        <div v-for="(item, index) in form.workExperiences" :key="item.id" class="repeat-card">
          <div class="repeat-head"><strong>工作经历 {{ index + 1 }}</strong><button class="row-delete" @click="removeWork(index)">删除</button></div>
          <div class="boss-fixed-grid">
            <label><span>公司名称</span><input v-model="item.company" placeholder="公司名称" /></label>
            <label><span>职位名称</span><input v-model="item.position" placeholder="职位名称" /></label>
            <label><span>开始时间</span><div class="pretty-month-field"><select :value="monthYear(item.startDate)" @change="updateMonth(item, 'startDate', 'year', $event.target.value)"><option value="">年份</option><option v-for="year in monthYears" :key="year" :value="year">{{ year }}年</option></select><select :value="monthMonth(item.startDate)" @change="updateMonth(item, 'startDate', 'month', $event.target.value)"><option value="">月份</option><option v-for="month in monthOptions" :key="month" :value="month">{{ Number(month) }}月</option></select></div></label>
            <label><span>结束时间</span><div class="pretty-month-field"><select :value="monthYear(item.endDate)" @change="updateMonth(item, 'endDate', 'year', $event.target.value)"><option value="">年份</option><option v-for="year in monthYears" :key="year" :value="year">{{ year }}年</option></select><select :value="monthMonth(item.endDate)" @change="updateMonth(item, 'endDate', 'month', $event.target.value)"><option value="">月份</option><option v-for="month in monthOptions" :key="month" :value="month">{{ Number(month) }}月</option></select></div></label>
            <label class="wide"><span>工作内容</span><textarea v-model="item.description" rows="4" placeholder="工作职责、负责模块、技术栈、协作方式，建议 3-5 条" /></label>
            <label class="wide"><span>工作业绩</span><textarea v-model="item.achievement" rows="4" placeholder="量化成果、项目交付、效率提升、获奖或业务价值，建议 2-4 条" /></label>
          </div>
        </div>
      </section>

      <section class="boss-section-card glass-card">
        <div class="card-title"><h2>项目经历</h2><button @click="addProject">添加项目经历</button></div>
        <div v-for="(item, index) in form.projectExperiences" :key="item.id" class="repeat-card">
          <div class="repeat-head"><strong>项目经历 {{ index + 1 }}</strong><button class="row-delete" @click="removeProject(index)">删除</button></div>
          <div class="boss-fixed-grid">
            <label><span>项目名称</span><input v-model="item.name" placeholder="项目名称" /></label>
            <label><span>项目角色</span><input v-model="item.role" placeholder="例如：后端负责人" /></label>
            <label class="wide"><span>技术栈</span><input v-model="item.techStack" placeholder="Java、Spring Boot、MySQL、Redis" /></label>
            <label class="wide"><span>项目职责与成果</span><textarea v-model="item.responsibility" rows="4" placeholder="背景、职责、难点、成果，建议 3-5 条" /></label>
          </div>
        </div>
      </section>

      <section class="boss-section-card glass-card">
        <div class="card-title"><h2>技能标签</h2><span>用于岗位筛选和匹配，多个技能可用逗号或换行分隔</span></div>
        <div class="boss-fixed-grid">
          <label class="wide"><span>技能标签</span><textarea v-model="form.skills" rows="3" placeholder="Java、Spring Boot、MySQL、Redis，多个技能可用逗号或换行分隔" /></label>
        </div>
      </section>
    </div>

    <div v-if="summaryCompare.visible" class="modal-mask profile-ai-modal-mask" @click.self="closeSummaryCompare">
      <div class="modal-card profile-ai-modal-card">
        <button class="close" @click="closeSummaryCompare">×</button>
        <p class="eyebrow">AI Summary Compare</p>
        <h2>是否使用 AI 更新画像摘要？</h2>
        <p class="compare-tip">系统已生成新的画像摘要。请对比当前版本和 AI 版本，确认后再覆盖。</p>
        <div class="summary-compare-grid">
          <section><strong>当前摘要</strong><p>{{ summaryCompare.oldSummary || '当前为空' }}</p></section>
          <section><strong>AI 建议</strong><p>{{ summaryCompare.newSummary }}</p></section>
        </div>
        <div class="summary-diff-card">
          <strong>差异对比</strong>
          <div class="summary-diff-legend"><span class="removed">删除</span><span class="added">新增</span><span>未标记为保留内容</span></div>
          <p class="summary-diff-text">
            <template v-for="(part, index) in summaryDiff" :key="`${part.type}-${index}-${part.text}`">
              <span :class="['summary-diff-token', part.type]">{{ part.text }}</span>
            </template>
          </p>
        </div>
        <div v-if="summaryCompare.highlights?.length" class="summary-highlights"><strong>AI 提炼依据</strong><span v-for="item in summaryCompare.highlights" :key="item">{{ item }}</span></div>
        <div class="modal-actions">
          <button class="secondary-btn" @click="closeSummaryCompare">保留当前</button>
          <button class="primary-btn" @click="applyAiSummary">使用 AI 版本</button>
        </div>
      </div>
    </div>

    <div v-if="warningMessage" class="modal-mask warning-modal-mask" @click.self="closeWarning">
      <div class="modal-card warning-modal-card">
        <button class="close" @click="closeWarning">×</button>
        <p class="eyebrow">Warning</p>
        <h2>操作提示</h2>
        <p>{{ warningMessage }}</p>
        <div class="modal-actions"><button class="primary-btn" @click="closeWarning">我知道了</button></div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { generateJobProfileSummary } from '../api/resume'
import { useResumeStore } from '../stores/resume'

const resume = useResumeStore()
const saving = ref(false)
const syncing = ref(false)
const generatingSummary = ref(false)
const error = ref('')
const saveHint = ref('')
const warningMessage = ref('')
let lastWarning = ''
const summaryCompare = reactive({ visible: false, oldSummary: '', newSummary: '', highlights: [], provider: '' })
const summaryDiff = computed(() => buildSummaryDiff(summaryCompare.oldSummary, summaryCompare.newSummary))
const form = reactive(createEmptyForm())
const profile = computed(() => isProfile(resume.current) ? resume.current : resume.items.find(isProfile) || null)
const sourceProvider = computed(() => profile.value?.parsed?.source?.provider || '手动填写')
const degreeOptions = ['大专', '本科', '硕士', '博士']
const fullTimeOptions = ['全日制', '非全日制']
const educationStatusOptions = ['已毕业', '在读', '肄业', '结业']
const monthOptions = Array.from({ length: 12 }, (_, i) => String(i + 1).padStart(2, '0'))
const currentYear = new Date().getFullYear()
const monthYears = Array.from({ length: 45 }, (_, i) => currentYear + 3 - i)
const workYearOptions = ['应届生', '1年以内', '1-3年', '3-5年', '5-10年', '10年以上']
const jobStatusOptions = ['离职-随时到岗', '在职-月内到岗', '在职-考虑机会', '在职-暂不考虑']
const arrivalOptions = ['随时', '1周内', '2周内', '1个月内', '3个月内']
const jobTypeOptions = ['全职', '兼职', '实习']
const workModeOptions = ['到岗办公', '远程办公', '混合办公']
const salaryOptions = ['面议', '3K以下', '3-5K', '5-10K', '10-15K', '15-20K', '20-30K', '30-50K', '50K以上']
const industryOptions = ['不限', '互联网', '人工智能', '企业服务', '金融科技', '医疗健康', '教育科技', '智能制造']

onMounted(() => {
  resume.loadProfile().catch(err => {
    error.value = err?.message || '求职画像加载失败，请稍后重试。'
    saveHint.value = '画像暂未加载成功，你仍可以先填写内容，保存时会重新连接后端。'
  })
})
watch(() => profile.value?.resumeId, resetForm, { immediate: true })
watch(() => profile.value?.parsed, resetForm)

function createEmptyForm() {
  return {
    basic: { name: '', gender: '', age: '', birthYear: '', city: '', degree: '', workYears: '', currentTitle: '', phone: '', email: '' },
    personalAdvantage: '',
    status: { status: '', arrivalTime: '', jobType: '', workMode: '', description: '' },
    expectation: { city: '', position: '', salary: '', industry: '', jobType: '', negativeExcludes: '', rejectExcludes: '' },
    workExperiences: [newWork()],
    projectExperiences: [newProject()],
    educationExperiences: [newEducation()],
    skills: '',
    jobIntentions: '',
    summary: '',
  }
}
function newWork(data = {}) { return { id: crypto.randomUUID(), company: '', position: '', startDate: '', endDate: '', department: '', industry: '', description: '', achievement: '', ...data } }
function newProject(data = {}) { return { id: crypto.randomUUID(), name: '', role: '', startDate: '', endDate: '', background: '', techStack: '', responsibility: '', achievement: '', ...data } }
function newEducation(data = {}) { return { id: crypto.randomUUID(), school: '', major: '', degree: '', fullTime: '', status: '', startDate: '', endDate: '', period: '', description: '', ...data } }
function isProfile(item) { const type = item?.parsed?.source?.type; return type === 'job_profile' || type === 'boss_online_resume' }
function resetForm() {
  const parsed = profile.value?.parsed || {}
  const basic = objectValue(parsed.basic_info)
  Object.assign(form.basic, {
    name: textValue(parsed.name || basic.name), gender: normalizeGender(basic.gender), age: textValue(basic.age), birthYear: textValue(basic.birthYear || basic.birth_year),
    city: textValue(basic.city), degree: normalizeDegree(basic.degree || basic.education), workYears: normalizeWorkYears(parsed.years_experience || basic.workYears || basic.work_years),
    currentTitle: textValue(parsed.current_title || basic.currentTitle || basic.current_title), phone: textValue(basic.phone), email: textValue(basic.email),
  })
  form.personalAdvantage = textValue(parsed.personal_advantage)
  const status = objectValue(parsed.job_status)
  Object.assign(form.status, { status: normalizeOption(status.status || status.statusDesc || parsed.job_status, jobStatusOptions), arrivalTime: normalizeOption(status.arrivalTime || status.arrival_time, arrivalOptions), jobType: normalizeOption(status.jobType || status.job_type, jobTypeOptions), workMode: normalizeOption(status.workMode || status.work_mode, workModeOptions), description: textValue(status.description) })
  const expectation = objectValue(parsed.job_expectations)
  Object.assign(form.expectation, {
    city: textValue(expectation.city), position: textValue(expectation.position || expectation.positionName || parsed.expected_titles), salary: normalizeOption(expectation.salary, salaryOptions),
    industry: normalizeOption(expectation.industry, industryOptions), jobType: normalizeOption(expectation.jobType || expectation.job_type, jobTypeOptions),
    negativeExcludes: textValue(expectation.negativeExcludes || expectation.negative_excludes || expectation.softExcludes || expectation.soft_excludes || expectation.deprioritizedExcludes || expectation.deprioritized_excludes),
    rejectExcludes: textValue(expectation.rejectExcludes || expectation.reject_excludes || expectation.hardExcludes || expectation.hard_excludes || expectation.excludes),
  })
  form.workExperiences = normalizeItems(parsed.work_experiences, mapWork, newWork)
  form.projectExperiences = normalizeItems(parsed.project_experiences, mapProject, newProject)
  form.educationExperiences = normalizeItems(parsed.education_experiences, mapEducation, newEducation)
  form.skills = arrayText(parsed.skills)
  form.jobIntentions = textValue(parsed.job_intentions)
  form.summary = textValue(parsed.summary)
  saveHint.value = ''
  error.value = ''
}
function objectValue(value) { return value && typeof value === 'object' && !Array.isArray(value) ? value : {} }
function textValue(value) {
  if (value === null || value === undefined) return ''
  if (Array.isArray(value)) return value.map(item => typeof item === 'object' ? JSON.stringify(item) : String(item)).join('\n')
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}
function arrayText(value) { return Array.isArray(value) ? value.map(textValue).join('\n') : textValue(value) }
function normalizeItems(value, mapper, factory) {
  const rows = Array.isArray(value) ? value : (value ? [value] : [])
  const mapped = rows.map(item => mapper(objectValue(item), textValue(item))).filter(Boolean)
  return mapped.length ? mapped : [factory()]
}
function mapWork(item, fallback) { return newWork({ company: textValue(item.company || item.companyName), position: textValue(item.position || item.positionName), startDate: normalizeMonth(item.startDate || item.start_date), endDate: normalizeMonth(item.endDate || item.end_date), department: textValue(item.department), industry: textValue(item.industry), description: textValue(item.description || item.content || fallback), achievement: textValue(item.achievement) }) }
function mapProject(item, fallback) { return newProject({ name: textValue(item.name || item.projectName), role: textValue(item.role), startDate: normalizeMonth(item.startDate || item.start_date), endDate: normalizeMonth(item.endDate || item.end_date), background: textValue(item.background), techStack: arrayText(item.techStack || item.tech_stack), responsibility: textValue(item.responsibility || item.description || fallback), achievement: textValue(item.achievement) }) }
function mapEducation(item, fallback) {
  const startDate = normalizeMonth(item.startDate || item.start_date)
  const endDate = normalizeMonth(item.endDate || item.end_date)
  return newEducation({ school: textValue(item.school || item.schoolName), major: textValue(item.major), degree: normalizeDegree(item.degree), fullTime: normalizeFullTime(item.fullTime || item.full_time || item.educationType || item.education_type), status: normalizeEducationStatus(item.status || item.educationStatus || item.education_status), startDate, endDate, period: textValue(item.period || monthRange(startDate, endDate)), description: textValue(item.description || fallback) })
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
function displayMonth(value) { return textValue(value).replace('-', '.') }
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
function monthRange(start, end) { return [displayMonth(start), displayMonth(end)].filter(Boolean).join('-') }
function listValue(value) { return String(value || '').split(/[,，、\n\r\t ]+/).map(item => item.trim()).filter(Boolean) }
function filledRows(rows) { return rows.filter(row => Object.entries(row).some(([key, value]) => key !== 'id' && String(value || '').trim())) }
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
  const work = filledRows(form.workExperiences).map(item => ({ ...item }))
  const projects = filledRows(form.projectExperiences)
  const education = filledRows(form.educationExperiences).map(item => ({ ...item, period: item.period || monthRange(item.startDate, item.endDate) }))
  const expectation = expectationPayload()
  return {
    name: form.basic.name.trim(),
    summary: form.summary.trim(),
    current_title: form.basic.currentTitle.trim(),
    years_experience: form.basic.workYears.trim(),
    expected_titles: listValue(form.expectation.position),
    skills: listValue(form.skills),
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
    source: { type: 'job_profile', provider, synced_at: new Date().toISOString(), raw: profile.value?.parsed?.source?.raw || {} },
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
      saveHint.value = saveAfterApply ? 'AI 已生成画像摘要，正在保存' : 'AI 已生成画像摘要，请确认后保存。'
      if (saveAfterApply) {
        await resume.saveProfile(buildParsed('AI 自动生成摘要'))
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
async function syncBoss() {
  if (syncing.value) return
  syncing.value = true; error.value = ''; saveHint.value = '正在从 Boss 直聘拉取在线简历'
  try {
    await resume.syncBossOnline()
    saveHint.value = '已从 Boss 直聘同步求职画像，可继续编辑后保存。'
  } catch (err) {
    error.value = err?.message || '从 Boss 直聘拉取求职画像失败'
    showWarning(error.value)
  } finally {
    syncing.value = false
  }
}
async function saveProfile() {
  saving.value = true; error.value = ''; saveHint.value = ''
  const summaryWasEmpty = !form.summary.trim()
  try {
    await resume.saveProfile(buildParsed('手动填写'))
    saveHint.value = summaryWasEmpty ? '求职画像已保存，正在生成画像摘要' : '求职画像已保存。'
    if (summaryWasEmpty) void requestAiSummary({ autoApply: true, showCompare: false, saveAfterApply: true })
  } catch (err) {
    error.value = err.message
    showWarning(error.value || '求职画像保存失败')
  } finally {
    saving.value = false
  }
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
  let i = 0, j = 0
  while (i < oldTokens.length && j < newTokens.length) {
    if (oldTokens[i] === newTokens[j]) {
      pushDiffPart(parts, 'same', oldTokens[i]); i++; j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      pushDiffPart(parts, 'removed', oldTokens[i]); i++
    } else {
      pushDiffPart(parts, 'added', newTokens[j]); j++
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
  summaryCompare.visible = false
  saveHint.value = '已使用 AI 版本，请点击保存画像后生效。'
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
function clearForm() {
  Object.assign(form, createEmptyForm())
  saveHint.value = '已清空，点击保存后生效。'
  error.value = ''
}
function addWork() { form.workExperiences.push(newWork()) }
function removeWork(index) { form.workExperiences.splice(index, 1); if (!form.workExperiences.length) addWork() }
function addProject() { form.projectExperiences.push(newProject()) }
function removeProject(index) { form.projectExperiences.splice(index, 1); if (!form.projectExperiences.length) addProject() }
function addEducation() { form.educationExperiences.push(newEducation()) }
function removeEducation(index) { form.educationExperiences.splice(index, 1); if (!form.educationExperiences.length) addEducation() }
</script>
