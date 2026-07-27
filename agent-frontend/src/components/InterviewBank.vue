<template>
  <section
    :class="
      embedded ? 'interview-embedded interview-manager-page' : 'system-page interview-page interview-manager-page'
    "
  >
    <InterviewBankHeader
      :embedded="embedded"
      :active-mode="activeMode"
      :page-eyebrow="pageEyebrow"
      :page-title="pageTitle"
      :page-description="pageDescription"
      :bank-type-options="bankTypeOptions"
      :active-bank-type="filters.bankType"
      :show-actions="activeMode === 'bank'"
      @create="openCreateModal"
      @switch-bank="switchBankTab"
    />

    <p v-if="error" class="error settings-error">{{ error }}</p>

    <section v-if="activeMode === 'bank'" class="interview-bank-view glass-card">
      <div class="interview-filter-bar">
        <label class="history-search"
          ><span>搜索题目</span
          ><input
            v-model.trim="filters.keyword"
            placeholder="输入标题、内容、答案或标签"
            @keyup.enter="searchQuestions"
        /></label>
        <label class="filter-select"
          ><span>分类</span
          ><select v-model="filters.category" @change="searchQuestions">
            <option value="">全部分类</option>
            <option v-for="item in categories" :key="item" :value="item">{{ item }}</option>
          </select></label
        >
        <label class="filter-select"
          ><span>难度</span
          ><select v-model="filters.difficulty" @change="searchQuestions">
            <option value="">全部难度</option>
            <option v-for="item in difficulties" :key="item" :value="item">{{ item }}</option>
          </select></label
        >
        <div class="filter-actions">
          <button class="secondary-btn" :disabled="loading" @click="searchQuestions">
            {{ loading ? '查询中' : '查询' }}</button
          ><button v-if="activeFilterCount" class="text-btn" @click="resetFilters">
            重置 {{ activeFilterCount }} 项
          </button>
        </div>
      </div>

      <div class="bank-summary-row">
        <span
          ><strong>{{ pagination.total }}</strong> 题目</span
        >
        <span
          ><strong>{{ categories.length }}</strong> 分类</span
        >
        <span v-if="activeFilterCount"
          ><strong>{{ activeFilterCount }}</strong> 个筛选条件</span
        >
        <span v-if="selectedIds.length" class="bank-summary-selected"
          ><strong>{{ selectedIds.length }}</strong> 已选</span
        >
      </div>

      <div v-if="selectedIds.length" class="selection-toolbar">
        <div class="selection-toolbar-main">
          <strong>已选 {{ selectedIds.length }} 题</strong>
          <button class="primary-btn" :disabled="examLoading" @click="startSelectedPractice">开始练习</button>
          <button class="secondary-btn" @click="showBatchEditor = !showBatchEditor">
            {{ showBatchEditor ? '收起批量设置' : '批量设置' }}
          </button>
          <button class="secondary-btn" @click="clearSelection">取消选择</button>
          <span class="selection-action-divider" aria-hidden="true"></span>
          <button class="danger-btn" :disabled="saving" @click="applyBatchDelete">删除所选</button>
        </div>
        <div v-if="showBatchEditor" class="selection-toolbar-editor">
          <input v-model.trim="batchForm.category" placeholder="分类，如 Agent 工程" />
          <select v-model="batchForm.difficulty">
            <option value="">难度不变</option>
            <option>简单</option>
            <option>中等</option>
            <option>困难</option>
          </select>
          <div class="batch-tag-editor">
            <div v-if="batchTags.length" class="question-tag-list" aria-label="批量设置标签">
              <span v-for="tag in batchTags" :key="tag"
                >{{ tag
                }}<button type="button" :aria-label="`移除批量标签 ${tag}`" @click="removeBatchTag(tag)">
                  ×
                </button></span
              >
            </div>
            <div class="batch-tag-input-row">
              <input v-model.trim="batchTagDraft" placeholder="输入一个标签后按回车" @keydown="handleBatchTagKeydown" />
              <button type="button" class="secondary-btn" :disabled="!batchTagDraft.trim()" @click="addBatchTag">
                添加标签
              </button>
            </div>
            <small v-if="batchTagError" class="batch-tag-error" role="alert">{{ batchTagError }}</small>
          </div>
          <button class="secondary-btn" :disabled="saving" @click="applyBatchChanges">应用批量修改</button>
        </div>
      </div>

      <div class="interview-table-wrap">
        <div v-if="loading && !filteredQuestions.length" class="loading-state compact">
          <strong>题库加载中</strong>
          <p>正在读取当前题库，请稍候。</p>
        </div>
        <table v-else class="interview-table">
          <thead>
            <tr>
              <th class="select-col">
                <input
                  type="checkbox"
                  :checked="allCurrentPageSelected"
                  aria-label="选择当前页全部题目"
                  @change="toggleCurrentPage($event.target.checked)"
                />
              </th>
              <th class="index-col">序号</th>
              <th>题目</th>
              <th>分类</th>
              <th>难度</th>
              <th>题型</th>
              <th class="tags-col">标签</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in filteredQuestions" :key="item.questionId">
              <td class="select-col">
                <input
                  type="checkbox"
                  :checked="selectedSet.has(item.questionId)"
                  :aria-label="`选择题目：${displayTitle(item, index)}`"
                  @change="toggleSelection(item.questionId, $event.target.checked)"
                />
              </td>
              <td class="index-col">
                <span class="question-index">{{ rowNumber(index) }}</span>
              </td>
              <td class="question-main-cell">
                <strong>{{ displayTitle(item, index) }}</strong>
                <p class="question-preview">{{ questionStem(item) }}</p>
              </td>
              <td>{{ item.category || '通用' }}</td>
              <td>
                <span class="state-badge warn">{{ item.difficulty || '中等' }}</span>
              </td>
              <td>{{ item.questionType || '简答' }}</td>
              <td class="tags-col">
                <div class="question-tags">
                  <span v-for="tag in tagLabels(item).slice(0, 3)" :key="tag">{{ tag }}</span>
                </div>
              </td>
              <td class="actions-col">
                <div class="table-actions compact-actions">
                  <button type="button" class="primary-text" @click="startSingleQuestionPractice(item)">单题练习</button
                  ><button type="button" class="row-secondary-action" @click="openEditModal(item)">编辑</button
                  ><button type="button" class="danger-text row-danger-action" @click="removeQuestion(item.questionId)">
                    删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!loading && !filteredQuestions.length" class="empty-state compact">
          <strong>暂无题目</strong>
          <p>点击“新增题目”，可选择手动录入或 AI 生成。</p>
        </div>
      </div>
      <div class="bank-pagination" v-if="pagination.total > 0">
        <span>第 {{ pagination.page }} / {{ pagination.pages || 1 }} 页，共 {{ pagination.total }} 题</span>
        <div>
          <select v-model.number="pagination.size" aria-label="每页题目数量" @change="changePageSize">
            <option :value="10">10 条/页</option>
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
          </select>
          <button class="secondary-btn" :disabled="pagination.page <= 1" @click="goPage(pagination.page - 1)">
            上一页
          </button>
          <button
            v-for="page in visiblePages"
            :key="page"
            :class="['page-num-btn', { active: page === pagination.page }]"
            @click="goPage(page)"
          >
            {{ page }}
          </button>
          <button
            class="secondary-btn"
            :disabled="pagination.page >= pagination.pages"
            @click="goPage(pagination.page + 1)"
          >
            下一页
          </button>
        </div>
      </div>
    </section>

    <section v-else class="practice-desk-view">
      <div v-if="isOpeningTargetExam" class="practice-entry-loading glass-card" role="status" aria-live="polite">
        <div class="loading-state compact">
          <strong>正在进入练习</strong>
          <p>正在准备题目和作答环境，请稍候。</p>
        </div>
      </div>
      <div v-else-if="!practiceHomeVisible && currentExam && activeQuestion" class="practice-active-workbench">
        <section class="practice-overview-card glass-card">
          <div class="practice-overview-main">
            <div class="practice-overview-title">
              <strong>{{ displayExamTitle(currentExam) }}</strong>
              <span v-if="currentExam.status !== 'submitted'">剩余 {{ remainingTimeText }}</span>
              <span v-else>得分 {{ currentExam.score }}</span>
            </div>
            <div class="practice-overview-progress">
              <span
                ><b>{{ answeredCount }}</b> / {{ examTotalCount }} 已完成</span
              >
              <div class="exam-progress-bar" aria-label="作答进度">
                <span :style="{ width: `${examProgressPercent}%` }"></span>
              </div>
            </div>
          </div>
          <div class="practice-overview-actions">
            <button class="secondary-btn compact" @click="returnToPracticeHome">练习记录</button>
            <button class="primary-btn compact" @click="requestComposePractice('smart')">创建练习</button>
          </div>
          <details class="practice-question-navigator">
            <summary>
              题目导航 <span>当前第 {{ currentQuestionIndex }} 题</span>
            </summary>
            <div class="practice-question-grid" aria-label="题目列表">
              <button
                v-for="(item, index) in currentExam.questions || []"
                :key="item.questionId"
                type="button"
                :title="item.title"
                :aria-label="`第 ${index + 1} 题：${item.title}`"
                :class="[
                  'practice-question-number',
                  { active: item.questionId === activeQuestion.questionId, answered: isQuestionAnswered(item) },
                ]"
                @click="setActiveQuestion(item.questionId)"
              >
                {{ index + 1 }}
              </button>
            </div>
          </details>
        </section>

        <div class="practice-leetcode-shell">
          <main class="practice-problem-panel glass-card">
            <div class="practice-panel-head">
              <div>
                <p class="eyebrow">{{ bankTypeLabel(activeQuestion.bankType) }}</p>
                <h2>{{ currentQuestionIndex }}. {{ activeQuestion.title }}</h2>
              </div>
              <span :class="['state-badge', difficultyClass(activeQuestion.difficulty)]">{{
                activeQuestion.difficulty || '中等'
              }}</span>
            </div>
            <div class="practice-problem-body">
              <PracticeMarkdown
                :key="`stem-${activeQuestion.questionId}`"
                :content="questionStem(activeQuestion)"
                :custom-id="`practice-stem-${activeQuestion.questionId}`"
              />
              <div class="question-tags">
                <span v-for="tag in tagLabels(activeQuestion).slice(0, 6)" :key="tag">{{ tag }}</span>
              </div>
              <section v-if="optionItems(activeQuestion).length" class="practice-description-block">
                <h3>选项</h3>
                <div class="exam-option-list readonly-options">
                  <label v-for="option in optionItems(activeQuestion)" :key="option.key" class="exam-option readonly">
                    <b>{{ option.key }}</b
                    ><PracticeMarkdown
                      :content="option.text"
                      :custom-id="`practice-option-${activeQuestion.questionId}-${option.key}`"
                      compact
                    />
                  </label>
                </div>
              </section>
              <details
                v-if="showAnswerMode || currentExam.status === 'submitted'"
                :key="`answer-review-${activeQuestion.questionId}`"
                class="answer-review leetcode-answer-review"
              >
                <summary>
                  <strong
                    :class="
                      currentExam.status === 'submitted' ? (activeQuestion.correct ? 'ok-text' : 'error') : 'ok-text'
                    "
                  >
                    {{ currentExam.status === 'submitted' ? (activeQuestion.correct ? '正确' : '待改进') : '参考答案' }}
                  </strong>
                  <span>查看参考答案</span>
                </summary>
                <PracticeMarkdown
                  :key="`answer-${activeQuestion.questionId}`"
                  :content="answerContent(activeQuestion.answer)"
                  :custom-id="`practice-answer-${activeQuestion.questionId}`"
                  empty-text="未维护参考答案"
                />
              </details>
            </div>
          </main>

          <section class="practice-answer-panel glass-card">
            <div class="practice-panel-head answer-head">
              <div>
                <p class="eyebrow">Answer</p>
                <h2>{{ isCodingQuestion(activeQuestion) ? '代码编辑器' : '作答区' }}</h2>
              </div>
              <span
                :class="[
                  'state-badge',
                  currentExam.status === 'submitted' ? 'ok' : timerRemaining <= 60 ? 'danger' : 'warn',
                ]"
              >
                {{ currentExam.status === 'submitted' ? `得分 ${currentExam.score}` : remainingTimeText }}
              </span>
            </div>

            <div v-if="isCodingQuestion(activeQuestion)" class="practice-code-panel leetcode-answer-editor">
              <div class="leetcode-editor-toolbar practice-editor-toolbar">
                <label
                  ><span>语言</span
                  ><select
                    :value="currentCodingLanguage(activeQuestion.questionId)"
                    :disabled="currentExam.status === 'submitted' || timerExpired"
                    @change="setCodingLanguage(activeQuestion.questionId, $event.target.value)"
                  >
                    <option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">
                      {{ item.label }}
                    </option>
                  </select></label
                >
                <button
                  type="button"
                  class="secondary-btn compact practice-copy-editor"
                  @click="copyPracticeCode(activeQuestion)"
                >
                  {{ codeCopyState[activeQuestion.questionId] || '复制代码' }}
                </button>
              </div>
              <textarea
                v-model="answers[activeQuestion.questionId]"
                :disabled="currentExam.status === 'submitted' || timerExpired"
                aria-label="编程题代码答案"
                placeholder="请在此编写完整代码答案"
                spellcheck="false"
                class="leetcode-code-editor practice-code-editor"
              />
              <div v-if="currentExam.status !== 'submitted' && !examLoading" class="leetcode-run-actions">
                <button
                  class="secondary-btn"
                  :disabled="codingRunning[activeQuestion.questionId]"
                  @click="runCodingSample(activeQuestion)"
                >
                  {{ codingRunning[activeQuestion.questionId] ? '运行中' : '运行样例' }}
                </button>
                <button
                  class="secondary-btn"
                  :class="{ active: codingDebugOpen[activeQuestion.questionId] }"
                  @click="toggleCodingDebug(activeQuestion)"
                >
                  自定义调试
                </button>
                <span>{{ codingResultSummary(activeQuestion.questionId) }}</span>
              </div>
              <div
                v-if="currentExam.status !== 'submitted' && !examLoading && codingDebugOpen[activeQuestion.questionId]"
                class="practice-debug-panel"
              >
                <div class="practice-debug-fields">
                  <label
                    ><span>参数 JSON</span
                    ><textarea
                      v-model="codingDebugForm(activeQuestion).argsText"
                      spellcheck="false"
                      placeholder="默认加载题目样例，可直接修改"
                    />
                  </label>
                  <label
                    ><span>期望结果 JSON（可选）</span
                    ><textarea
                      v-model="codingDebugForm(activeQuestion).expectedText"
                      spellcheck="false"
                      placeholder="默认加载样例输出，留空时仅查看实际输出"
                    />
                  </label>
                </div>
                <div class="practice-debug-actions">
                  <small
                    >{{
                      Number(codingMeta(activeQuestion).parameterCount || 0) === 1
                        ? '单参数题直接填写参数值。'
                        : '多参数题使用 JSON 数组按参数顺序填写。'
                    }}
                    默认值来自题目独立维护的测试用例，可修改；自定义调试不参与提交评分。</small
                  >
                  <button
                    class="primary-btn compact"
                    :disabled="codingRunning[activeQuestion.questionId] || currentExam.status === 'submitted'"
                    @click="runCodingDebug(activeQuestion)"
                  >
                    {{ codingRunning[activeQuestion.questionId] ? '运行中' : '运行调试' }}
                  </button>
                </div>
              </div>
              <div
                v-if="
                  currentExam.status !== 'submitted' &&
                  !examLoading &&
                  codingResultRows(activeQuestion.questionId).length
                "
                class="leetcode-result-list compact-code-results"
              >
                <article
                  v-for="result in codingResultRows(activeQuestion.questionId)"
                  :key="result.name"
                  :class="['leetcode-result-item', result.passed ? 'passed' : 'failed']"
                >
                  <div>
                    <strong>{{ result.name }}</strong
                    ><span>{{ result.passed ? ('expected' in result ? '通过' : '运行成功') : '未通过' }}</span>
                  </div>
                  <p>输入：{{ result.input }}</p>
                  <p v-if="'expected' in result">期望：{{ result.expected }}</p>
                  <p>实际：{{ result.actual }}</p>
                  <p v-if="result.error" class="error">错误：{{ result.error }}</p>
                </article>
              </div>
            </div>

            <div v-else-if="optionItems(activeQuestion).length" class="practice-choice-answer">
              <label
                v-for="option in optionItems(activeQuestion)"
                :key="option.key"
                :class="['exam-option', { selected: isOptionSelected(activeQuestion, option.key) }]"
              >
                <input
                  :type="isMultiChoice(activeQuestion) ? 'checkbox' : 'radio'"
                  :name="activeQuestion.questionId"
                  :value="option.key"
                  :checked="isOptionSelected(activeQuestion, option.key)"
                  :disabled="currentExam.status === 'submitted' || timerExpired"
                  @change="updateOptionAnswer(activeQuestion, option.key, $event.target.checked)"
                />
                <b>{{ option.key }}</b
                ><PracticeMarkdown
                  :content="option.text"
                  :custom-id="`practice-answer-option-${activeQuestion.questionId}-${option.key}`"
                  compact
                />
              </label>
            </div>

            <textarea
              v-else
              v-model="answers[activeQuestion.questionId]"
              :disabled="currentExam.status === 'submitted' || timerExpired"
              class="practice-text-answer"
              placeholder="请输入你的答案"
            />

            <div
              v-if="!examLoading && (currentExam.status !== 'submitted' || examTotalCount > 1)"
              class="practice-bottom-bar"
            >
              <button class="secondary-btn" :disabled="currentQuestionIndex <= 1" @click="goAdjacentQuestion(-1)">
                上一题
              </button>
              <button
                class="secondary-btn"
                :disabled="currentQuestionIndex >= examTotalCount"
                @click="goAdjacentQuestion(1)"
              >
                下一题
              </button>
              <span v-if="currentExam.status !== 'submitted'">还有 {{ unansweredQuestions.length }} 题未答</span>
              <button
                v-if="currentExam.status !== 'submitted'"
                class="primary-btn"
                :disabled="examLoading"
                @click="submitCurrentExam"
              >
                {{ timerExpired ? '时间到，提交得分' : '提交练习' }}
              </button>
            </div>
          </section>
        </div>
      </div>

      <section v-else class="glass-card practice-records-home">
        <header class="practice-records-home-header">
          <div>
            <p class="eyebrow">Practice History</p>
            <h2>练习记录</h2>
            <p>继续未完成练习，或查看已提交练习的得分与复盘。</p>
          </div>
          <button class="primary-btn practice-create-button" @click="requestComposePractice('smart')">创建练习</button>
        </header>

        <div
          v-if="recordsLoading"
          class="practice-records-loading"
          role="status"
          aria-live="polite"
          aria-label="正在加载练习记录"
        >
          <span class="practice-records-loading-spinner" aria-hidden="true"></span>
          <div>
            <strong>正在加载练习记录</strong>
            <p>请稍候，马上为你准备好。</p>
          </div>
        </div>

        <template v-else>
          <div class="practice-record-summary" aria-label="练习记录概览">
            <div>
              <span>全部练习</span>
              <strong>{{ exams.length }}</strong>
            </div>
            <div>
              <span>进行中</span>
              <strong>{{ inProgressExamCount }}</strong>
            </div>
            <div>
              <span>已完成</span>
              <strong>{{ submittedExamCount }}</strong>
            </div>
          </div>

          <div class="practice-records-home-toolbar">
            <label class="practice-record-search">
              <span>搜索练习记录</span>
              <input v-model.trim="recordKeyword" type="search" placeholder="搜索练习名称" />
            </label>
            <div class="practice-record-status-tabs" aria-label="按状态筛选练习" role="tablist">
              <button
                v-for="item in [
                  { value: 'all', label: '全部' },
                  { value: 'running', label: '进行中' },
                  { value: 'submitted', label: '已完成' },
                ]"
                :key="item.value"
                type="button"
                role="tab"
                :aria-selected="recordStatus === item.value"
                :class="{ active: recordStatus === item.value }"
                @click="recordStatus = item.value"
              >
                {{ item.label }}
              </button>
            </div>
          </div>

          <div v-if="recordsError" class="empty-state practice-records-home-state practice-records-error">
            <strong>练习记录加载失败</strong>
            <p>{{ recordsError }}</p>
            <button class="secondary-btn" @click="loadExams">重新加载</button>
          </div>
          <div v-else-if="filteredExams.length" class="practice-record-list">
            <article v-for="exam in filteredExams" :key="exam.examId" class="practice-record-row">
              <button type="button" class="practice-record-main" @click="requestOpenExam(exam.examId)">
                <span :class="['practice-record-status', exam.status === 'submitted' ? 'completed' : 'running']">{{
                  exam.status === 'submitted' ? '已完成' : '进行中'
                }}</span>
                <span class="practice-record-content">
                  <strong>{{ displayExamTitle(exam) }}</strong>
                  <small>
                    {{ formatExamStartedAt(exam.startedAt) }}
                    <span aria-hidden="true">·</span>
                    {{ exam.totalCount || exam.questionCount || 0 }} 题
                    <span aria-hidden="true">·</span>
                    {{ examShowAnswer(exam) ? '学习模式' : '考试模式' }}
                    <span aria-hidden="true">·</span>
                    {{ examCompositionLabel(exam) }}
                  </small>
                </span>
                <span class="practice-record-result">
                  <strong>{{ examRecordProgress(exam) }}</strong>
                  <small>{{ examRecordActionLabel(exam) }}</small>
                </span>
              </button>
              <div class="practice-record-actions">
                <button type="button" class="secondary-btn compact" @click="requestOpenExam(exam.examId)">
                  {{ examRecordActionLabel(exam) }}
                </button>
                <button
                  type="button"
                  class="danger-text"
                  :aria-label="`删除练习：${displayExamTitle(exam)}`"
                  @click="openExamDeleteDialog(exam)"
                >
                  删除
                </button>
              </div>
            </article>
          </div>
          <div v-else-if="exams.length" class="empty-state practice-records-home-state">
            <strong>没有符合条件的练习</strong>
            <p>尝试修改练习名称或切换记录状态。</p>
            <button class="secondary-btn" @click="resetRecordFilters">清除筛选</button>
          </div>
          <div v-else class="empty-state practice-records-home-state practice-records-empty">
            <span class="practice-records-empty-visual" aria-hidden="true">
              <svg
                viewBox="0 0 48 48"
                fill="none"
                stroke="currentColor"
                stroke-width="2.2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M14 8h16l6 6v25H14z" />
                <path d="M30 8v7h6M20 22h10M20 28h10" />
                <path d="m20 35 2.8 2.8L29 32" />
              </svg>
            </span>
            <div class="practice-records-empty-copy">
              <strong>开始第一套组卷练习</strong>
              <p>根据目标创建专属练习，完成后可随时继续作答或查看复盘。</p>
            </div>
            <button class="primary-btn" @click="requestComposePractice('smart')">创建第一套练习</button>
          </div>
        </template>
      </section>
    </section>

    <div v-if="examDeleteDialog.visible" class="modal-mask interview-delete-mask" @click.self="closeExamDeleteDialog">
      <div class="interview-delete-modal practice-record-delete-modal">
        <button class="close" aria-label="关闭删除练习弹窗" @click="closeExamDeleteDialog">×</button>
        <p class="eyebrow">删除练习记录</p>
        <h2>删除“{{ displayExamTitle(examDeleteDialog.exam) }}”？</h2>
        <p>练习题目、作答结果和复盘数据将一并删除，删除后无法恢复。</p>
        <p v-if="examDeleteDialog.error" class="error settings-error" role="alert">
          {{ examDeleteDialog.error }}
        </p>
        <div class="history-delete-actions">
          <button class="secondary-btn" :disabled="examDeleteDialog.loading" @click="closeExamDeleteDialog">
            取消
          </button>
          <button class="danger-btn" :disabled="examDeleteDialog.loading" @click="confirmExamDelete">
            {{ examDeleteDialog.loading ? '删除中' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="practiceDialog.visible" class="modal-mask interview-delete-mask" @click.self="closePracticeDialog">
      <div class="interview-delete-modal practice-confirm-modal">
        <button class="close" @click="closePracticeDialog">×</button>
        <p class="eyebrow">{{ practiceDialogEyebrow }}</p>
        <h2>{{ practiceDialogTitle }}</h2>
        <p>{{ practiceDialogDescription }}</p>
        <div class="history-delete-actions">
          <button class="secondary-btn" @click="closePracticeDialog">继续作答</button>
          <button
            :class="practiceDialog.mode === 'submit' ? 'danger-btn' : 'secondary-btn'"
            @click="confirmPracticeDialog"
          >
            {{ practiceDialogConfirmText }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="deleteDialog.visible" class="modal-mask interview-delete-mask" @click.self="closeDeleteDialog">
      <div class="interview-delete-modal">
        <button class="close" @click="closeDeleteDialog">×</button>
        <p class="eyebrow">删除题目</p>
        <h2>{{ deleteDialog.mode === 'batch' ? '批量删除题目？' : '删除这道题目？' }}</h2>
        <p>
          {{
            deleteDialog.mode === 'batch' ? `确认删除选中的 ${deleteDialog.count} 道笔试题？` : '确认删除这道笔试题？'
          }}
        </p>
        <div class="history-delete-actions">
          <button class="secondary-btn" :disabled="saving" @click="closeDeleteDialog">取消</button>
          <button class="danger-btn" :disabled="saving" @click="confirmDelete">
            {{ saving ? '删除中' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>

    <QuestionEditModal
      ref="editModalRef"
      :bank-type-options="bankTypeOptions"
      :question-type-options="questionTypes"
      @saved="handleQuestionSaved"
    />
  </section>
</template>

<script setup>
import { useInterviewBankPage } from '../composables/useInterviewBankPage'

const props = defineProps({
  mode: { type: String, default: 'bank' },
  embedded: { type: Boolean, default: false },
  initialExamId: { type: String, default: '' },
})

const emit = defineEmits(['practice-created', 'back-to-bank', 'compose-practice'])

const {
  activeMode,
  loading,
  saving,
  examLoading,
  error,
  selectedIds,
  filters,
  pagination,
  batchForm,
  showBatchEditor,
  deleteDialog,
  filteredQuestions,
  selectedSet,
  allCurrentPageSelected,
  visiblePages,
  goPage,
  changePageSize,
  rowNumber,
  toggleSelection,
  toggleCurrentPage,
  clearSelection,
  applyBatchDelete,
  removeQuestion,
  closeDeleteDialog,
  confirmDelete,
  bankTypeOptions,
  categories,
  difficulties,
  questionTypes,
  bankTypeLabel,
  timerRemaining,
  remainingTimeText,
  editModalRef,
  batchTagDraft,
  batchTagError,
  recordsLoading,
  recordsError,
  recordKeyword,
  recordStatus,
  exams,
  currentExam,
  practiceHomeVisible,
  answers,
  codingRunning,
  codingDebugOpen,
  codeCopyState,
  practiceDialog,
  examDeleteDialog,
  timerExpired,
  activeFilterCount,
  batchTags,
  addBatchTag,
  removeBatchTag,
  handleBatchTagKeydown,
  applyBatchChanges,
  pageEyebrow,
  pageTitle,
  pageDescription,
  showAnswerMode,
  isOpeningTargetExam,
  examTotalCount,
  answeredCount,
  unansweredQuestions,
  examProgressPercent,
  inProgressExamCount,
  submittedExamCount,
  filteredExams,
  activeQuestion,
  currentQuestionIndex,
  practiceDialogEyebrow,
  practiceDialogTitle,
  practiceDialogDescription,
  practiceDialogConfirmText,
  searchQuestions,
  resetFilters,
  switchBankTab,
  openCreateModal,
  openEditModal,
  handleQuestionSaved,
  startSelectedPractice,
  startSingleQuestionPractice,
  loadExams,
  examShowAnswer,
  examCompositionLabel,
  examRecordActionLabel,
  examRecordProgress,
  resetRecordFilters,
  returnToPracticeHome,
  requestComposePractice,
  requestOpenExam,
  openExamDeleteDialog,
  closeExamDeleteDialog,
  confirmExamDelete,
  currentCodingLanguage,
  setCodingLanguage,
  isQuestionAnswered,
  setActiveQuestion,
  goAdjacentQuestion,
  isOptionSelected,
  updateOptionAnswer,
  submitCurrentExam,
  closePracticeDialog,
  confirmPracticeDialog,
  codingDebugForm,
  toggleCodingDebug,
  runCodingSample,
  runCodingDebug,
  copyPracticeCode,
  codingResultRows,
  codingResultSummary,
  answerContent,
  codingLanguageOptions,
  difficultyClass,
  displayTitle,
  tagLabels,
  isCodingQuestion,
  codingMeta,
  isMultiChoice,
  optionItems,
  questionStem,
  displayExamTitle,
  formatExamStartedAt,
  InterviewBankHeader,
  PracticeMarkdown,
  QuestionEditModal,
} = useInterviewBankPage(props, emit)

defineExpose({
  showRecordsHome: returnToPracticeHome,
})
</script>
