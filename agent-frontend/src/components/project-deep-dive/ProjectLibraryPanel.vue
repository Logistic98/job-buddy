<template>
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
      <div class="project-stat-card readiness">
        <span class="project-stat-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <circle cx="12" cy="12" r="8.5" />
            <path d="m8.5 12 2.2 2.2 4.8-5" />
          </svg>
        </span>
        <div>
          <span>平均准备度</span><strong>{{ libraryStats.averageReadiness }}%</strong>
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

<script setup>
defineProps({
  error: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  projects: { type: Array, default: () => [] },
  libraryStats: { type: Object, required: true },
  openCreate: { type: Function, required: true },
  loadProjects: { type: Function, required: true },
  readiness: { type: Function, required: true },
  techLabels: { type: Function, required: true },
  materialCount: { type: Function, required: true },
  questionCount: { type: Function, required: true },
  formatUpdatedAt: { type: Function, required: true },
  openProject: { type: Function, required: true },
  openDeleteDialog: { type: Function, required: true },
})
</script>
