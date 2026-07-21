<template>
  <section class="system-page boss-resume-page">
    <header class="page-header profile-page-header">
      <div>
        <p class="eyebrow">Job Seeker Profile</p>
        <h1>求职画像</h1>
        <p>维护对岗位筛选有用的信息，保存后用于推荐、匹配和问答上下文。</p>
      </div>
      <button type="button" class="profile-overview-trigger" @click="profileOverviewVisible = true">
        <span>画像概览</span>
        <small>{{ summaryStatus }}</small>
      </button>
    </header>

    <div class="profile-workbench-bar">
      <nav class="profile-section-nav" aria-label="求职画像编辑分段">
        <button
          v-for="section in profileSections"
          :key="section.key"
          type="button"
          :class="{ active: activeSection === section.key }"
          :aria-current="activeSection === section.key ? 'step' : undefined"
          @click="activeSection = section.key"
        >
          <span>{{ section.label }}</span>
          <small v-if="section.count !== undefined">{{ section.count }}</small>
        </button>
      </nav>
      <div class="profile-save-state" aria-live="polite">
        <span :class="['profile-save-indicator', { dirty, error: !!error }]">{{ profileSaveState }}</span>
        <button class="primary-btn" :disabled="saving || !dirty" @click="saveProfile">
          {{ saving ? '保存中' : '保存修改' }}
        </button>
      </div>
    </div>

    <p v-if="error" class="inline-feedback error profile-inline-feedback" role="alert">
      {{ error }} <button type="button" @click="saveProfile">重试保存</button>
    </p>
    <p v-else-if="saveHint" class="inline-feedback profile-inline-feedback" aria-live="polite">{{ saveHint }}</p>

    <div class="boss-resume-layout profile-section-content">
      <section v-show="activeSection === 'profile'" class="boss-section-card glass-card profile-info-card">
        <div class="card-title profile-card-title">
          <span class="profile-card-mark">01</span>
          <div>
            <h2>基本信息</h2>
            <span>填写用于岗位推荐和简历匹配的基础信息</span>
          </div>
        </div>
        <div class="boss-fixed-grid compact-profile-grid">
          <label><span>姓名</span><input v-model="form.basic.name" placeholder="请输入姓名" /></label>
          <label
            ><span>性别</span
            ><select v-model="form.basic.gender">
              <option value="" disabled>请选择</option>
              <option>男</option>
              <option>女</option>
            </select></label
          >
          <label><span>年龄</span><input v-model="form.basic.age" placeholder="例如：28" /></label>
          <label><span>所在城市</span><input v-model="form.basic.city" placeholder="例如：上海" /></label>
          <label
            ><span>最高学历</span
            ><select v-model="form.basic.degree">
              <option value="" disabled>请选择</option>
              <option v-for="item in degreeOptions" :key="item">{{ item }}</option>
            </select></label
          >
          <label
            ><span>工作年限</span
            ><select v-model="form.basic.workYears">
              <option value="" disabled>请选择</option>
              <option v-for="item in workYearOptions" :key="item">{{ item }}</option>
            </select></label
          >
          <label class="wide"
            ><span>期望岗位</span
            ><input v-model="form.expectation.position" placeholder="例如：Java 大模型应用开发工程师"
          /></label>
        </div>
      </section>

      <section v-show="activeSection === 'profile'" class="boss-section-card glass-card profile-info-card">
        <div class="card-title profile-card-title">
          <span class="profile-card-mark">02</span>
          <div>
            <h2>个人优势</h2>
            <span>概括核心能力、项目亮点和职业优势</span>
          </div>
        </div>
        <textarea
          v-model="form.personalAdvantage"
          class="full-textarea profile-advantage-textarea"
          rows="8"
          placeholder="个人优势、自我介绍、核心竞争力"
        />
      </section>

      <section v-show="activeSection === 'profile'" class="boss-section-card glass-card profile-info-card">
        <div class="card-title profile-card-title">
          <span class="profile-card-mark">03</span>
          <div>
            <h2>求职状态</h2>
            <span>离职/在职、到岗时间等</span>
          </div>
        </div>
        <div class="boss-fixed-grid">
          <label
            ><span>当前状态</span
            ><select v-model="form.status.status">
              <option value="" disabled>请选择</option>
              <option v-for="item in jobStatusOptions" :key="item">{{ item }}</option>
            </select></label
          >
          <label
            ><span>到岗时间</span
            ><select v-model="form.status.arrivalTime">
              <option value="" disabled>请选择</option>
              <option v-for="item in arrivalOptions" :key="item">{{ item }}</option>
            </select></label
          >
          <label
            ><span>求职类型</span
            ><select v-model="form.status.jobType">
              <option value="" disabled>请选择</option>
              <option v-for="item in jobTypeOptions" :key="item">{{ item }}</option>
            </select></label
          >
          <label
            ><span>工作模式</span
            ><select v-model="form.status.workMode">
              <option value="" disabled>请选择</option>
              <option v-for="item in workModeOptions" :key="item">{{ item }}</option>
            </select></label
          >
        </div>
      </section>

      <section
        v-show="activeSection === 'profile'"
        class="boss-section-card glass-card profile-info-card profile-expectation-card"
      >
        <div class="card-title profile-card-title">
          <span class="profile-card-mark">04</span>
          <div>
            <h2>求职期望</h2>
            <span>期望城市、岗位、薪资、行业</span>
          </div>
        </div>
        <div class="boss-fixed-grid">
          <label><span>期望城市</span><input v-model="form.expectation.city" placeholder="例如：上海" /></label>
          <label
            ><span>期望薪资</span>
            <div class="profile-editable-select" data-profile-editable-select>
              <input
                v-model="form.expectation.salary"
                role="combobox"
                aria-label="期望薪资"
                aria-autocomplete="list"
                :aria-expanded="expectationDropdown === 'salary'"
                placeholder="请选择或输入，例如：40-50K"
                @focus="openExpectationDropdown('salary')"
                @keydown.escape="closeExpectationDropdown"
              />
              <button
                type="button"
                class="profile-editable-select-toggle"
                aria-label="展开期望薪资选项"
                @mousedown.prevent
                @click.stop="toggleExpectationDropdown('salary')"
              >
                ▾
              </button>
              <div v-if="expectationDropdown === 'salary'" class="profile-editable-select-menu" role="listbox">
                <button
                  v-for="item in salaryOptions"
                  :key="item"
                  type="button"
                  role="option"
                  :aria-selected="form.expectation.salary === item"
                  @mousedown.prevent="selectExpectationOption('salary', item)"
                >
                  {{ item }}
                </button>
              </div>
            </div>
          </label>
          <label
            ><span>期望行业</span>
            <div class="profile-editable-select" data-profile-editable-select>
              <input
                v-model="form.expectation.industry"
                role="combobox"
                aria-label="期望行业"
                aria-autocomplete="list"
                :aria-expanded="expectationDropdown === 'industry'"
                placeholder="请选择或输入，例如：人工智能"
                @focus="openExpectationDropdown('industry')"
                @keydown.escape="closeExpectationDropdown"
              />
              <button
                type="button"
                class="profile-editable-select-toggle"
                aria-label="展开期望行业选项"
                @mousedown.prevent
                @click.stop="toggleExpectationDropdown('industry')"
              >
                ▾
              </button>
              <div v-if="expectationDropdown === 'industry'" class="profile-editable-select-menu" role="listbox">
                <button
                  v-for="item in industryOptions"
                  :key="item"
                  type="button"
                  role="option"
                  :aria-selected="form.expectation.industry === item"
                  @mousedown.prevent="selectExpectationOption('industry', item)"
                >
                  {{ item }}
                </button>
              </div>
            </div>
          </label>
          <label class="wide"
            ><span>强减分项</span
            ><input
              v-model="form.expectation.negativeExcludes"
              placeholder="可接受但显著降低匹配，例如：出差、大小周、加班多"
          /></label>
          <label class="wide"
            ><span>硬性拒绝项</span
            ><input v-model="form.expectation.rejectExcludes" placeholder="出现就直接拒绝，例如：外包、劳务派遣、驻场"
          /></label>
        </div>
      </section>

      <section v-show="activeSection === 'profile'" class="boss-section-card glass-card profile-skills-card">
        <div class="card-title">
          <div>
            <h2>技能标签</h2>
            <span>用于岗位筛选和匹配，建议保留最能代表能力方向的标签</span>
          </div>
          <b>{{ skillTags.length }} 个标签</b>
        </div>
        <div v-if="skillTags.length" class="profile-skill-tags" aria-label="当前技能标签">
          <span v-for="skill in skillTags" :key="skill"
            >{{ skill
            }}<button type="button" :aria-label="`移除技能 ${skill}`" @click="removeSkill(skill)">×</button></span
          >
        </div>
        <div v-else class="profile-skills-empty">还没有技能标签，请在下方添加。</div>
        <div class="profile-skill-input-row">
          <input
            v-model.trim="skillDraft"
            placeholder="输入技能后按回车，例如 LangGraph"
            @keydown="handleSkillKeydown"
          />
          <button type="button" class="primary-btn" :disabled="!skillDraft.trim()" @click="addSkills">添加标签</button>
        </div>
        <p class="profile-skill-hint">请逐个添加技能标签。点击标签右侧 × 可移除。</p>
      </section>

      <section v-show="activeSection === 'education'" class="boss-section-card glass-card profile-experience-section">
        <div class="card-title profile-section-title">
          <div>
            <h2>教育经历</h2>
            <span>完善学校、专业与学历信息，可同时展开多段经历编辑</span>
          </div>
          <button @click="addEducation">添加教育经历</button>
        </div>
        <div class="profile-experience-list">
          <details
            v-for="(item, index) in form.educationExperiences"
            :key="item.id"
            :open="isEntryExpanded('education', item.id)"
            class="repeat-card profile-experience-card"
            @toggle="toggleEntry('education', item.id, $event)"
          >
            <summary class="profile-experience-summary">
              <span class="profile-experience-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="profile-experience-heading"
                ><strong>{{ item.school || `教育经历 ${index + 1}` }}</strong
                ><small>{{
                  [item.major, item.degree].filter(Boolean).join(' · ') || '暂未填写专业与学历'
                }}</small></span
              >
              <span class="profile-experience-meta">{{
                monthRange(item.startDate, item.endDate) || '时间待完善'
              }}</span>
              <span class="profile-experience-toggle">{{
                isEntryExpanded('education', item.id) ? '收起' : '展开'
              }}</span>
              <span class="profile-experience-chevron" aria-hidden="true">›</span>
              <button
                type="button"
                class="row-delete profile-experience-delete"
                @click.stop.prevent="removeEducation(index)"
              >
                删除
              </button>
            </summary>
            <div class="profile-experience-editor">
              <div class="boss-fixed-grid">
                <label><span>学校名称</span><input v-model="item.school" placeholder="学校名称" /></label>
                <label><span>学院</span><input v-model="item.college" placeholder="例如：计算机学院" /></label>
                <label><span>专业</span><input v-model="item.major" placeholder="专业" /></label>
                <label
                  ><span>学历</span
                  ><select v-model="item.degree">
                    <option value="" disabled>请选择</option>
                    <option v-for="degree in degreeOptions" :key="degree">{{ degree }}</option>
                  </select></label
                >
                <label><span>入学时间</span><input v-model="item.startDate" type="month" /></label>
                <label><span>毕业时间</span><input v-model="item.endDate" type="month" /></label>
                <label
                  ><span>是否全日制</span
                  ><select v-model="item.fullTime">
                    <option value="" disabled>请选择</option>
                    <option v-for="opt in fullTimeOptions" :key="opt">{{ opt }}</option>
                  </select></label
                >
                <label
                  ><span>学历状态</span
                  ><select v-model="item.status">
                    <option value="" disabled>请选择</option>
                    <option v-for="opt in educationStatusOptions" :key="opt">{{ opt }}</option>
                  </select></label
                >
              </div>
            </div>
          </details>
        </div>
      </section>

      <section v-show="activeSection === 'work'" class="boss-section-card glass-card profile-experience-section">
        <div class="card-title profile-section-title">
          <div>
            <h2>工作经历</h2>
            <span>记录任职信息、工作内容与量化成果，可同时展开多段经历编辑</span>
          </div>
          <button @click="addWork">添加工作经历</button>
        </div>
        <div class="profile-experience-list">
          <details
            v-for="(item, index) in form.workExperiences"
            :key="item.id"
            :open="isEntryExpanded('work', item.id)"
            class="repeat-card profile-experience-card"
            @toggle="toggleEntry('work', item.id, $event)"
          >
            <summary class="profile-experience-summary">
              <span class="profile-experience-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="profile-experience-heading"
                ><strong>{{ item.company || `工作经历 ${index + 1}` }}</strong
                ><small>{{ item.position || '暂未填写职位名称' }}</small></span
              >
              <span class="profile-experience-meta">{{
                monthRange(item.startDate, item.endDate) || '时间待完善'
              }}</span>
              <span class="profile-experience-toggle">{{ isEntryExpanded('work', item.id) ? '收起' : '展开' }}</span>
              <span class="profile-experience-chevron" aria-hidden="true">›</span>
              <button
                type="button"
                class="row-delete profile-experience-delete"
                @click.stop.prevent="removeWork(index)"
              >
                删除
              </button>
            </summary>
            <div class="profile-experience-editor">
              <div class="boss-fixed-grid">
                <label><span>公司名称</span><input v-model="item.company" placeholder="公司名称" /></label>
                <label><span>职位名称</span><input v-model="item.position" placeholder="职位名称" /></label>
                <label
                  ><span>开始时间</span>
                  <div class="pretty-month-field">
                    <select
                      :value="monthYear(item.startDate)"
                      @change="updateMonth(item, 'startDate', 'year', $event.target.value)"
                    >
                      <option value="">年份</option>
                      <option v-for="year in monthYears" :key="year" :value="year">{{ year }}年</option></select
                    ><select
                      :value="monthMonth(item.startDate)"
                      @change="updateMonth(item, 'startDate', 'month', $event.target.value)"
                    >
                      <option value="">月份</option>
                      <option v-for="month in monthOptions" :key="month" :value="month">{{ Number(month) }}月</option>
                    </select>
                  </div></label
                >
                <label
                  ><span>结束时间</span>
                  <div class="pretty-month-field">
                    <select
                      :value="monthYear(item.endDate)"
                      @change="updateMonth(item, 'endDate', 'year', $event.target.value)"
                    >
                      <option value="">年份</option>
                      <option v-for="year in monthYears" :key="year" :value="year">{{ year }}年</option></select
                    ><select
                      :value="monthMonth(item.endDate)"
                      @change="updateMonth(item, 'endDate', 'month', $event.target.value)"
                    >
                      <option value="">月份</option>
                      <option v-for="month in monthOptions" :key="month" :value="month">{{ Number(month) }}月</option>
                    </select>
                  </div></label
                >
                <label class="wide"
                  ><span>工作内容</span
                  ><textarea
                    v-model="item.description"
                    rows="4"
                    placeholder="工作职责、负责模块、技术栈、协作方式，建议 3-5 条"
                  />
                </label>
                <label class="wide"
                  ><span>工作业绩</span
                  ><textarea
                    v-model="item.achievement"
                    rows="4"
                    placeholder="量化成果、项目交付、效率提升、获奖或业务价值，建议 2-4 条"
                  />
                </label>
              </div>
            </div>
          </details>
        </div>
      </section>

      <section
        v-show="activeSection === 'projects'"
        class="boss-section-card glass-card profile-projects-card profile-experience-section"
      >
        <div class="card-title profile-section-title">
          <div>
            <h2>项目经历</h2>
            <span>梳理项目角色、技术栈与成果，可同时展开多段经历编辑</span>
          </div>
          <button @click="addProject">添加项目经历</button>
        </div>
        <div class="profile-project-list">
          <details
            v-for="(item, index) in form.projectExperiences"
            :key="item.id"
            :open="isEntryExpanded('projects', item.id)"
            class="repeat-card profile-project-card profile-experience-card"
            @toggle="toggleEntry('projects', item.id, $event)"
          >
            <summary class="profile-project-summary">
              <span class="profile-project-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="profile-project-heading">
                <strong>{{ item.name || `项目经历 ${index + 1}` }}</strong>
                <small>{{ item.role || '暂未填写项目角色' }}</small>
              </span>
              <span class="profile-project-meta">{{
                projectMonthRange(item.startDate, item.endDate) || '时间待完善'
              }}</span>
              <span v-if="projectTechTags(item.techStack).length" class="profile-project-techs" aria-label="项目技术栈">
                <em v-for="tech in projectTechTags(item.techStack).slice(0, 4)" :key="tech">{{ tech }}</em>
                <em v-if="projectTechTags(item.techStack).length > 4" class="more"
                  >+{{ projectTechTags(item.techStack).length - 4 }}</em
                >
              </span>
              <span v-else class="profile-project-empty-tech">暂未填写技术栈</span>
              <span class="profile-project-toggle">{{ isEntryExpanded('projects', item.id) ? '收起' : '展开' }}</span>
              <span class="profile-project-chevron" aria-hidden="true">›</span>
            </summary>
            <div class="profile-project-editor">
              <div class="boss-fixed-grid">
                <label><span>项目名称</span><input v-model="item.name" placeholder="项目名称" /></label>
                <label><span>项目角色</span><input v-model="item.role" placeholder="例如：后端负责人" /></label>
                <label><span>开始时间</span><input v-model="item.startDate" type="month" /></label>
                <label><span>结束时间</span><input v-model="item.endDate" type="month" /></label>
                <div class="wide profile-project-tech-editor">
                  <span class="profile-project-field-label">技术栈</span>
                  <div v-if="projectTechTags(item.techStack).length" class="profile-project-tech-tag-list">
                    <span v-for="tech in projectTechTags(item.techStack)" :key="tech">
                      {{ tech }}
                      <button type="button" :aria-label="`移除项目技术 ${tech}`" @click="removeProjectTech(item, tech)">
                        ×
                      </button>
                    </span>
                  </div>
                  <div class="profile-project-tech-input-row">
                    <input
                      v-model.trim="item.techDraft"
                      placeholder="输入一项技术后按回车，例如 Spring Boot"
                      @keydown.enter.prevent="addProjectTech(item)"
                    />
                    <button type="button" :disabled="!item.techDraft.trim()" @click="addProjectTech(item)">
                      添加标签
                    </button>
                  </div>
                </div>
                <label class="wide"
                  ><span>项目职责</span
                  ><textarea
                    v-model="item.responsibility"
                    rows="4"
                    placeholder="负责模块、技术方案、协作方式，建议 3-5 条"
                  />
                </label>
                <label class="wide"
                  ><span>主要贡献</span
                  ><textarea
                    v-model="item.achievement"
                    rows="4"
                    placeholder="关键成果、量化价值、难点突破，建议 2-4 条"
                  />
                </label>
              </div>
              <div class="profile-project-editor-actions">
                <button type="button" class="row-delete" @click="removeProject(index)">删除这段项目经历</button>
              </div>
            </div>
          </details>
        </div>
      </section>
    </div>

    <div
      v-if="profileOverviewVisible"
      class="modal-mask profile-overview-modal-mask"
      @click.self="profileOverviewVisible = false"
    >
      <div
        class="modal-card profile-overview-modal-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="profile-overview-title"
        aria-describedby="profile-overview-description"
      >
        <button
          type="button"
          class="close profile-overview-close"
          aria-label="关闭画像概览"
          @click="profileOverviewVisible = false"
        >
          ×
        </button>
        <header class="profile-overview-modal-head">
          <div class="profile-overview-heading">
            <p class="eyebrow">Profile Overview</p>
            <div class="profile-overview-title-row">
              <h2 id="profile-overview-title">画像概览</h2>
              <span :class="['profile-overview-status', { dirty }]">{{ dirty ? '待保存' : '已同步' }}</span>
            </div>
            <p id="profile-overview-description">
              基于当前已填写的信息提炼，用于岗位推荐、匹配和问答上下文。你也可以直接编辑。
            </p>
          </div>
          <button
            type="button"
            class="secondary-btn profile-summary-ai-button"
            :disabled="generatingSummary"
            @click="generateSummary"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path
                d="M12 3l1.35 4.15L17.5 8.5l-4.15 1.35L12 14l-1.35-4.15L6.5 8.5l4.15-1.35L12 3Zm6 10 .8 2.2L21 16l-2.2.8L18 19l-.8-2.2L15 16l2.2-.8L18 13Z"
              />
            </svg>
            <span
              ><strong>{{ generatingSummary ? 'AI 提取中' : 'AI 提取画像' }}</strong
              ><small>{{ generatingSummary ? '正在分析完整画像' : '基于当前信息重新生成' }}</small></span
            >
          </button>
        </header>
        <div class="profile-overview-modal-body">
          <label class="profile-overview-editor" for="profile-overview-summary">
            <span class="profile-overview-editor-head"
              ><strong>画像摘要</strong><small>建议突出经验、技术方向与核心项目</small></span
            >
            <textarea
              id="profile-overview-summary"
              v-model="form.summary"
              class="full-textarea"
              rows="7"
              placeholder="当前还没有画像摘要。你可以直接填写，或点击“AI 提取画像”根据基本信息、求职期望、教育、工作、项目和技能自动生成。"
            />
          </label>
          <div class="profile-overview-meta" aria-live="polite">
            <span>{{ form.summary.trim() ? `${form.summary.trim().length} 个字符` : '尚未生成画像摘要' }}</span>
            <span v-if="dirty" class="dirty">内容已修改，保存后生效</span>
            <span v-else class="saved">内容已保存并用于岗位匹配</span>
          </div>
        </div>
        <footer class="modal-actions profile-overview-actions">
          <p>{{ dirty ? '保存后将同步更新画像上下文' : '当前内容已是最新版本' }}</p>
          <div>
            <button type="button" class="primary-btn" :disabled="saving" @click="saveOverviewProfile">
              {{ saving ? '保存中' : '保存' }}
            </button>
          </div>
        </footer>
      </div>
    </div>

    <div v-if="summaryCompare.visible" class="modal-mask profile-ai-modal-mask" @click.self="closeSummaryCompare">
      <div class="modal-card profile-ai-modal-card">
        <header class="profile-ai-modal-head">
          <div>
            <p class="eyebrow">AI Summary Compare</p>
            <h2>是否使用 AI 更新画像摘要？</h2>
            <p class="compare-tip">系统已生成新的画像摘要。请对比当前版本和 AI 版本，确认后再覆盖。</p>
          </div>
          <button class="close" aria-label="关闭摘要对比" @click="closeSummaryCompare">×</button>
        </header>
        <div class="profile-ai-modal-body">
          <div class="summary-compare-grid">
            <section>
              <strong>当前摘要</strong>
              <p>{{ summaryCompare.oldSummary || '当前为空' }}</p>
            </section>
            <section>
              <strong>AI 建议</strong>
              <p>{{ summaryCompare.newSummary }}</p>
            </section>
          </div>
          <div class="summary-diff-card">
            <strong>差异对比</strong>
            <div class="summary-diff-legend">
              <span class="removed">删除</span><span class="added">新增</span><span>未标记为保留内容</span>
            </div>
            <p class="summary-diff-text">
              <template v-for="(part, index) in summaryDiff" :key="`${part.type}-${index}-${part.text}`">
                <span :class="['summary-diff-token', part.type]">{{ part.text }}</span>
              </template>
            </p>
          </div>
        </div>
        <div class="modal-actions profile-ai-modal-actions">
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
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { generateJobProfileSummary } from '../api/resume'
import { useResumeStore } from '../stores/resume'

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
async function saveProfile() {
  saving.value = true
  error.value = ''
  saveHint.value = ''
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
</script>
