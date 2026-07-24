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
            ><input v-model="form.expectation.position" placeholder="例如：Agent 与大模型应用开发工程师"
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
                <label
                  ><span>项目角色</span><input v-model="item.role" placeholder="例如：Agent 应用开发负责人"
                /></label>
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
                      placeholder="输入一项技术后按回车，例如 LangGraph"
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
            <p id="profile-overview-description">基于当前已填写的信息提炼，用于岗位推荐、匹配和问答上下文。</p>
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
import { useBossResumePage } from '../composables/useBossResumePage'

const {
  saving,
  generatingSummary,
  error,
  saveHint,
  warningMessage,
  profileOverviewVisible,
  summaryCompare,
  summaryDiff,
  form,
  activeSection,
  dirty,
  skillDraft,
  expectationDropdown,
  profileSections,
  skillTags,
  summaryStatus,
  profileSaveState,
  degreeOptions,
  fullTimeOptions,
  educationStatusOptions,
  monthOptions,
  monthYears,
  workYearOptions,
  jobStatusOptions,
  arrivalOptions,
  jobTypeOptions,
  workModeOptions,
  salaryOptions,
  industryOptions,
  monthYear,
  monthMonth,
  updateMonth,
  monthRange,
  projectMonthRange,
  projectTechTags,
  generateSummary,
  saveProfile,
  saveOverviewProfile,
  closeSummaryCompare,
  applyAiSummary,
  closeWarning,
  openExpectationDropdown,
  closeExpectationDropdown,
  toggleExpectationDropdown,
  selectExpectationOption,
  addSkills,
  removeSkill,
  handleSkillKeydown,
  addProjectTech,
  removeProjectTech,
  isEntryExpanded,
  toggleEntry,
  addWork,
  removeWork,
  addProject,
  removeProject,
  addEducation,
  removeEducation,
} = useBossResumePage()
</script>
