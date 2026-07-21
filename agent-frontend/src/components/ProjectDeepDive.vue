<template>
  <section class="system-page project-deep-page project-deep-v2">
    <template v-if="!selectedId">
      <header class="page-header project-library-header">
        <div class="project-library-heading">
          <span class="project-library-title-icon" aria-hidden="true">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path
                d="M4 7.5A2.5 2.5 0 0 1 6.5 5H10l2 2h5.5A2.5 2.5 0 0 1 20 9.5v7A2.5 2.5 0 0 1 17.5 19h-11A2.5 2.5 0 0 1 4 16.5z"
              />
              <circle cx="13.5" cy="12.5" r="2.5" />
              <path d="m15.4 14.4 2.1 2.1" />
            </svg>
          </span>
          <div class="project-library-title-copy">
            <p class="eyebrow">Project Deep Dive</p>
            <h1>项目深挖</h1>
            <p>集中管理项目经历，选择一个项目后进入材料整理与面试问题复盘。</p>
          </div>
        </div>
        <button class="primary-btn project-create-btn" @click="openCreate">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          <span>新增项目</span>
        </button>
      </header>

      <p v-if="error" class="error settings-error project-library-error" role="alert">
        {{ error }}
        <button type="button" @click="loadProjects">重新加载</button>
      </p>

      <div v-if="loading && !projects.length" class="project-library-loading" aria-live="polite">
        <div v-for="index in 6" :key="index" class="project-card-skeleton"><span></span><span></span><span></span></div>
      </div>

      <template v-else>
        <section v-if="projects.length" class="project-library-summary" aria-label="项目准备概览">
          <div class="project-stat-card projects">
            <span class="project-stat-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M3.5 8.5h17v9a2.5 2.5 0 0 1-2.5 2.5H6a2.5 2.5 0 0 1-2.5-2.5z" />
                <path d="M3.5 8.5V7A2.5 2.5 0 0 1 6 4.5h4l2 2h6A2.5 2.5 0 0 1 20.5 9" />
                <path d="M8 13h8M8 16h5" />
              </svg>
            </span>
            <div>
              <span>项目总数</span><strong>{{ projects.length }}</strong>
            </div>
          </div>
          <div class="project-stat-card materials">
            <span class="project-stat-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M8 3.5h6l4 4v13H8z" />
                <path d="M14 3.5v4h4M11 12h4M11 15.5h4" />
                <path d="M5 6.5v13a1 1 0 0 0 1 1" />
              </svg>
            </span>
            <div>
              <span>材料总数</span><strong>{{ libraryStats.materials }}</strong>
            </div>
          </div>
          <div class="project-stat-card questions">
            <span class="project-stat-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M4 5h16v12H9l-5 3z" />
                <path d="M9.5 9.5a2.5 2.5 0 1 1 3.8 2.1c-.8.5-1.3 1-1.3 2M12 15.5h.01" />
              </svg>
            </span>
            <div>
              <span>深挖问题</span><strong>{{ libraryStats.questions }}</strong>
            </div>
          </div>
        </section>

        <div v-if="projects.length" :class="['project-library-grid', { 'two-row': projects.length > 3 }]">
          <article
            v-for="item in projects"
            :key="item.projectId"
            :class="['project-library-card', `is-${readiness(item).tone}`]"
            role="button"
            tabindex="0"
            :aria-label="`打开项目 ${item.name}`"
            @click="openProject(item.projectId)"
            @keydown.enter.self="openProject(item.projectId)"
          >
            <div class="project-card-topline">
              <span :class="['project-readiness', readiness(item).tone]">
                <svg v-if="readiness(item).tone === 'pending'" viewBox="0 0 20 20" aria-hidden="true">
                  <circle cx="10" cy="10" r="6.5" />
                  <path d="M10 6.5v4l2.5 1.5" />
                </svg>
                <svg v-else-if="readiness(item).tone === 'ready'" viewBox="0 0 20 20" aria-hidden="true">
                  <path d="M10 3.5 11.4 8l4.6 1.4-4.6 1.4-1.4 4.7-1.4-4.7L4 9.4 8.6 8z" />
                </svg>
                <svg v-else viewBox="0 0 20 20" aria-hidden="true">
                  <circle cx="10" cy="10" r="6.5" />
                  <path d="m7 10 2 2 4-4" />
                </svg>
                {{ readiness(item).label }}
              </span>
              <button
                class="project-card-delete"
                type="button"
                :aria-label="`删除项目 ${item.name}`"
                @click.stop="openDeleteDialog(item)"
              >
                删除
              </button>
            </div>
            <div class="project-card-main">
              <h2>{{ item.name }}</h2>
              <p class="project-card-role">{{ item.role || '核心开发' }}</p>
              <p class="project-card-summary">
                {{ item.summary || '暂无项目摘要，进入项目后可补充背景、职责和关键结果。' }}
              </p>
              <div v-if="techLabels(item).length" class="project-tech-tags">
                <span v-for="tech in techLabels(item).slice(0, 5)" :key="tech">{{ tech }}</span>
                <em v-if="techLabels(item).length > 5">+{{ techLabels(item).length - 5 }}</em>
              </div>
            </div>
            <div class="project-card-metrics">
              <div>
                <strong>{{ materialCount(item) }}</strong
                ><span class="project-metric-label"
                  ><svg viewBox="0 0 20 20" aria-hidden="true">
                    <path d="M6 3.5h5l3 3v10H6z" />
                    <path d="M11 3.5v3h3" /></svg
                  >份材料</span
                >
              </div>
              <div>
                <strong>{{ questionCount(item) }}</strong
                ><span class="project-metric-label"
                  ><svg viewBox="0 0 20 20" aria-hidden="true">
                    <path d="M4.5 4.5h11v9h-7l-4 3z" />
                    <path d="M8 8h4M8 10.5h2.5" /></svg
                  >道问题</span
                >
              </div>
              <div>
                <strong>{{ readiness(item).progress }}%</strong
                ><span class="project-metric-label"
                  ><svg viewBox="0 0 20 20" aria-hidden="true">
                    <circle cx="10" cy="10" r="6" />
                    <path d="m7.5 10 1.7 1.7 3.5-3.7" /></svg
                  >准备度</span
                >
              </div>
            </div>
            <div class="project-card-footer">
              <span>{{ formatUpdatedAt(item.updatedAt) }}</span>
              <button class="primary-btn" @click.stop="openProject(item.projectId, readiness(item).stage)">
                <span>{{ readiness(item).action }}</span
                ><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m7 4 6 6-6 6" /></svg>
              </button>
            </div>
          </article>
        </div>

        <section v-else class="project-library-empty" aria-labelledby="project-empty-title">
          <div class="project-empty-hero">
            <span class="project-empty-visual" aria-hidden="true">
              <svg viewBox="0 0 64 64" fill="none">
                <path
                  d="M10 21.5A5.5 5.5 0 0 1 15.5 16H27l5 5h16.5a5.5 5.5 0 0 1 5.5 5.5V45a5 5 0 0 1-5 5H15a5 5 0 0 1-5-5z"
                />
                <path d="M25 36h14M32 29v14" />
              </svg>
            </span>
            <p class="eyebrow">Start Your First Project</p>
            <h2 id="project-empty-title">创建你的第一个项目档案</h2>
            <p class="project-empty-description">沉淀真实项目经历，把零散信息整理成可复盘、可表达的面试素材。</p>
            <button class="primary-btn project-empty-create" type="button" @click="openCreate">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 4v12M4 10h12" /></svg>
              <span>创建第一个项目</span>
            </button>
            <small>只需填写项目名称，其他信息可以稍后补充</small>
          </div>

          <ol class="project-empty-steps" aria-label="项目深挖使用流程">
            <li>
              <span>01</span>
              <div>
                <strong>创建项目档案</strong>
                <p>记录项目名称、角色与核心目标</p>
              </div>
            </li>
            <li>
              <span>02</span>
              <div>
                <strong>补充真实材料</strong>
                <p>完善职责、技术难点与项目成果</p>
              </div>
            </li>
            <li>
              <span>03</span>
              <div>
                <strong>生成深挖问题</strong>
                <p>围绕项目经历进行针对性复盘</p>
              </div>
            </li>
          </ol>
        </section>
      </template>
    </template>

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
                    <MarkdownRender
                      v-if="answerMarkdown"
                      :key="`answer-${selectedQuestion.questionId || questionPosition}-${answerMarkdown.length}`"
                      class="deep-markdown"
                      custom-id="project-deep-answer"
                      :content="answerMarkdown"
                      :final="true"
                      html-policy="escape"
                      :max-live-nodes="0"
                      :fade="false"
                      :typewriter="false"
                      :smooth-streaming="false"
                    />
                    <p v-else class="question-detail-empty">
                      暂无参考答案。建议补充项目背景、个人职责、方案取舍、结果指标和复盘。
                    </p>
                  </section>
                  <section v-if="followUpMarkdown" class="question-detail-block">
                    <h4>可能追问</h4>
                    <MarkdownRender
                      :key="`followup-${selectedQuestion.questionId || questionPosition}-${followUpMarkdown.length}`"
                      class="deep-markdown"
                      custom-id="project-deep-followup"
                      :content="followUpMarkdown"
                      :final="true"
                      html-policy="escape"
                      :max-live-nodes="0"
                      :fade="false"
                      :typewriter="false"
                      :smooth-streaming="false"
                    />
                  </section>
                  <section v-if="evidenceMarkdown" class="question-detail-block">
                    <h4>材料依据</h4>
                    <MarkdownRender
                      :key="`evidence-${selectedQuestion.questionId || questionPosition}-${evidenceMarkdown.length}`"
                      class="deep-markdown"
                      custom-id="project-deep-evidence"
                      :content="evidenceMarkdown"
                      :final="true"
                      html-policy="escape"
                      :max-live-nodes="0"
                      :fade="false"
                      :typewriter="false"
                      :smooth-streaming="false"
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
              ><span>项目名称</span
              ><input
                ref="projectNameInput"
                v-model.trim="form.name"
                maxlength="80"
                placeholder="例如：企业知识库检索助手"
            /></label>
            <label
              ><span>项目角色</span><input v-model.trim="form.role" maxlength="40" placeholder="核心开发 / 后端开发"
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
                  placeholder="输入一项技术，例如 Spring Boot"
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
          <p v-if="modalError" class="error settings-error" role="alert">{{ modalError }}</p>
        </div>
        <div class="modal-actions project-editor-actions">
          <button class="secondary-btn" :disabled="saving" @click="closeCreate">取消</button
          ><button class="primary-btn" :disabled="saving || !form.name.trim()" @click="saveProject">
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
            ><span>生成数量</span><input v-model.number="generateForm.count" type="number" min="4" max="40"
          /></label>
          <label
            ><span>关注方向</span
            ><input v-model.trim="generateForm.focus" placeholder="架构设计、技术难点、性能优化、项目复盘"
          /></label>
          <p v-if="selectedProject.questions?.length" class="question-generate-notice">
            重新生成会替换智能生成的问题，手动添加或编辑过的问题会保留。
          </p>
        </div>

        <div v-else class="form-grid compact-form modal-form-grid question-manual-form">
          <label class="wide"
            ><span>问题</span
            ><textarea
              ref="questionInput"
              v-model.trim="questionModal.question"
              maxlength="500"
              rows="2"
              placeholder="例如：这个模块的幂等是怎么保证的？"
            />
          </label>
          <label
            ><span>分类</span
            ><input v-model.trim="questionModal.category" maxlength="40" placeholder="架构设计 / 技术难点"
          /></label>
          <label
            ><span>难度</span
            ><select v-model="questionModal.difficulty">
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
        <p v-if="questionModal.error" class="error settings-error" role="alert">{{ questionModal.error }}</p>
        <div class="modal-actions">
          <button class="secondary-btn" :disabled="saving || generating" @click="closeQuestionModal">取消</button
          ><button class="question-add-btn" :disabled="questionModalSubmitDisabled" @click="submitQuestionModal">
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
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import MarkdownRender from 'markstream-vue'
import { useRoute, useRouter } from 'vue-router'
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
import PracticeMarkdown from './interview/PracticeMarkdown.vue'

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
  role: '核心开发',
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
const generateForm = reactive({ count: 12, focus: '架构设计、技术难点、性能优化、项目复盘' })
const deleteDialog = reactive({ visible: false, projectId: '', name: '' })
const questionModal = reactive({
  visible: false,
  mode: 'create',
  entryType: 'generate',
  questionId: '',
  question: '',
  answer: '',
  category: '',
  difficulty: '常规',
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
  if (questionModal.mode === 'edit' || questionModal.entryType === 'manual') return !questionModal.question.trim()
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
      replaceProject({ ...detail, materials: (detail.materials || []).filter((item) => item.materialId !== deletedId) })
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
    role: project.role || '核心开发',
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
  if (!form.name.trim()) {
    modalError.value = '请填写项目名称'
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
  const rejected = files.filter((file) => file.size <= 0 || file.size > maxBytes)
  const accepted = files.filter((file) => file.size > 0 && file.size <= maxBytes)
  const failures = rejected.map((file) => `${file.name}（${file.size <= 0 ? '空文件' : '超过 1GB'}）`)
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
          difficulty: question.difficulty === '深入' ? '深入' : '常规',
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
          difficulty: '常规',
          error: '',
        },
  )
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
  if (!questionModal.question.trim()) {
    questionModal.error = '请填写问题内容'
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
</script>
