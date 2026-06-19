<template>
  <section class="system-page project-deep-page">
    <header class="page-header">
      <div><p class="eyebrow">Project Deep Dive</p><h1>项目深挖</h1><p>创建项目、上传项目材料，自动生成面试中常见的项目深挖问题和参考答案。</p></div>
      <div class="history-header-actions"><button class="secondary-btn" :disabled="loading" @click="loadProjects">刷新</button><button class="primary-btn" @click="openCreate">新增项目</button></div>
    </header>
    <p v-if="error" class="error settings-error">{{ error }}</p>

    <div class="project-deep-layout">
      <aside class="glass-card project-list-card">
        <div class="card-title"><h2>项目列表</h2><span>{{ projects.length }} 个</span></div>
        <label class="history-search"><span>搜索项目</span><input v-model.trim="keyword" placeholder="搜索项目名、角色、技术栈" /></label>
        <div class="project-list-scroll">
          <article v-for="item in filteredProjects" :key="item.projectId" :class="['project-list-item', { active: selectedId === item.projectId }]" @click="selectProject(item.projectId)">
            <strong>{{ item.name }}</strong><p>{{ item.summary || '暂无项目摘要' }}</p><div><span>{{ item.role || '核心开发' }}</span><span>{{ item.materials?.length || 0 }} 份材料</span><span>{{ item.questions?.length || 0 }} 问</span></div>
          </article>
          <div v-if="!filteredProjects.length" class="empty-state compact"><strong>暂无项目</strong><p>先创建一个项目，再上传项目材料。</p></div>
        </div>
      </aside>

      <main class="glass-card project-detail-card">
        <template v-if="selectedProject">
          <div v-if="projectStage === 'info'" class="project-info-stage">
            <div class="project-detail-head"><div><p class="eyebrow">Project Info</p><h2>{{ selectedProject.name }}</h2><p>{{ selectedProject.summary || '可以补充项目背景、目标、职责和结果。' }}</p><div class="question-tags"><span>{{ selectedProject.role || '核心开发' }}</span><span v-if="selectedProject.techStack">{{ selectedProject.techStack }}</span></div></div><button class="danger-text" @click="removeProject(selectedProject.projectId)">删除项目</button></div>
            <div class="project-info-grid">
              <section><span>项目角色</span><strong>{{ selectedProject.role || '核心开发' }}</strong></section>
              <section><span>技术栈</span><strong>{{ selectedProject.techStack || '未填写' }}</strong></section>
              <section><span>项目材料</span><strong>{{ selectedProject.materials?.length || 0 }} 份</strong></section>
              <section><span>深挖问答</span><strong>{{ selectedProject.questions?.length || 0 }} 条</strong></section>
            </div>
            <div class="project-info-summary"><h3>项目摘要</h3><p>{{ selectedProject.summary || '暂无项目摘要，请补充项目背景、目标、职责、技术难点和结果指标。' }}</p></div>
            <div class="modal-actions"><button class="primary-btn" @click="projectStage = 'deep'">进入项目深挖</button><button class="secondary-btn" @click="openCreate">新增项目</button></div>
          </div>

          <div v-else class="project-deep-stage">
            <div class="project-detail-head"><div><p class="eyebrow">Deep Dive</p><h2>{{ selectedProject.name }} · 项目深挖</h2><p>上传项目材料，生成面试官常问的项目深挖问题和参考答案。</p></div><button class="secondary-btn" @click="projectStage = 'info'">返回项目信息</button></div>
            <section class="project-material-section">
              <div class="card-title"><h2>项目材料</h2><span>{{ selectedProject.materials?.length || 0 }} 份</span></div>
              <div class="material-uploader">
                <label class="doc-upload-box"><input type="file" accept=".txt,.md,.markdown,.json,.csv" @change="uploadMaterialFile" /><b>上传材料</b><small>支持 TXT / MD / JSON / CSV，上传后自动用于生成问题</small></label>
                <textarea v-model="materialText" placeholder="也可以直接粘贴项目介绍、项目职责、架构说明、技术难点、结果指标等材料" />
                <button class="secondary-btn" :disabled="!materialText.trim() || saving" @click="savePastedMaterial">保存粘贴材料</button>
              </div>
              <div class="material-list"><article v-for="m in selectedProject.materials || []" :key="m.materialId"><strong>{{ m.fileName }}</strong><p>{{ m.preview }}</p></article></div>
            </section>

            <section class="project-question-section">
              <div class="card-title"><div><h2>深挖问题与答案</h2><p class="section-hint">左侧选择问题，右侧查看完整参考答案、追问方向和材料依据。</p></div><span>{{ selectedProject.questions?.length || 0 }} 条</span></div>
              <div class="generate-bar"><label><span>生成数量</span><input v-model.number="generateForm.count" type="number" min="4" max="40" /></label><label><span>关注方向</span><input v-model="generateForm.focus" placeholder="架构设计、技术难点、性能优化、项目复盘" /></label><button class="primary-btn" :disabled="generating" @click="generateQuestions">{{ generating ? '生成中' : '生成深挖问题' }}</button></div>
              <div v-if="selectedProject.questions?.length" class="deep-question-workbench">
                <div class="deep-question-list" role="listbox" aria-label="深挖问题列表">
                  <button
                    v-for="(item, index) in selectedProject.questions || []"
                    :key="item.questionId || index"
                    type="button"
                    :class="['deep-question', { active: selectedQuestion === item }]"
                    @click="selectQuestion(item)"
                  >
                    <div class="question-card-head"><span class="question-index">Q{{ index + 1 }}</span><b>{{ item.category || '综合追问' }}</b><em>{{ item.difficulty || '常规' }}</em></div>
                    <h3>{{ item.question }}</h3>
                    <p>{{ item.answer || '暂无参考答案' }}</p>
                    <strong>查看完整答案</strong>
                  </button>
                </div>
                <article class="deep-question-detail" v-if="selectedQuestion">
                  <div class="question-detail-top">
                    <div><p class="eyebrow">Selected Question</p><h3>{{ selectedQuestion.question }}</h3></div>
                    <div class="question-tags"><span>{{ selectedQuestion.category || '综合追问' }}</span><span>{{ selectedQuestion.difficulty || '常规' }}</span></div>
                  </div>
                  <section><h4>参考答案</h4><p>{{ selectedQuestion.answer || '暂无参考答案。建议补充项目背景、个人职责、方案取舍、结果指标和复盘。' }}</p></section>
                  <section v-if="selectedQuestion.followUp || selectedQuestion.followUps?.length"><h4>可能追问</h4><p>{{ formatListText(selectedQuestion.followUp || selectedQuestion.followUps) }}</p></section>
                  <section v-if="selectedQuestion.evidence || selectedQuestion.materialEvidence"><h4>材料依据</h4><p>{{ selectedQuestion.evidence || selectedQuestion.materialEvidence }}</p></section>
                </article>
              </div>
              <div v-else class="empty-state compact"><strong>暂无深挖问题</strong><p>上传材料后点击“生成深挖问题”。</p></div>
            </section>
          </div>
        </template>
        <div v-else class="empty-state"><strong>请选择项目</strong><p>左侧选择或新建项目后，先查看项目信息，再进入项目深挖。</p></div>
      </main>
    </div>

    <div v-if="showModal" class="modal-mask"><div class="modal-card project-modal-card"><button class="close" @click="showModal = false">×</button><h2>新增项目</h2><p>建议填写项目背景、职责、技术栈，后续生成问题会作为上下文使用。</p><div class="form-grid compact-form modal-form-grid"><label class="wide"><span>项目名称</span><input v-model="form.name" placeholder="例如：企业知识库检索助手" /></label><label><span>项目角色</span><input v-model="form.role" placeholder="核心开发 / 后端开发" /></label><label><span>技术栈</span><input v-model="form.techStack" placeholder="Java, Spring Boot, Redis, Elasticsearch" /></label><label class="wide"><span>项目摘要</span><textarea v-model="form.summary" placeholder="项目背景、目标、你负责的模块、结果指标" /></label></div><p v-if="modalError" class="error settings-error">{{ modalError }}</p><div class="modal-actions"><button class="primary-btn" :disabled="saving" @click="saveProject">{{ saving ? '保存中' : '保存项目' }}</button><button class="secondary-btn" @click="showModal = false">取消</button></div></div></div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { addProjectMaterial, createDeepDiveProject, deleteDeepDiveProject, generateProjectQuestions, listDeepDiveProjects } from '../api/projectDeepDive'

const loading = ref(false), saving = ref(false), generating = ref(false)
const error = ref(''), modalError = ref('')
const projects = ref([]), selectedId = ref(''), keyword = ref(''), showModal = ref(false), materialText = ref(''), projectStage = ref('info'), selectedQuestionId = ref('')
const form = reactive({ name: '', role: '核心开发', techStack: '', summary: '' })
const generateForm = reactive({ count: 12, focus: '架构设计、技术难点、性能优化、项目复盘' })
const filteredProjects = computed(() => { const q = keyword.value.toLowerCase(); return q ? projects.value.filter(p => [p.name,p.role,p.summary,p.techStack].filter(Boolean).join(' ').toLowerCase().includes(q)) : projects.value })
const selectedProject = computed(() => projects.value.find(p => p.projectId === selectedId.value) || projects.value[0] || null)
const selectedQuestion = computed(() => {
  const questions = selectedProject.value?.questions || []
  if (!questions.length) return null
  return questions.find(q => q.questionId === selectedQuestionId.value) || questions[0]
})

watch(selectedProject, project => {
  selectedQuestionId.value = project?.questions?.[0]?.questionId || ''
})

onMounted(loadProjects)
async function loadProjects() { loading.value = true; try { projects.value = await listDeepDiveProjects(); if (!selectedId.value && projects.value[0]) selectedId.value = projects.value[0].projectId } catch (e) { error.value = e.message || '项目加载失败' } finally { loading.value = false } }
function selectProject(id) { selectedId.value = id; materialText.value = ''; projectStage.value = 'info'; selectedQuestionId.value = '' }
function openCreate() { Object.assign(form, { name: '', role: '核心开发', techStack: '', summary: '' }); modalError.value = ''; showModal.value = true }
async function saveProject() { saving.value = true; try { const saved = await createDeepDiveProject(form); showModal.value = false; await loadProjects(); selectedId.value = saved.projectId } catch (e) { modalError.value = e.message || '保存失败' } finally { saving.value = false } }
async function removeProject(id) { if (!window.confirm('确认删除该项目？材料和问题会一并删除。')) return; await deleteDeepDiveProject(id); selectedId.value = ''; await loadProjects() }
async function uploadMaterialFile(event) { const file = event.target.files?.[0]; event.target.value = ''; if (!file || !selectedProject.value) return; const text = await file.text(); await saveMaterial(file.name, file.type || 'text/plain', text) }
async function savePastedMaterial() { if (!selectedProject.value || !materialText.value.trim()) return; await saveMaterial('粘贴材料.txt', 'text/plain', materialText.value) }
async function saveMaterial(fileName, contentType, content) { saving.value = true; try { const updated = await addProjectMaterial(selectedProject.value.projectId, { fileName, contentType, content }); replaceProject(updated); materialText.value = '' } catch (e) { error.value = e.message || '材料保存失败' } finally { saving.value = false } }
async function generateQuestions() { if (!selectedProject.value) return; generating.value = true; try { const updated = await generateProjectQuestions(selectedProject.value.projectId, generateForm); replaceProject(updated); selectedQuestionId.value = updated.questions?.[0]?.questionId || '' } catch (e) { error.value = e.message || '生成失败' } finally { generating.value = false } }
function replaceProject(updated) { const idx = projects.value.findIndex(p => p.projectId === updated.projectId); if (idx >= 0) projects.value.splice(idx, 1, updated); else projects.value.unshift(updated); selectedId.value = updated.projectId }
function selectQuestion(item) { selectedQuestionId.value = item?.questionId || '' }
function formatListText(value) { return Array.isArray(value) ? value.filter(Boolean).join('\n') : String(value || '') }
</script>
