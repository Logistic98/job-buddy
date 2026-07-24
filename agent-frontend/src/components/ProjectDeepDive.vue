<template>
  <section class="system-page project-deep-page project-deep-v2">
    <ProjectLibraryPanel
      v-if="!selectedId"
      :error="error"
      :loading="loading"
      :projects="projects"
      :library-stats="libraryStats"
      :open-create="openCreate"
      :load-projects="loadProjects"
      :readiness="readiness"
      :tech-labels="techLabels"
      :material-count="materialCount"
      :question-count="questionCount"
      :format-updated-at="formatUpdatedAt"
      :open-project="openProject"
      :open-delete-dialog="openDeleteDialog"
    />

    <template v-else>
      <header class="project-workbench-header">
        <div class="project-workbench-breadcrumb">
          <button type="button" @click="backToLibrary">项目列表</button><span>/</span
          ><strong>{{ activeProjectName }}</strong>
        </div>
        <div class="project-workbench-title">
          <div>
            <p class="eyebrow">Project Workspace</p>
            <h1>{{ activeProjectName }}</h1>
          </div>
        </div>
        <nav class="project-workbench-steps" aria-label="项目深挖步骤">
          <button
            v-for="step in projectSteps"
            :key="step.key"
            :class="{ active: projectStage === step.key, done: step.done }"
            @click="projectStage = step.key"
          >
            <b>{{ step.index }}</b
            ><span
              ><strong>{{ step.label }}</strong
              ><small>{{ step.description }}</small></span
            >
          </button>
        </nav>
      </header>

      <main class="glass-card project-workbench-body">
        <div v-if="detailLoading" class="project-detail-loading" aria-live="polite">
          <span></span><span></span><span></span>
          <p>正在加载项目详情</p>
        </div>
        <div v-else-if="detailError" class="empty-state project-detail-error" role="alert">
          <strong>项目详情加载失败</strong>
          <p>{{ detailError }}</p>
          <div>
            <button class="secondary-btn" @click="backToLibrary">返回项目列表</button
            ><button class="primary-btn" @click="loadProjectDetail(selectedId, true)">重新加载</button>
          </div>
        </div>
        <template v-else-if="selectedProject">
          <section v-if="projectStage === 'info'" class="project-info-stage project-stage-panel">
            <div class="project-overview-layout">
              <div class="project-overview-main">
                <section :class="['project-overview-summary', { empty: !selectedProject.summary }]">
                  <div class="project-section-title">
                    <span>项目摘要</span
                    ><button type="button" :disabled="saving" @click="openEditProject('basic')">编辑</button>
                  </div>
                  <p>{{ selectedProject.summary || '待补充项目定位、核心目标和整体价值。' }}</p>
                </section>
                <article :class="['project-context-row', { empty: !selectedProject.background }]">
                  <span><b>项目背景</b><em>目标与业务上下文</em></span>
                  <strong>{{ selectedProject.background || '补充业务背景、用户痛点与项目目标。' }}</strong>
                  <button
                    type="button"
                    class="project-context-edit"
                    :disabled="saving"
                    @click="openEditProject('details')"
                  >
                    编辑
                  </button>
                </article>
                <section class="project-experience-panel">
                  <div class="project-section-title project-experience-title">
                    <div>
                      <span>核心经历</span><small>职责、技术、难点与成果 · {{ coreOverviewCompletion }}/4</small>
                    </div>
                    <button
                      type="button"
                      class="project-summary-edit"
                      :disabled="saving"
                      @click="openEditProject('details')"
                    >
                      编辑
                    </button>
                  </div>
                  <div class="project-overview-focus" aria-label="项目核心经历">
                    <article
                      :class="['project-focus-card', 'responsibility', { empty: !selectedProject.responsibilities }]"
                    >
                      <span class="project-focus-card-head"
                        ><b>个人职责</b><em v-if="!selectedProject.responsibilities">待补充</em></span
                      >
                      <span class="project-focus-card-copy">{{
                        selectedProject.responsibilities || '补充负责范围、核心模块与协作边界。'
                      }}</span>
                    </article>
                    <article :class="['project-focus-card', 'technology', { empty: !selectedProject.highlights }]">
                      <span class="project-focus-card-head"
                        ><b>关键技术</b><em v-if="!selectedProject.highlights">待补充</em></span
                      >
                      <span class="project-focus-card-copy">{{
                        selectedProject.highlights || '补充关键设计、技术选型与创新方案。'
                      }}</span>
                    </article>
                    <article :class="['project-focus-card', 'challenge', { empty: !selectedProject.challenges }]">
                      <span class="project-focus-card-head"
                        ><b>项目难点</b><em v-if="!selectedProject.challenges">待补充</em></span
                      >
                      <span class="project-focus-card-copy">{{
                        selectedProject.challenges || '补充核心难题、方案取舍与落地过程。'
                      }}</span>
                    </article>
                    <article :class="['project-focus-card', 'outcome', { empty: !selectedProject.outcomes }]">
                      <span class="project-focus-card-head"
                        ><b>项目成果</b><em v-if="!selectedProject.outcomes">待补充</em></span
                      >
                      <span class="project-focus-card-copy">{{
                        selectedProject.outcomes || '补充效率、稳定性、成本或业务价值等量化结果。'
                      }}</span>
                    </article>
                  </div>
                </section>
              </div>
              <aside class="project-facts-panel">
                <div class="project-section-title">
                  <span>基础信息</span
                  ><button type="button" :disabled="saving" @click="openEditProject('basic')">编辑</button>
                </div>
                <dl class="project-facts-list">
                  <div>
                    <dt>项目角色</dt>
                    <dd :class="{ missing: !selectedProject.role }">{{ selectedProject.role || '待补充' }}</dd>
                  </div>
                  <div>
                    <dt>项目类型</dt>
                    <dd :class="{ missing: !selectedProject.projectType }">
                      {{ selectedProject.projectType || '待补充' }}
                    </dd>
                  </div>
                  <div>
                    <dt>业务领域</dt>
                    <dd :class="{ missing: !selectedProject.businessDomain }">
                      {{ selectedProject.businessDomain || '待补充' }}
                    </dd>
                  </div>
                  <div>
                    <dt>项目周期</dt>
                    <dd :class="{ missing: !selectedProject.projectPeriod }">
                      {{ selectedProject.projectPeriod || '待补充' }}
                    </dd>
                  </div>
                  <div>
                    <dt>项目状态</dt>
                    <dd :class="{ missing: !selectedProject.projectStatus }">
                      {{ selectedProject.projectStatus || '待补充' }}
                    </dd>
                  </div>
                  <div>
                    <dt>团队规模</dt>
                    <dd :class="{ missing: !selectedProject.teamSize }">{{ selectedProject.teamSize || '待补充' }}</dd>
                  </div>
                </dl>
                <div class="project-facts-tech">
                  <span>技术栈</span>
                  <div v-if="techLabels(selectedProject).length" class="project-overview-tags">
                    <b v-for="tech in techLabels(selectedProject)" :key="tech">{{ tech }}</b>
                  </div>
                  <p v-else class="project-overview-empty">待补充核心技术栈</p>
                </div>
                <div class="project-facts-metrics" aria-label="项目资源统计">
                  <button type="button" aria-label="查看项目材料" @click="projectStage = 'materials'">
                    <strong>{{ materialCount(selectedProject) }}</strong
                    ><span>份项目材料</span><i aria-hidden="true">查看</i>
                  </button>
                  <button type="button" aria-label="查看深挖问题" @click="projectStage = 'questions'">
                    <strong>{{ questionCount(selectedProject) }}</strong
                    ><span>道深挖问题</span><i aria-hidden="true">查看</i>
                  </button>
                </div>
              </aside>
            </div>
          </section>

          <section
            v-else-if="projectStage === 'materials'"
            :class="[
              'project-material-section',
              'project-stage-panel',
              { 'is-empty': !selectedProject.materials?.length },
            ]"
          >
            <div class="material-file-manager">
              <label class="material-method-card material-upload-card">
                <input
                  type="file"
                  class="material-file-input"
                  multiple
                  :disabled="saving"
                  @change="uploadMaterialFiles"
                />
                <b>批量上传项目文件</b>
                <small>不限制文件格式；单个文件最大 1GB</small>
                <span class="material-upload-cta">{{ saving ? '上传中…' : '选择多个文件' }}</span>
              </label>
              <p v-if="selectedProject.materials?.length" class="material-upload-note">
                所选文件将逐个上传，单个文件失败不会影响其他文件。
              </p>
            </div>
            <div v-if="materialUploadStatus" class="inline-feedback success" aria-live="polite">
              {{ materialUploadStatus }}
            </div>
            <div v-if="materialError" class="inline-feedback error" role="alert">{{ materialError }}</div>
            <section
              v-if="selectedProject.materials?.length"
              class="project-material-library"
              aria-labelledby="material-library-title"
            >
              <div class="material-library-heading">
                <div>
                  <h3 id="material-library-title">项目文件</h3>
                  <p>集中下载和管理当前项目的相关文件。</p>
                </div>
                <div class="material-batch-actions">
                  <label
                    ><input type="checkbox" :checked="allMaterialsSelected" @change="toggleAllMaterials" />全选</label
                  >
                  <a
                    v-if="selectedMaterialIds.length"
                    class="primary-btn"
                    :href="projectMaterialBatchDownloadUrl(selectedMaterialIds)"
                    >批量下载（{{ selectedMaterialIds.length }}）</a
                  >
                  <button v-else type="button" class="primary-btn" disabled>批量下载</button>
                  <span>{{ selectedProject.materials.length }} 个</span>
                </div>
              </div>
              <div class="material-list material-card-grid">
                <article
                  v-for="m in selectedProject.materials"
                  :key="m.materialId"
                  :class="['material-card', { selected: selectedMaterialIds.includes(m.materialId) }]"
                >
                  <div class="material-card-head">
                    <label class="material-card-select" :aria-label="`选择文件 ${m.fileName}`"
                      ><input v-model="selectedMaterialIds" type="checkbox" :value="m.materialId"
                    /></label>
                    <span :class="['material-file-mark', materialTypeInfo(m).tone]" aria-hidden="true">{{
                      materialTypeInfo(m).label
                    }}</span>
                    <div class="material-card-identity">
                      <strong class="material-card-name" :title="m.fileName">{{ m.fileName }}</strong>
                      <div class="material-card-meta">
                        <small>{{ formatFileSize(m.sizeBytes) }}</small>
                        <small>{{ formatUpdatedAt(m.createdAt, '上传时间未知') }}</small>
                      </div>
                    </div>
                    <button
                      class="material-card-delete"
                      type="button"
                      :disabled="saving"
                      :aria-label="`删除文件 ${m.fileName}`"
                      @click="openMaterialDeleteDialog(m)"
                    >
                      删除
                    </button>
                  </div>
                  <div class="material-card-actions">
                    <a class="primary-btn" :href="projectMaterialFileUrl(m.materialId)">下载</a>
                  </div>
                </article>
              </div>
            </section>
          </section>

          <section v-else class="project-question-section project-stage-panel">
            <div v-if="questionActionError" class="inline-feedback error" role="alert">{{ questionActionError }}</div>
            <template v-if="selectedProject.questions?.length">
              <div class="project-question-tools">
                <label class="history-search"
                  ><span>筛选问题</span><input v-model.trim="questionKeyword" placeholder="搜索题目、分类或难度"
                /></label>
                <div class="project-question-tool-actions">
                  <span
                    >共 {{ filteredProjectQuestions.length }} 道，第 {{ questionPage }} / {{ questionPages }} 页</span
                  ><button class="question-add-btn" :disabled="saving || generating" @click="openQuestionModal()">
                    添加问题
                  </button>
                </div>
              </div>
              <div v-if="filteredProjectQuestions.length" class="deep-question-workbench">
                <div class="deep-question-list" role="listbox" aria-label="深挖问题列表">
                  <button
                    v-for="(item, index) in pagedProjectQuestions"
                    :key="item.questionId || index"
                    type="button"
                    role="option"
                    :aria-selected="selectedQuestion === item"
                    :class="['deep-question', { active: selectedQuestion === item }]"
                    @click="selectQuestion(item)"
                  >
                    <div class="question-card-head">
                      <span class="question-index">Q{{ (questionPage - 1) * questionPageSize + index + 1 }}</span
                      ><b>{{ item.category || '综合追问' }}</b
                      ><em>{{ item.difficulty || '常规' }}</em>
                    </div>
                    <h3>{{ item.question }}</h3>
                  </button>
                  <div class="project-question-pagination">
                    <button class="secondary-btn" :disabled="questionPage <= 1" @click="questionPage--">上一页</button
                    ><button class="secondary-btn" :disabled="questionPage >= questionPages" @click="questionPage++">
                      下一页
                    </button>
                  </div>
                </div>
                <article v-if="selectedQuestion" class="deep-question-detail">
                  <div class="question-detail-top">
                    <div class="question-detail-headline">
                      <p class="eyebrow">当前问题</p>
                      <h3>{{ selectedQuestion.question }}</h3>
                      <div class="question-tags">
                        <span>{{ selectedQuestion.category || '综合追问' }}</span
                        ><span>{{ selectedQuestion.difficulty || '常规' }}</span
                        ><span v-if="isManualQuestion(selectedQuestion)" class="manual-tag">手动维护</span>
                      </div>
                    </div>
                    <div class="question-detail-actions">
                      <button
                        class="secondary-btn"
                        type="button"
                        :disabled="saving"
                        @click="openQuestionModal(selectedQuestion)"
                      >
                        编辑
                      </button>
                      <button
                        class="danger-text"
                        type="button"
                        :disabled="saving"
                        @click="openQuestionDeleteDialog(selectedQuestion)"
                      >
                        删除
                      </button>
                    </div>
                  </div>
                  <section class="question-detail-block">
                    <h4>参考答案</h4>
                    <PracticeMarkdown
                      v-if="answerMarkdown"
                      :key="`answer-${selectedQuestion.questionId || questionPosition}-${answerMarkdown.length}`"
                      class="deep-markdown"
                      custom-id="project-deep-answer"
                      :content="answerMarkdown"
                    />
                    <p v-else class="question-detail-empty">
                      暂无参考答案。建议补充项目背景、个人职责、方案取舍、结果指标和复盘。
                    </p>
                  </section>
                  <section v-if="followUpMarkdown" class="question-detail-block">
                    <h4>可能追问</h4>
                    <PracticeMarkdown
                      :key="`followup-${selectedQuestion.questionId || questionPosition}-${followUpMarkdown.length}`"
                      class="deep-markdown"
                      custom-id="project-deep-followup"
                      :content="followUpMarkdown"
                    />
                  </section>
                  <section v-if="evidenceMarkdown" class="question-detail-block">
                    <h4>材料依据</h4>
                    <PracticeMarkdown
                      :key="`evidence-${selectedQuestion.questionId || questionPosition}-${evidenceMarkdown.length}`"
                      class="deep-markdown"
                      custom-id="project-deep-evidence"
                      :content="evidenceMarkdown"
                    />
                  </section>
                </article>
              </div>
              <div v-else class="empty-state compact">
                <strong>没有匹配的问题</strong>
                <p>请更换关键词或清空筛选条件。</p>
                <button class="secondary-btn" @click="questionKeyword = ''">清空筛选</button>
              </div>
            </template>
            <div v-else class="empty-state compact project-question-empty">
              <strong>还没有问题</strong>
              <p>
                {{
                  canGenerate
                    ? '可以根据项目材料智能生成，也可以手动录入问题。'
                    : '手动录入问题，或先添加项目材料后智能生成。'
                }}
              </p>
              <div class="empty-state-actions">
                <button v-if="!canGenerate" class="secondary-btn" type="button" @click="projectStage = 'materials'">
                  添加材料
                </button>
                <button class="question-add-btn" type="button" @click="openQuestionModal()">添加问题</button>
              </div>
            </div>
          </section>
        </template>
      </main>
    </template>

    <div v-if="showModal" class="modal-mask" @click.self="closeCreate">
      <div class="modal-card project-modal-card project-editor-card">
        <header class="project-editor-head">
          <div>
            <p class="eyebrow">{{ projectModalMode === 'edit' ? 'Edit Project' : 'New Project' }}</p>
            <h2>{{ projectModalMode === 'edit' ? '编辑项目信息' : '新增项目' }}</h2>
            <p>
              {{
                projectModalMode === 'edit'
                  ? '按信息类型分步维护，保存后立即更新项目概览。'
                  : '先填写基础信息，项目创建后可继续完善经历详情。'
              }}
            </p>
          </div>
          <button class="close" aria-label="关闭" @click="closeCreate">×</button>
        </header>
        <nav class="project-editor-tabs" aria-label="项目信息分类">
          <button
            type="button"
            :class="{ active: projectEditorSection === 'basic' }"
            @click="projectEditorSection = 'basic'"
          >
            <b>基础信息</b><span>名称、类型、领域、状态与技术栈</span>
          </button>
          <button
            type="button"
            :class="{ active: projectEditorSection === 'details' }"
            @click="projectEditorSection = 'details'"
          >
            <b>经历详情</b><span>背景、职责、技术方案与成果</span>
          </button>
        </nav>
        <div class="project-editor-body">
          <div
            v-if="projectEditorSection === 'basic'"
            class="form-grid compact-form modal-form-grid project-basic-form"
          >
            <label class="wide"
              ><span class="form-required">项目名称</span
              ><input
                ref="projectNameInput"
                aria-required="true"
                v-model.trim="form.name"
                maxlength="80"
                placeholder="例如：企业知识库检索助手"
            /></label>
            <label
              ><span>项目角色</span
              ><input v-model.trim="form.role" maxlength="40" placeholder="Agent 应用开发 / LLM 工程"
            /></label>
            <label
              ><span>项目周期</span
              ><input v-model.trim="form.projectPeriod" maxlength="128" placeholder="2024.03 - 2025.06"
            /></label>
            <label
              ><span>团队规模</span><input v-model.trim="form.teamSize" maxlength="64" placeholder="8 人 / 跨 3 个团队"
            /></label>
            <label
              ><span>项目类型</span
              ><input v-model.trim="form.projectType" maxlength="128" placeholder="内部研发平台 / 商业交付项目"
            /></label>
            <label
              ><span>业务领域</span
              ><input v-model.trim="form.businessDomain" maxlength="128" placeholder="AI 基础设施 / 金融风控"
            /></label>
            <label
              ><span>项目状态</span
              ><input v-model.trim="form.projectStatus" maxlength="64" placeholder="持续迭代 / 已交付 / 已归档"
            /></label>
            <div class="wide project-tech-editor">
              <span class="project-tech-field-label">技术栈</span>
              <div v-if="formTechLabels.length" class="project-tech-tag-list" aria-label="已添加的技术栈">
                <span v-for="tech in formTechLabels" :key="tech">
                  {{ tech }}
                  <button type="button" :aria-label="`移除技术栈 ${tech}`" @click="removeFormTech(tech)">×</button>
                </span>
              </div>
              <div class="project-tech-input-row">
                <input
                  v-model.trim="techDraft"
                  maxlength="64"
                  placeholder="输入一项技术，例如 LangGraph"
                  @keydown.enter.prevent="addFormTech"
                />
                <button type="button" :disabled="!techDraft.trim()" @click="addFormTech">添加标签</button>
              </div>
              <small v-if="techStackError" class="field-hint project-tech-error" role="alert">{{
                techStackError
              }}</small>
              <small v-else class="field-hint">每次输入一项技术，按回车或点击按钮添加</small>
            </div>
            <label class="wide"
              ><span>项目摘要</span
              ><textarea
                v-model.trim="form.summary"
                maxlength="1000"
                placeholder="概括项目定位、核心目标和整体价值"
              /><small class="field-hint">{{ form.summary.length }} / 1000</small></label
            >
          </div>
          <div v-else class="form-grid compact-form modal-form-grid project-detail-form">
            <label class="wide"
              ><span>项目背景</span
              ><textarea
                v-model.trim="form.background"
                maxlength="2000"
                placeholder="说明业务背景、用户痛点与项目目标"
              /><small class="field-hint">{{ form.background.length }} / 2000</small></label
            >
            <label
              ><span>个人职责</span
              ><textarea
                v-model.trim="form.responsibilities"
                maxlength="2000"
                placeholder="说明负责范围、核心模块与协作边界"
              /><small class="field-hint">{{ form.responsibilities.length }} / 2000</small></label
            >
            <label
              ><span>技术亮点</span
              ><textarea
                v-model.trim="form.highlights"
                maxlength="2000"
                placeholder="说明关键设计、技术选型和创新点"
              /><small class="field-hint">{{ form.highlights.length }} / 2000</small></label
            >
            <label
              ><span>难点与解决方案</span
              ><textarea
                v-model.trim="form.challenges"
                maxlength="2000"
                placeholder="说明问题现象、方案取舍与落地过程"
              /><small class="field-hint">{{ form.challenges.length }} / 2000</small></label
            >
            <label
              ><span>项目成果</span
              ><textarea
                v-model.trim="form.outcomes"
                maxlength="2000"
                placeholder="说明效率、稳定性、成本或业务价值等量化结果"
              /><small class="field-hint">{{ form.outcomes.length }} / 2000</small></label
            >
          </div>
          <p v-if="modalError" class="error settings-error form-error-alert" role="alert" aria-live="assertive">
            {{ modalError }}
          </p>
        </div>
        <div class="modal-actions project-editor-actions">
          <button class="primary-btn" :disabled="saving" @click="saveProject">
            {{ saving ? '保存中' : projectModalMode === 'edit' ? '保存修改' : '创建' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="questionModal.visible" class="modal-mask" @click.self="closeQuestionModal">
      <div class="modal-card project-modal-card question-editor-card">
        <button class="close" aria-label="关闭" @click="closeQuestionModal">×</button>
        <p class="eyebrow">{{ questionModal.mode === 'edit' ? 'Edit Question' : 'Add Question' }}</p>
        <h2>{{ questionModal.mode === 'edit' ? '编辑问题' : '添加问题' }}</h2>
        <p>
          {{
            questionModal.mode === 'edit'
              ? '修改问题内容与参考答案，保存后立即生效。'
              : '选择智能生成或手动录入，所有新增问题都从这里开始。'
          }}
        </p>

        <div
          v-if="questionModal.mode === 'create'"
          class="question-create-methods"
          role="tablist"
          aria-label="添加问题方式"
        >
          <button
            type="button"
            :class="{ active: questionModal.entryType === 'generate' }"
            :disabled="!canGenerate"
            @click="setQuestionEntryType('generate')"
          >
            <b>智能生成</b><span>根据项目材料批量生成问题和参考答案</span
            ><small v-if="!canGenerate">需要先添加项目材料</small>
          </button>
          <button
            type="button"
            :class="{ active: questionModal.entryType === 'manual' }"
            @click="setQuestionEntryType('manual')"
          >
            <b>手动录入</b><span>自行填写一道问题、分类和参考答案</span>
          </button>
        </div>

        <div
          v-if="questionModal.mode === 'create' && questionModal.entryType === 'generate'"
          class="question-generate-form"
        >
          <label
            ><span class="form-required">生成数量</span
            ><input
              v-model.number="generateForm.count"
              aria-required="true"
              type="number"
              min="4"
              max="40"
              step="1"
              placeholder="请输入 4-40 的整数"
          /></label>
          <label
            ><span>关注方向</span
            ><input v-model.trim="generateForm.focus" placeholder="Agent 架构、RAG、模型评测、性能优化"
          /></label>
          <p v-if="selectedProject.questions?.length" class="question-generate-notice">
            重新生成会替换智能生成的问题，手动添加或编辑过的问题会保留。
          </p>
        </div>

        <div v-else class="form-grid compact-form modal-form-grid question-manual-form">
          <label class="wide"
            ><span class="form-required">问题</span
            ><textarea
              ref="questionInput"
              aria-required="true"
              v-model.trim="questionModal.question"
              maxlength="500"
              rows="2"
              placeholder="例如：这个 Agent 如何进行工具选择与失败恢复？"
            />
          </label>
          <label
            ><span>分类</span
            ><input v-model.trim="questionModal.category" maxlength="40" placeholder="Agent 架构 / 模型评测"
          /></label>
          <label
            ><span class="form-required">难度</span
            ><select v-model="questionModal.difficulty" aria-required="true">
              <option value="" disabled>请选择难度</option>
              <option value="常规">常规</option>
              <option value="深入">深入</option>
            </select></label
          >
          <div class="wide project-answer-editor markdown-editor-field markdown-answer-editor">
            <div class="markdown-editor-head">
              <span>参考答案</span>
              <div class="markdown-editor-tabs" role="tablist" aria-label="参考答案编辑模式">
                <button
                  type="button"
                  role="tab"
                  :aria-selected="answerEditorMode === 'edit'"
                  :class="{ active: answerEditorMode === 'edit' }"
                  @click="answerEditorMode = 'edit'"
                >
                  编辑
                </button>
                <button
                  type="button"
                  role="tab"
                  :aria-selected="answerEditorMode === 'preview'"
                  :class="{ active: answerEditorMode === 'preview' }"
                  @click="answerEditorMode = 'preview'"
                >
                  预览
                </button>
              </div>
            </div>
            <label
              v-if="answerEditorMode === 'edit'"
              class="markdown-editor-pane markdown-source-pane"
              for="project-question-answer-markdown"
            >
              <span>Markdown 源码</span>
              <textarea
                id="project-question-answer-markdown"
                v-model="questionModal.answer"
                class="question-answer-input"
                maxlength="8000"
                placeholder="支持 Markdown：**加粗**、- 列表、`代码` 等"
              />
              <small class="field-hint">{{ questionModal.answer.length }} / 8000</small>
            </label>
            <section v-else class="markdown-editor-pane markdown-preview-pane" aria-label="参考答案 Markdown 预览">
              <span>渲染预览</span>
              <div class="markdown-preview-content">
                <PracticeMarkdown
                  :content="questionModal.answer"
                  custom-id="project-question-answer-preview"
                  empty-text="输入 Markdown 后可在这里查看答案效果"
                />
              </div>
            </section>
          </div>
        </div>
        <p v-if="questionModal.error" class="error settings-error form-error-alert" role="alert" aria-live="assertive">
          {{ questionModal.error }}
        </p>
        <div class="modal-actions">
          <button class="question-add-btn" :disabled="questionModalSubmitDisabled" @click="submitQuestionModal">
            {{ questionModalSubmitText }}
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="materialDeleteDialog.visible"
      class="modal-mask project-delete-mask"
      @click.self="closeMaterialDeleteDialog"
    >
      <div class="history-delete-modal">
        <button class="close" aria-label="关闭" @click="closeMaterialDeleteDialog">×</button>
        <p class="eyebrow">删除文件</p>
        <h2>删除这个项目文件？</h2>
        <p class="question-delete-preview">{{ materialDeleteDialog.name }}</p>
        <p>文件删除后无法下载，此操作无法撤销。</p>
        <div class="history-delete-actions">
          <button class="secondary-btn" :disabled="saving" @click="closeMaterialDeleteDialog">取消</button
          ><button class="danger-btn" :disabled="saving" @click="confirmDeleteMaterial">
            {{ saving ? '删除中' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="questionDeleteDialog.visible"
      class="modal-mask project-delete-mask"
      @click.self="closeQuestionDeleteDialog"
    >
      <div class="history-delete-modal">
        <button class="close" aria-label="关闭" @click="closeQuestionDeleteDialog">×</button>
        <p class="eyebrow">删除问题</p>
        <h2>删除这道问题？</h2>
        <p class="question-delete-preview">{{ questionDeleteDialog.name }}</p>
        <p>删除后无法恢复。</p>
        <div class="history-delete-actions">
          <button class="secondary-btn" :disabled="saving" @click="closeQuestionDeleteDialog">取消</button
          ><button class="danger-btn" :disabled="saving" @click="confirmDeleteQuestion">
            {{ saving ? '删除中' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="deleteDialog.visible" class="modal-mask project-delete-mask" @click.self="closeDeleteDialog">
      <div class="history-delete-modal">
        <button class="close" aria-label="关闭" @click="closeDeleteDialog">×</button>
        <p class="eyebrow">删除项目</p>
        <h2>删除“{{ deleteDialog.name }}”？</h2>
        <p>项目材料和已生成的问题将一并删除，此操作无法撤销。</p>
        <div class="history-delete-actions">
          <button class="secondary-btn" :disabled="saving" @click="closeDeleteDialog">取消</button
          ><button class="danger-btn" :disabled="saving" @click="confirmDeleteProject">
            {{ saving ? '删除中' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import ProjectLibraryPanel from './project-deep-dive/ProjectLibraryPanel.vue'
import { useProjectDeepDivePage } from '../composables/useProjectDeepDivePage'

const {
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
  projectNameInput,
  questionKeyword,
  selectedMaterialIds,
  questionPage,
  questionPageSize,
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
  materialCount,
  questionCount,
  selectQuestion,
  isManualQuestion,
  openQuestionModal,
  setQuestionEntryType,
  closeQuestionModal,
  submitQuestionModal,
  openQuestionDeleteDialog,
  closeQuestionDeleteDialog,
  confirmDeleteQuestion,
  projectMaterialBatchDownloadUrl,
  projectMaterialFileUrl,
  PracticeMarkdown,
} = useProjectDeepDivePage()
</script>
