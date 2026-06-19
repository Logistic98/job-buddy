<template>
  <section :class="embedded ? 'interview-embedded interview-manager-page' : 'system-page interview-page interview-manager-page'">
    <header v-if="!embedded" class="page-header">
      <div>
        <p class="eyebrow">{{ pageEyebrow }}</p>
        <h1>{{ pageTitle }}</h1>
        <p>{{ pageDescription }}</p>
      </div>
      <div class="history-header-actions">
        <template v-if="activeMode === 'bank'">
          <button class="primary-btn" @click="openCreateModal">新增题目</button>
          <button class="secondary-btn" :disabled="loading" @click="loadAll">刷新</button>
        </template>
        <template v-else>
          <button class="secondary-btn" @click="emit('back-to-bank')">返回题库</button>
          <button class="primary-btn" @click="openPracticeModal">随机组卷</button>
          <button class="secondary-btn" :disabled="recordsLoading" @click="loadExams">刷新记录</button>
        </template>
      </div>
    </header>
    <div v-else class="embedded-actions">
      <nav v-if="activeMode === 'bank'" class="bank-type-tabs embedded-bank-tabs" aria-label="题库切换">
        <button
          v-for="item in bankTypeOptions"
          :key="item.value"
          :class="{ active: filters.bankType === item.value }"
          @click="switchBankTab(item.value)"
        >
          {{ item.label }}
        </button>
      </nav>
      <div class="history-header-actions">
        <template v-if="activeMode === 'bank'">
          <button class="primary-btn" @click="openCreateModal">新增题目</button>
          <button class="secondary-btn" :disabled="loading" @click="loadAll">刷新</button>
        </template>
        <template v-else>
          <button class="secondary-btn" @click="emit('back-to-bank')">返回题库</button>
          <button class="primary-btn" @click="openPracticeModal">随机组卷</button>
          <button class="secondary-btn" :disabled="recordsLoading" @click="loadExams">刷新记录</button>
        </template>
      </div>
    </div>

    <p v-if="error" class="error settings-error">{{ error }}</p>

    <section v-if="activeMode === 'bank'" class="interview-bank-view glass-card">
      <div class="interview-filter-bar">
        <label class="history-search"><span>搜索</span><input v-model.trim="filters.keyword" placeholder="搜索标题、内容、答案或标签" @keyup.enter="loadQuestions" /></label>
        <select v-model="filters.category" @change="searchQuestions"><option value="">全部分类</option><option v-for="item in categories" :key="item" :value="item">{{ item }}</option></select>
        <select v-model="filters.difficulty" @change="searchQuestions"><option value="">全部难度</option><option v-for="item in difficulties" :key="item" :value="item">{{ item }}</option></select>
        <button class="secondary-btn" @click="searchQuestions">查询</button>
      </div>

      <div class="bank-summary-row">
        <span><strong>{{ pagination.total }}</strong> 题目</span>
        <span><strong>{{ categories.length }}</strong> 分类</span>
        <span v-if="selectedIds.length" class="bank-summary-selected"><strong>{{ selectedIds.length }}</strong> 已选</span>
      </div>

      <div v-if="selectedIds.length" class="selection-toolbar">
        <div class="selection-toolbar-main">
          <strong>已选 {{ selectedIds.length }} 题</strong>
          <button class="primary-btn" :disabled="examLoading" @click="startSelectedPractice">开始练习</button>
          <button class="secondary-btn" @click="showBatchEditor = !showBatchEditor">{{ showBatchEditor ? '收起批量设置' : '批量设置' }}</button>
          <button class="danger-btn" :disabled="saving" @click="applyBatchDelete">删除所选</button>
          <button class="secondary-btn" @click="clearSelection">取消选择</button>
        </div>
        <div v-if="showBatchEditor" class="selection-toolbar-editor">
          <input v-model.trim="batchForm.category" placeholder="分类，如 Java" />
          <select v-model="batchForm.difficulty"><option value="">难度不变</option><option>简单</option><option>中等</option><option>困难</option></select>
          <input v-model.trim="batchForm.tagsText" placeholder="标签，逗号分隔" />
          <button class="secondary-btn" :disabled="saving" @click="applyBatchUpdate">应用批量修改</button>
        </div>
      </div>

      <div class="interview-table-wrap">
        <div v-if="loading && !filteredQuestions.length" class="loading-state compact"><strong>题库加载中</strong><p>正在读取当前题库，请稍候。</p></div>
        <table v-else class="interview-table">
          <thead><tr><th class="select-col"><input type="checkbox" :checked="allCurrentPageSelected" @change="toggleCurrentPage($event.target.checked)" /></th><th class="index-col">序号</th><th>题目</th><th>分类</th><th>难度</th><th>题型</th><th>标签</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(item, index) in filteredQuestions" :key="item.questionId">
              <td class="select-col"><input type="checkbox" :checked="selectedSet.has(item.questionId)" @change="toggleSelection(item.questionId, $event.target.checked)" /></td>
              <td class="index-col"><span class="question-index">{{ rowNumber(index) }}</span></td>
              <td><strong>{{ displayTitle(item, index) }}</strong><p>{{ item.content }}</p></td>
              <td>{{ item.category || '通用' }}</td>
              <td><span class="state-badge warn">{{ item.difficulty || '中等' }}</span></td>
              <td>{{ item.questionType || '简答' }}</td>
              <td><div class="question-tags"><span v-for="tag in tagLabels(item).slice(0, 4)" :key="tag">{{ tag }}</span></div></td>
              <td><div class="table-actions"><button class="primary-text" @click="startSingleQuestionPractice(item)">单题练习</button><button class="secondary-btn" @click="openEditModal(item)">编辑</button><button class="danger-text" @click="removeQuestion(item.questionId)">删除</button></div></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!loading && !filteredQuestions.length" class="empty-state compact"><strong>暂无题目</strong><p>点击“新增题目”，可选择手动录入或 AI 生成。</p></div>
      </div>
      <div class="bank-pagination" v-if="pagination.total > 0">
        <span>第 {{ pagination.page }} / {{ pagination.pages || 1 }} 页，共 {{ pagination.total }} 题</span>
        <div>
          <select v-model.number="pagination.size" @change="changePageSize"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select>
          <button class="secondary-btn" :disabled="pagination.page <= 1" @click="goPage(pagination.page - 1)">上一页</button>
          <button v-for="page in visiblePages" :key="page" :class="['page-num-btn', { active: page === pagination.page }]" @click="goPage(page)">{{ page }}</button>
          <button class="secondary-btn" :disabled="pagination.page >= pagination.pages" @click="goPage(pagination.page + 1)">下一页</button>
        </div>
      </div>
    </section>

    <section v-else class="practice-desk-view">
      <div v-if="currentExam && activeQuestion" class="practice-leetcode-shell">
        <aside class="practice-left-panel glass-card">
          <div class="practice-left-top">
            <button class="secondary-btn compact" @click="emit('back-to-bank')">返回题库</button>
            <button class="primary-btn compact" @click="openPracticeModal">随机组卷</button>
          </div>
          <div class="practice-progress-card">
            <div>
              <strong>{{ answeredCount }}</strong>
              <span>/ {{ examTotalCount }} 已完成</span>
            </div>
            <div class="exam-progress-bar" aria-label="作答进度"><span :style="{ width: `${examProgressPercent}%` }"></span></div>
            <small v-if="currentExam.status !== 'submitted'">剩余 {{ remainingTimeText }}</small>
            <small v-else>得分 {{ currentExam.score }}</small>
          </div>
          <div class="practice-question-list" aria-label="题目列表">
            <button
              v-for="(item, index) in currentExam.questions || []"
              :key="item.questionId"
              type="button"
              :class="['practice-question-pill', { active: item.questionId === activeQuestion.questionId, answered: isQuestionAnswered(item) }]"
              @click="setActiveQuestion(item.questionId)"
            >
              <b>{{ index + 1 }}</b>
              <span>{{ item.title }}</span>
              <em>{{ isQuestionAnswered(item) ? '已答' : '未答' }}</em>
            </button>
          </div>
        </aside>

        <main class="practice-problem-panel glass-card">
          <div class="practice-panel-head">
            <div>
              <p class="eyebrow">{{ bankTypeLabel(activeQuestion.bankType) }}</p>
              <h2>{{ currentQuestionIndex }}. {{ activeQuestion.title }}</h2>
            </div>
            <span :class="['state-badge', difficultyClass(activeQuestion.difficulty)]">{{ activeQuestion.difficulty || '中等' }}</span>
          </div>
          <div class="practice-problem-body">
            <p class="practice-stem">{{ questionStem(activeQuestion) }}</p>
            <div class="question-tags">
              <span v-for="tag in tagLabels(activeQuestion).slice(0, 6)" :key="tag">{{ tag }}</span>
            </div>
            <section v-if="optionItems(activeQuestion).length" class="practice-description-block">
              <h3>选项</h3>
              <div class="exam-option-list readonly-options">
                <label v-for="option in optionItems(activeQuestion)" :key="option.key" class="exam-option readonly">
                  <b>{{ option.key }}</b><span>{{ option.text }}</span>
                </label>
              </div>
            </section>
            <section v-if="showAnswerMode || currentExam.status === 'submitted'" class="answer-review leetcode-answer-review">
              <strong :class="currentExam.status === 'submitted' ? (activeQuestion.correct ? 'ok-text' : 'error') : 'ok-text'">
                {{ currentExam.status === 'submitted' ? (activeQuestion.correct ? '正确' : '待改进') : '参考答案' }}
              </strong>
              <p>{{ activeQuestion.answer || '未维护参考答案' }}</p>
            </section>
          </div>
        </main>

        <section class="practice-answer-panel glass-card">
          <div class="practice-panel-head answer-head">
            <div>
              <p class="eyebrow">Answer</p>
              <h2>{{ isCodingQuestion(activeQuestion) ? '代码编辑器' : '作答区' }}</h2>
            </div>
            <span :class="['state-badge', currentExam.status === 'submitted' ? 'ok' : (timerRemaining <= 60 ? 'danger' : 'warn')]">
              {{ currentExam.status === 'submitted' ? `得分 ${currentExam.score}` : remainingTimeText }}
            </span>
          </div>

          <div v-if="isCodingQuestion(activeQuestion)" class="practice-code-panel leetcode-answer-editor">
            <div class="leetcode-editor-toolbar">
              <label><span>语言</span><select :value="currentCodingLanguage(activeQuestion.questionId)" :disabled="currentExam.status === 'submitted' || timerExpired" @change="setCodingLanguage(activeQuestion.questionId, $event.target.value)"><option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
              <span>{{ codingSignature(activeQuestion) }}</span>
            </div>
            <textarea v-model="answers[activeQuestion.questionId]" :disabled="currentExam.status === 'submitted' || timerExpired" spellcheck="false" class="leetcode-code-editor practice-code-editor" />
            <div class="leetcode-run-actions">
              <button class="secondary-btn" :disabled="codingRunning[activeQuestion.questionId] || currentExam.status === 'submitted'" @click="runCodingSample(activeQuestion)">{{ codingRunning[activeQuestion.questionId] ? '运行中' : '运行样例' }}</button>
              <span>{{ codingResultSummary(activeQuestion.questionId) }}</span>
            </div>
            <div v-if="codingResultRows(activeQuestion.questionId).length" class="leetcode-result-list compact-code-results">
              <article v-for="result in codingResultRows(activeQuestion.questionId)" :key="result.name" :class="['leetcode-result-item', result.passed ? 'passed' : 'failed']">
                <div><strong>{{ result.name }}</strong><span>{{ result.passed ? '通过' : '未通过' }}</span></div>
                <p>输入：{{ result.input }}</p><p>期望：{{ result.expected }}</p><p>实际：{{ result.actual }}</p>
                <p v-if="result.error" class="error">错误：{{ result.error }}</p>
              </article>
            </div>
          </div>

          <div v-else-if="optionItems(activeQuestion).length" class="practice-choice-answer">
            <label v-for="option in optionItems(activeQuestion)" :key="option.key" :class="['exam-option', { selected: isOptionSelected(activeQuestion, option.key) }]">
              <input :type="isMultiChoice(activeQuestion) ? 'checkbox' : 'radio'" :name="activeQuestion.questionId" :value="option.key" :checked="isOptionSelected(activeQuestion, option.key)" :disabled="currentExam.status === 'submitted' || timerExpired" @change="updateOptionAnswer(activeQuestion, option.key, $event.target.checked)" />
              <b>{{ option.key }}</b><span>{{ option.text }}</span>
            </label>
          </div>

          <textarea v-else v-model="answers[activeQuestion.questionId]" :disabled="currentExam.status === 'submitted' || timerExpired" class="practice-text-answer" placeholder="请输入你的答案" />

          <div class="practice-bottom-bar">
            <button class="secondary-btn" :disabled="currentQuestionIndex <= 1" @click="goAdjacentQuestion(-1)">上一题</button>
            <button class="secondary-btn" :disabled="currentQuestionIndex >= examTotalCount" @click="goAdjacentQuestion(1)">下一题</button>
            <span v-if="currentExam.status !== 'submitted'">还有 {{ unansweredQuestions.length }} 题未答</span>
            <button v-if="currentExam.status !== 'submitted'" class="primary-btn" :disabled="examLoading" @click="submitCurrentExam">{{ timerExpired ? '时间到，提交得分' : '提交练习' }}</button>
          </div>
        </section>
      </div>

      <div v-else class="practice-start-grid">
        <section class="glass-card practice-start-card">
          <div v-if="recordsLoading || examDetailLoading" class="loading-state compact"><strong>练习数据加载中</strong><p>正在读取练习记录。</p></div>
          <div v-else class="empty-state compact">
            <strong>选择一种练习方式</strong>
            <p>从题库点“单题练习”，或在这里随机组卷。</p>
            <div class="history-header-actions center-actions">
              <button class="secondary-btn" @click="emit('back-to-bank')">返回题库</button>
              <button class="primary-btn" @click="openPracticeModal">随机组卷</button>
            </div>
          </div>
        </section>
        <aside class="glass-card exam-record-card practice-records-clean">
          <div class="card-title"><h2>练习记录</h2><span>{{ exams.length }} 次</span></div>
          <div v-if="recordsLoading" class="loading-state compact"><strong>记录加载中</strong><p>正在同步最新练习记录。</p></div>
          <template v-else>
            <button v-for="exam in exams" :key="exam.examId" class="exam-record" @click="openExam(exam.examId)">
              <span>{{ displayExamTitle(exam) }}<em v-if="examShowAnswer(exam)" class="exam-mode-tag">学习模式</em></span><b>{{ exam.status === 'submitted' ? `${exam.score} 分` : '进行中' }}</b>
            </button>
          </template>
          <div v-if="!recordsLoading && !exams.length" class="empty-state compact"><strong>暂无记录</strong><p>完成练习后会在这里保存得分。</p></div>
        </aside>
      </div>
    </section>

    <div v-if="showPracticeModal" class="modal-mask" @click.self="closePracticeModal">
      <div class="modal-card interview-modal-card practice-create-modal">
        <button class="close" @click="closePracticeModal">×</button>
        <div class="practice-modal-head"><h2>随机组卷</h2><p>设置练习名称、限时和练习模式，再按题库、分类、难度、题型组合抽题。</p></div>

        <div class="practice-form">
          <div class="practice-section">
            <label class="practice-field">
              <span class="practice-field-label">练习名称</span>
              <input v-model="examConfig.title" placeholder="例如：Java 后端算法与理论组合练习" />
              <small class="field-hint">建议写清方向和题型组合，便于练习记录复盘。</small>
            </label>
            <div class="practice-field">
              <span class="practice-field-label">限时时长</span>
              <div class="practice-duration">
                <button
                  v-for="min in durationPresets"
                  :key="min"
                  type="button"
                  :class="['practice-chip', { active: examConfig.durationMinutes === min }]"
                  @click="examConfig.durationMinutes = min"
                >
                  {{ min }} 分钟
                </button>
                <label class="practice-duration-custom">
                  <input v-model.number="examConfig.durationMinutes" type="number" min="1" max="240" />
                  <span>分钟</span>
                </label>
                <small class="field-hint inline">限时范围 1-240 分钟。</small>
              </div>
            </div>
          </div>

          <div class="practice-section">
            <span class="practice-field-label">练习模式</span>
            <div class="practice-mode-cards">
              <label :class="['practice-mode-card', { active: examConfig.answerMode === 'hidden' }]">
                <input type="radio" value="hidden" v-model="examConfig.answerMode" />
                <b>考试模式</b>
                <small>先独立作答，提交后再看参考答案</small>
              </label>
              <label :class="['practice-mode-card', { active: examConfig.answerMode === 'visible' }]">
                <input type="radio" value="visible" v-model="examConfig.answerMode" />
                <b>学习模式</b>
                <small>练习中展示参考答案，适合快速巩固</small>
              </label>
            </div>
          </div>

          <div class="practice-section">
            <div class="practice-section-head">
              <span class="practice-field-label">组卷规则</span>
              <span class="practice-total-pill">共 {{ examRuleTotal }} 题</span>
            </div>
            <div class="practice-rule-table">
              <div class="practice-rule-head">
                <span>题库</span><span>分类</span><span>难度</span><span>题型</span><span>题数</span><span></span>
              </div>
              <div v-for="(rule, index) in examConfig.rules" :key="rule.id" class="practice-rule-row">
                <select v-model="rule.bankType"><option value="">全部题库</option><option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select>
                <select v-model="rule.category"><option value="">全部分类</option><option v-for="item in categories" :key="item" :value="item">{{ item }}</option></select>
                <select v-model="rule.difficulty"><option value="">全部难度</option><option v-for="item in difficulties" :key="item" :value="item">{{ item }}</option></select>
                <select v-model="rule.questionType"><option value="">全部题型</option><option v-for="item in questionTypes" :key="item" :value="item">{{ item }}</option></select>
                <input v-model.number="rule.count" type="number" min="1" max="50" />
                <button type="button" class="practice-rule-remove" :disabled="examConfig.rules.length <= 1" title="删除该组合" @click="removeExamRule(index)">×</button>
              </div>
            </div>
            <p class="section-hint">可参考主流题单和套卷的设计方式，按题库、分类、难度、题型组合抽题；留空表示不限制。</p>
            <button type="button" class="practice-add-rule" @click="addExamRule">+ 添加规则</button>
          </div>
        </div>

        <p v-if="practiceModalError" class="error settings-error">{{ practiceModalError }}</p>
        <div class="modal-actions practice-modal-actions">
          <button class="secondary-btn" @click="closePracticeModal">取消</button>
          <button class="primary-btn" :disabled="examLoading || !examRuleTotal" @click="startExam">{{ examLoading ? '组卷中…' : `开始练习（${examRuleTotal} 题）` }}</button>
        </div>
      </div>
    </div>

    <div v-if="deleteDialog.visible" class="modal-mask interview-delete-mask" @click.self="closeDeleteDialog">
      <div class="interview-delete-modal">
        <button class="close" @click="closeDeleteDialog">×</button>
        <p class="eyebrow">删除题目</p>
        <h2>{{ deleteDialog.mode === 'batch' ? '批量删除题目？' : '删除这道题目？' }}</h2>
        <p>{{ deleteDialog.mode === 'batch' ? `确认删除选中的 ${deleteDialog.count} 道笔试题？` : '确认删除这道笔试题？' }}</p>
        <div class="history-delete-actions">
          <button class="secondary-btn" :disabled="saving" @click="closeDeleteDialog">取消</button>
          <button class="danger-btn" :disabled="saving" @click="confirmDelete">{{ saving ? '删除中' : '确认删除' }}</button>
        </div>
      </div>
    </div>

    <div v-if="showModal" class="modal-mask" @click.self="showModal = false">
      <div class="modal-card interview-modal-card practice-create-modal maintain-modal">
        <button class="close" @click="showModal = false">×</button>
        <div class="practice-modal-head"><h2>{{ modalTitle }}</h2><p>维护算法题和问答题，可手动录入，也可上传资料后由 AI 辅助生成。</p></div>
        <div class="interview-modal-tabs">
          <button :class="{ active: modalMode === 'manual' }" @click="modalMode = 'manual'">手动录入</button>
          <button :class="{ active: modalMode === 'ai' }" @click="modalMode = 'ai'">AI 生成</button>
        </div>

        <div v-if="modalMode === 'manual'" class="practice-form">
          <div class="practice-section">
            <span class="practice-field-label">基本信息</span>
            <div class="maintain-field-grid">
              <label class="practice-field wide"><span class="practice-field-label">标题</span><input v-model="form.title" placeholder="例如：Java HashMap 扩容机制" /><small class="field-hint">标题用于列表检索和练习展示，建议保持短句且明确知识点。</small></label>
              <label class="practice-field"><span class="practice-field-label">题库</span><select v-model="form.bankType" @change="syncFormBankType"><option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
              <label class="practice-field"><span class="practice-field-label">分类</span><input v-model="form.category" placeholder="Java / Spring / MySQL / 数组" /></label>
              <label class="practice-field"><span class="practice-field-label">难度</span><select v-model="form.difficulty"><option>简单</option><option>中等</option><option>困难</option></select></label>
              <label v-if="form.bankType !== 'leetcode'" class="practice-field"><span class="practice-field-label">题型</span><select v-model="form.questionType"><option v-for="item in formQuestionTypes" :key="item" :value="item">{{ item }}</option></select></label>
              <label class="practice-field"><span class="practice-field-label">标签</span><input v-model="form.tagsText" placeholder="逗号分隔，如 Java,集合" /><small class="field-hint">用于后续筛选与组卷，可填写多个。</small></label>
            </div>
          </div>

          <div class="practice-section">
            <span class="practice-field-label">{{ isChoiceForm ? '题干与选项' : '题目内容' }}</span>
            <label class="practice-field"><span class="practice-field-label">{{ isChoiceForm ? '题干' : '内容' }}</span><textarea v-model="form.content" :placeholder="isChoiceForm ? '请输入选择题题干，选项在下方填写' : '请输入笔试题内容'" /><small class="field-hint">题干只写问题本身，选择项在下方独立维护，避免重复解析出错。</small></label>
            <div v-if="isChoiceForm" class="choice-option-editor">
              <div class="choice-option-head"><span>选项</span><button type="button" class="secondary-btn" @click="addOption">新增选项</button></div>
              <label v-for="(option, index) in form.options" :key="option.key" class="choice-option-row">
                <b>{{ option.key }}</b>
                <input v-model="option.text" :placeholder="`选项 ${option.key}`" />
                <button type="button" class="danger-text" :disabled="form.options.length <= 2" @click="removeOption(index)">删除</button>
              </label>
              <label class="practice-field"><span class="practice-field-label">正确答案</span><input v-model="form.answer" :placeholder="form.questionType === '多选' ? '例如：A,C' : '例如：A'" /></label>
            </div>
            <div v-else-if="form.bankType === 'leetcode'" class="coding-meta-editor">
              <label class="practice-field"><span class="practice-field-label">默认语言</span><select v-model="form.codingLanguage" @change="resetCodingTemplateForLanguage"><option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
              <label class="practice-field wide"><span class="practice-field-label">初始代码模板</span><textarea v-model="form.codingTemplate" :placeholder="buildDefaultTemplate('solution', form.codingLanguage)" /></label>
              <label class="practice-field wide"><span class="practice-field-label">测试用例 JSON</span><textarea v-model="form.codingTestsText" placeholder="[{&quot;name&quot;:&quot;示例&quot;,&quot;args&quot;:[[2,7],9],&quot;expected&quot;:[0,1],&quot;sample&quot;:true}]" /><small class="field-hint">每条用例需包含 name、args、expected；sample=true 会作为练习中的样例运行。</small></label>
              <label class="practice-field wide"><span class="practice-field-label">参考答案 / 判分说明</span><textarea v-model="form.answer" placeholder="以测试用例通过情况作为主要评分依据" /></label>
            </div>
            <label v-else class="practice-field"><span class="practice-field-label">参考答案 / 判分关键词</span><textarea v-model="form.answer" placeholder="练习提交时会用参考答案做简单自动判分" /></label>
          </div>
        </div>

        <div v-else class="practice-form ai-generate-panel">
          <div class="practice-section">
            <span class="practice-field-label">生成设置</span>
            <div class="maintain-field-grid">
              <label class="practice-field wide"><span class="practice-field-label">方向 / 主题</span><input v-model="aiForm.topic" placeholder="例如：Java 后端、Agent 工程" /></label>
              <label class="practice-field"><span class="practice-field-label">题库</span><select v-model="aiForm.bankType" @change="syncAiBankType"><option v-for="item in bankTypeOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
              <label class="practice-field"><span class="practice-field-label">分类</span><input v-model="aiForm.category" placeholder="Java" /></label>
              <label class="practice-field"><span class="practice-field-label">难度</span><select v-model="aiForm.difficulty"><option>简单</option><option>中等</option><option>困难</option></select></label>
              <label v-if="aiForm.bankType !== 'leetcode'" class="practice-field"><span class="practice-field-label">题型</span><select v-model="aiForm.questionType"><option v-for="item in aiQuestionTypes" :key="item" :value="item">{{ item }}</option></select></label>
              <label class="practice-field"><span class="practice-field-label">数量</span><input v-model.number="aiForm.count" type="number" min="1" max="20" /></label>
            </div>
          </div>

          <div class="practice-section">
            <span class="practice-field-label">参考资料</span>
            <div class="doc-upload-field">
              <label class="doc-upload-box">
                <input type="file" accept=".txt,.md,.markdown,.json,.csv" @change="handleAiDocumentChange" />
                <b>选择文档</b>
                <small>{{ aiForm.documentName || '支持 TXT / MD / JSON / CSV，上传后自动填入下方资料区' }}</small>
              </label>
              <button v-if="aiForm.documentName" type="button" class="doc-clear-btn" @click="clearAiDocument">清除文档</button>
            </div>
            <label class="practice-field"><span class="practice-field-label">文档内容 / 补充资料</span><textarea v-model="aiForm.documentText" placeholder="可上传文档自动填入，也可以粘贴岗位 JD、技术文档、知识点材料" /></label>
            <label class="practice-field"><span class="practice-field-label">补充要求</span><textarea v-model="aiForm.requirements" placeholder="例如：偏工程实践，包含性能优化、排障、系统设计" /></label>
          </div>
        </div>

        <p v-if="modalError" class="error settings-error">{{ modalError }}</p>
        <div class="modal-actions practice-modal-actions">
          <button class="secondary-btn" @click="showModal = false">取消</button>
          <button class="primary-btn" :disabled="saving" @click="submitModal">{{ saving ? '处理中' : modalSubmitText }}</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { batchQuestions, createQuestion, createRandomExam, deleteQuestion, generateQuestions, getExam, getQuestionMeta, listExams, listQuestions, runCodeSample, submitExam, updateQuestion } from '../api/interview'
import { buildDefaultTemplate, defaultOptions, defaultSignature, difficultyClass, displayTitle, extractFunctionName, formatCodingTests, formatRemainingTime, isChoiceType, isCodingQuestion, isMultiChoice, normalizeCodingLanguage, optionItems, questionStem, requireText, splitCleanTags, tagLabels } from '../utils/interviewBank'

const props = defineProps({
  mode: { type: String, default: 'bank' },
  embedded: { type: Boolean, default: false },
  initialExamId: { type: String, default: '' },
})

const emit = defineEmits(['practice-created', 'back-to-bank'])

const activeMode = computed(() => props.mode === 'exam' ? 'exam' : 'bank')
const loading = ref(false)
const saving = ref(false)
const examLoading = ref(false)
const recordsLoading = ref(false)
const examDetailLoading = ref(false)
const error = ref('')
const modalError = ref('')
const questions = ref([])
const selectedIds = ref([])
const exams = ref([])
const currentExam = ref(null)
const activeQuestionId = ref('')
const answers = reactive({})
const codingResults = reactive({})
const codingRunning = reactive({})
const codingLanguageByQuestion = reactive({})
const questionMeta = reactive({ bankTypeOptions: [], categories: [], difficulties: [], questionTypes: [] })
const filters = reactive({ keyword: '', bankType: props.mode === 'bank' ? 'leetcode' : '', category: '', difficulty: '' })
const pagination = reactive({ page: 1, size: 20, total: 0, pages: 1 })
const batchForm = reactive({ category: '', difficulty: '', tagsText: '' })
const showBatchEditor = ref(false)
const examConfig = reactive({ title: '', durationMinutes: 30, answerMode: 'hidden', rules: [newExamRule()] })
const durationPresets = [15, 30, 45, 60, 90]
const showPracticeModal = ref(false)
const practiceModalError = ref('')
const showModal = ref(false)
const modalMode = ref('manual')
const editingId = ref('')
const form = reactive({ title: '', bankType: 'baguwen', category: 'Java', difficulty: '中等', questionType: '单选', tagsText: '', content: '', answer: '', options: defaultOptions(), codingLanguage: 'python', codingFunctionName: '', codingSignature: '', codingTemplate: '', codingTestsText: '' })
const aiForm = reactive({ topic: 'Java 后端', bankType: 'baguwen', category: 'Java', difficulty: '中等', questionType: '单选', count: 5, requirements: '', documentName: '', documentText: '' })
const deleteDialog = reactive({ visible: false, mode: 'single', questionId: '', count: 0 })

const codingLanguageOptions = [
  { value: 'python', label: 'Python' },
  { value: 'java', label: 'Java' },
  { value: 'javascript', label: 'JavaScript' },
]
const bankTypeDisplayName = { leetcode: '算法题库', baguwen: '问答题库' }
const bankTypeOptions = computed(() => {
  const options = questionMeta.bankTypeOptions?.length ? questionMeta.bankTypeOptions : [
    { value: 'leetcode', label: bankTypeDisplayName.leetcode },
    { value: 'baguwen', label: bankTypeDisplayName.baguwen },
  ]
  return options.map(item => ({ ...item, label: bankTypeDisplayName[item.value] || item.label }))
})
const categories = computed(() => (questionMeta.categories?.length ? questionMeta.categories : Array.from(new Set(questions.value.map(item => item.category).filter(Boolean)))).sort())
const difficulties = computed(() => (questionMeta.difficulties?.length ? questionMeta.difficulties : ['简单', '中等', '困难']))
const questionTypes = computed(() => (questionMeta.questionTypes?.length ? questionMeta.questionTypes : ['编程题', '单选', '多选', '判断', '简答']))
const formQuestionTypes = computed(() => form.bankType === 'leetcode' ? ['编程题'] : ['单选', '多选', '判断', '简答'])
const aiQuestionTypes = computed(() => aiForm.bankType === 'leetcode' ? ['编程题'] : ['单选', '多选', '判断', '简答'])
const examRuleTotal = computed(() => examConfig.rules.reduce((sum, rule) => sum + Math.max(0, Number(rule.count || 0)), 0))
const timerExpired = computed(() => Boolean(currentExam.value && currentExam.value.status !== 'submitted' && timerRemaining.value <= 0))
const remainingTimeText = computed(() => formatRemainingTime(timerRemaining.value))
const timerRemaining = ref(0)
let timerId = null
const filteredQuestions = computed(() => questions.value)
const selectedSet = computed(() => new Set(selectedIds.value))
const allCurrentPageSelected = computed(() => questions.value.length > 0 && questions.value.every(item => selectedSet.value.has(item.questionId)))
const visiblePages = computed(() => {
  const total = Math.max(1, pagination.pages || 1)
  const current = Math.min(Math.max(1, pagination.page), total)
  const start = Math.max(1, Math.min(current - 2, total - 4))
  const end = Math.min(total, start + 4)
  const pages = []
  for (let page = start; page <= end; page++) pages.push(page)
  return pages
})
const pageEyebrow = computed(() => activeMode.value === 'exam' ? 'Practice Desk' : 'Question Bank')
const pageTitle = computed(() => activeMode.value === 'exam' ? '练习台' : '题库')
const pageDescription = computed(() => activeMode.value === 'exam'
  ? '查看练习记录、继续作答或通过随机组卷开始新练习。'
  : '按题库维护题目，支持单题练习、勾选后练习和批量设置。')
const modalTitle = computed(() => editingId.value ? '编辑题目' : '新增题目')
const modalSubmitText = computed(() => modalMode.value === 'manual' ? (editingId.value ? '保存修改' : '保存题目') : '生成并入库')
const isChoiceForm = computed(() => isChoiceType(form.questionType))
const showAnswerMode = computed(() => Boolean(currentExam.value?.strategy?.showAnswer))
const examTotalCount = computed(() => currentExam.value?.questions?.length || 0)
const answeredCount = computed(() => (currentExam.value?.questions || []).filter(isQuestionAnswered).length)
const unansweredQuestions = computed(() => (currentExam.value?.questions || []).filter(item => !isQuestionAnswered(item)))
const examProgressPercent = computed(() => examTotalCount.value ? Math.round((answeredCount.value / examTotalCount.value) * 100) : 0)
const currentQuestionIndexMap = computed(() => Object.fromEntries((currentExam.value?.questions || []).map((item, index) => [item.questionId, index + 1])))
const activeQuestion = computed(() => {
  const list = currentExam.value?.questions || []
  return list.find(item => item.questionId === activeQuestionId.value) || list[0] || null
})
const currentQuestionIndex = computed(() => activeQuestion.value ? (currentQuestionIndexMap.value[activeQuestion.value.questionId] || 1) : 0)

onMounted(() => {
  loadAll()
  document.addEventListener('keydown', handleGlobalKeydown)
})
onBeforeUnmount(() => {
  stopExamTimer()
  document.removeEventListener('keydown', handleGlobalKeydown)
})

watch(() => props.initialExamId, async examId => {
  if (activeMode.value !== 'exam' || !examId) return
  await loadExams()
  await openExam(examId)
}, { immediate: true })

async function loadAll() {
  if (activeMode.value === 'exam') await Promise.all([loadQuestionMeta(), loadExams()])
  else await Promise.all([loadQuestionMeta(), loadQuestions()])
}
async function loadQuestionMeta() {
  try {
    const data = await getQuestionMeta({ bankType: filters.bankType, _ts: Date.now() })
    Object.assign(questionMeta, {
      bankTypeOptions: Array.isArray(data.bankTypeOptions) ? data.bankTypeOptions : questionMeta.bankTypeOptions,
      categories: Array.isArray(data.categories) ? data.categories : [],
      difficulties: Array.isArray(data.difficulties) ? data.difficulties : [],
      questionTypes: Array.isArray(data.questionTypes) ? data.questionTypes : [],
    })
  } catch (_) {}
}
async function loadQuestions() {
  loading.value = true
  error.value = ''
  try {
    const data = await listQuestions({ ...filters, page: pagination.page, size: pagination.size, _ts: Date.now() })
    questions.value = (data.items || []).map(normalizeQuestionRow)
    pagination.total = Number(data.total || questions.value.length || 0)
    pagination.page = Number(data.page || pagination.page || 1)
    pagination.size = Number(data.size || pagination.size || 20)
    pagination.pages = Math.max(1, Number(data.pages || Math.ceil(pagination.total / pagination.size) || 1))
  } catch (err) { error.value = err.message || '题库加载失败' } finally { loading.value = false }
}
async function searchQuestions() {
  pagination.page = 1
  clearSelection()
  questions.value = []
  return Promise.all([loadQuestionMeta(), loadQuestions()])
}
function switchBankTab(value) {
  if (filters.bankType === value) return
  filters.bankType = value
  filters.category = ''
  return searchQuestions()
}
function goPage(page) {
  pagination.page = Math.max(1, Math.min(page, pagination.pages || 1))
  questions.value = []
  return loadQuestions()
}
function changePageSize() { pagination.page = 1; clearSelection(); questions.value = []; return loadQuestions() }
function normalizeQuestionRow(item) {
  if (!item || typeof item !== 'object') return item
  const bankType = item.bankType || (item.questionType === '编程题' ? 'leetcode' : 'baguwen')
  return { ...item, bankType, tags: tagLabels(item).map(label => ({ label })), codingMeta: item.codingMeta || {} }
}
function rowNumber(index) { return (pagination.page - 1) * pagination.size + index + 1 }
function toggleSelection(questionId, checked) {
  const set = new Set(selectedIds.value)
  checked ? set.add(questionId) : set.delete(questionId)
  selectedIds.value = Array.from(set)
}
function toggleCurrentPage(checked) {
  const set = new Set(selectedIds.value)
  for (const item of questions.value) checked ? set.add(item.questionId) : set.delete(item.questionId)
  selectedIds.value = Array.from(set)
}
function clearSelection() {
  selectedIds.value = []
  showBatchEditor.value = false
}
async function applyBatchUpdate() {
  if (!selectedIds.value.length) return
  saving.value = true
  error.value = ''
  try {
    await batchQuestions({
      action: 'update',
      questionIds: selectedIds.value,
      category: batchForm.category,
      difficulty: batchForm.difficulty,
      tags: splitCleanTags(batchForm.tagsText),
    })
    Object.assign(batchForm, { category: '', difficulty: '', tagsText: '' })
    clearSelection()
    await loadQuestions()
  } catch (err) { error.value = err.message || '批量修改失败' } finally { saving.value = false }
}
function applyBatchDelete() {
  if (!selectedIds.value.length) return
  Object.assign(deleteDialog, { visible: true, mode: 'batch', questionId: '', count: selectedIds.value.length })
}
async function startSelectedPractice() {
  if (!selectedIds.value.length || examLoading.value) return
  const selectedQuestions = questions.value.filter(item => selectedSet.value.has(item.questionId))
  const title = `${currentBankTypeLabel()} 所选题练习（${selectedIds.value.length} 题）`
  await createManualPractice(selectedIds.value, title, selectedQuestions.length === 1)
}
async function startSingleQuestionPractice(item) {
  if (!item?.questionId || examLoading.value) return
  await createManualPractice([item.questionId], `${displayTitle(item, 0)} 单题练习`, true)
}
async function createManualPractice(questionIds, title, showAnswer = true) {
  examLoading.value = true
  error.value = ''
  try {
    const exam = await createRandomExam({
      title,
      durationMinutes: questionIds.length > 1 ? 45 : 30,
      showAnswer,
      questionIds,
    })
    assertManualPracticeMatches(exam, questionIds)
    clearSelection()
    emit('practice-created', exam)
  } catch (err) {
    error.value = err.message || '创建练习失败'
  } finally {
    examLoading.value = false
  }
}
function assertManualPracticeMatches(exam, questionIds) {
  const expected = Array.from(new Set(questionIds.map(id => String(id || '').trim()).filter(Boolean))).sort()
  const actual = (exam?.questions || []).map(item => String(item.questionId || '').trim()).filter(Boolean).sort()
  const same = expected.length === actual.length && expected.every((id, index) => id === actual[index])
  if (!same) throw new Error('练习内容与所选题目不一致，请刷新题库后重试')
}
function currentBankTypeLabel() { return bankTypeLabel(filters.bankType) || '题库' }
function displayExamTitle(exam) {
  return String(exam?.title || '未命名练习')
    .replace(/模拟练习/g, '随机组卷')
    .replace(/LeetCode/gi, '算法')
}
async function loadExams() {
  recordsLoading.value = true
  try {
    exams.value = await listExams()
  } catch (_) {
    exams.value = []
  } finally {
    recordsLoading.value = false
  }
}
function examShowAnswer(exam) { return Boolean(exam?.strategy?.showAnswer) }
function openPracticeModal() {
  practiceModalError.value = ''
  if (!examConfig.title.trim()) examConfig.title = defaultPracticeTitle()
  showPracticeModal.value = true
}
function defaultPracticeTitle() {
  const now = new Date()
  const pad = value => String(value).padStart(2, '0')
  return `随机组卷 ${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`
}
function closePracticeModal() {
  if (examLoading.value) return
  showPracticeModal.value = false
}
function newExamRule(data = {}) { return { id: crypto.randomUUID(), bankType: '', category: '', difficulty: '', questionType: '', count: 5, ...data } }
function addExamRule() { examConfig.rules.push(newExamRule({ count: 3 })) }
function removeExamRule(index) {
  if (examConfig.rules.length <= 1) return
  examConfig.rules.splice(index, 1)
}

function handleGlobalKeydown(event) {
  if (!['Escape', 'Esc'].includes(event.key)) return
  if (deleteDialog.visible) return closeDeleteDialog()
  if (showPracticeModal.value) return closePracticeModal()
  if (showModal.value && !saving.value) showModal.value = false
}

function openCreateModal() {
  editingId.value = ''
  modalMode.value = 'manual'
  modalError.value = ''
  resetForm()
  if (filters.bankType) {
    form.bankType = filters.bankType
    aiForm.bankType = filters.bankType
    syncFormBankType()
    syncAiBankType()
  }
  showModal.value = true
}
function openEditModal(item) {
  editingId.value = item.questionId
  modalMode.value = 'manual'
  modalError.value = ''
  const meta = codingMeta(item)
  Object.assign(form, {
    title: item.title || '', bankType: item.bankType || 'baguwen', category: item.category || 'Java', difficulty: item.difficulty || '中等', questionType: item.questionType || (item.bankType === 'leetcode' ? '编程题' : '单选'),
    tagsText: tagLabels(item).join(','), content: questionStem(item), answer: item.answer || '', options: optionItems(item).length ? optionItems(item) : defaultOptions(),
    codingLanguage: normalizeCodingLanguage(meta.language || 'python'), codingFunctionName: meta.functionName || '', codingSignature: meta.signature || '', codingTemplate: meta.template || '', codingTestsText: formatCodingTests(meta.tests),
  })
  syncFormBankType()
  if (form.bankType === 'leetcode' && !form.codingTemplate) form.codingTemplate = buildDefaultTemplate(form.codingFunctionName || 'solution', form.codingLanguage)
  showModal.value = true
}
function resetForm() { Object.assign(form, { title: '', bankType: 'baguwen', category: 'Java', difficulty: '中等', questionType: '单选', tagsText: '', content: '', answer: '', options: defaultOptions(), codingLanguage: 'python', codingFunctionName: '', codingSignature: '', codingTemplate: '', codingTestsText: '' }) }
async function submitModal() {
  modalError.value = ''
  if (modalMode.value === 'manual') return saveQuestion()
  return submitAiGenerate()
}
function validateQuestionForm() {
  requireText(form.title, '请填写题目标题')
  requireText(form.content, isChoiceForm.value ? '请填写选择题题干' : '请填写题目内容')
  if (isChoiceForm.value) {
    const validOptions = form.options.map(item => String(item.text || '').trim()).filter(Boolean)
    if (validOptions.length < 2) throw new Error('选择题至少需要 2 个有效选项')
    requireText(form.answer, '请填写正确答案')
  }
  if (form.bankType === 'leetcode') {
    requireText(form.codingTemplate, '请填写初始代码模板')
    if (!extractFunctionName(form.codingTemplate, form.codingLanguage)) throw new Error('代码模板中需要包含可识别的函数或方法声明')
  }
}
function validateAiForm() {
  if (!String(aiForm.topic || '').trim() && !String(aiForm.documentText || '').trim()) throw new Error('请填写方向主题或上传参考资料')
  const count = Number(aiForm.count || 0)
  if (!Number.isFinite(count) || count < 1 || count > 20) throw new Error('生成数量需在 1-20 之间')
}
function validatePracticeConfig() {
  requireText(examConfig.title, '请填写练习名称')
  const duration = Number(examConfig.durationMinutes || 0)
  if (!Number.isFinite(duration) || duration < 1 || duration > 240) throw new Error('限时时长需在 1-240 分钟之间')
  if (!examRuleTotal.value) throw new Error('请至少配置 1 道题')
  for (const rule of examConfig.rules) {
    const count = Number(rule.count || 0)
    if (!Number.isFinite(count) || count < 0 || count > 50) throw new Error('单条组卷规则题数需在 0-50 之间')
  }
}
async function saveQuestion() {
  saving.value = true
  try {
    validateQuestionForm()
    const payload = buildQuestionPayload()
    const saved = normalizeQuestionRow(editingId.value ? await updateQuestion(editingId.value, payload) : await createQuestion(payload))
    upsertQuestionRow(saved)
    showModal.value = false
    await loadQuestions()
  } catch (err) { modalError.value = err.message || '保存失败' } finally { saving.value = false }
}
function upsertQuestionRow(saved) {
  if (!saved?.questionId) return
  const idx = questions.value.findIndex(item => item.questionId === saved.questionId)
  if (idx >= 0) questions.value.splice(idx, 1, saved)
  else questions.value.unshift(saved)
}
function addOption() {
  const key = String.fromCharCode(65 + form.options.length)
  form.options.push({ key, text: '' })
}
function removeOption(index) {
  if (form.options.length <= 2) return
  form.options.splice(index, 1)
  form.options.forEach((item, idx) => { item.key = String.fromCharCode(65 + idx) })
}
function buildQuestionPayload() {
  const options = form.options.map(item => ({ key: item.key, text: String(item.text || '').trim() })).filter(item => item.text)
  const content = isChoiceType(form.questionType) && options.length
    ? `${form.content.trim()}\n\n${options.map(item => `${item.key}. ${item.text}`).join('\n')}`
    : form.content
  const payload = { ...form, content, answer: form.answer.trim(), tags: splitCleanTags(form.tagsText), bankType: form.bankType, questionType: form.bankType === 'leetcode' ? '编程题' : form.questionType }
  if (payload.bankType === 'leetcode') payload.codingMeta = buildCodingMetaFromForm()
  delete payload.tagsText
  delete payload.options
  delete payload.codingLanguage
  delete payload.codingFunctionName
  delete payload.codingSignature
  delete payload.codingTemplate
  delete payload.codingTestsText
  return payload
}
function bankTypeLabel(value) {
  const option = bankTypeOptions.value.find(item => item.value === value)
  if (option) return option.label
  return bankTypeDisplayName[value] || value || '题库'
}
function syncFormBankType() {
  if (form.bankType === 'leetcode') {
    form.questionType = '编程题'
    if (!form.codingLanguage) form.codingLanguage = 'python'
    if (!form.codingFunctionName) form.codingFunctionName = 'solution'
    if (!form.codingTemplate) form.codingTemplate = buildDefaultTemplate(form.codingFunctionName, form.codingLanguage)
  } else if (form.questionType === '编程题') {
    form.questionType = '单选'
  }
}
function resetCodingTemplateForLanguage() {
  const functionName = extractFunctionName(form.codingTemplate, form.codingLanguage) || form.codingFunctionName || 'solution'
  form.codingFunctionName = functionName
  form.codingTemplate = buildDefaultTemplate(functionName, form.codingLanguage)
}
function syncAiBankType() {
  if (aiForm.bankType === 'leetcode') aiForm.questionType = '编程题'
  else if (aiForm.questionType === '编程题') aiForm.questionType = '单选'
}
function codingMeta(item) {
  return item?.codingMeta && typeof item.codingMeta === 'object' ? item.codingMeta : {}
}
function currentCodingLanguage(questionId) {
  return normalizeCodingLanguage(codingLanguageByQuestion[questionId] || 'python')
}
function codingSignature(item) {
  const meta = codingMeta(item)
  const language = currentCodingLanguage(item.questionId)
  if (meta.signature && normalizeCodingLanguage(meta.language) === language) return meta.signature
  const functionName = meta.functionName || extractFunctionName(meta.template, normalizeCodingLanguage(meta.language)) || 'solution'
  return defaultSignature(functionName, language)
}
function setCodingLanguage(questionId, value) {
  const oldLanguage = currentCodingLanguage(questionId)
  const nextLanguage = normalizeCodingLanguage(value)
  const meta = codingMeta((currentExam.value?.questions || []).find(item => item.questionId === questionId) || {})
  const functionName = meta.functionName || extractFunctionName(answers[questionId], oldLanguage) || 'solution'
  const oldTemplate = buildDefaultTemplate(functionName, oldLanguage).trim()
  const current = String(answers[questionId] || '').trim()
  codingLanguageByQuestion[questionId] = nextLanguage
  if (!current || current === oldTemplate) answers[questionId] = buildDefaultTemplate(functionName, nextLanguage)
  delete codingResults[questionId]
}
function buildCodingMetaFromForm() {
  let tests = []
  if (form.codingTestsText.trim()) {
    try {
      const parsed = JSON.parse(form.codingTestsText)
      tests = Array.isArray(parsed) ? parsed : []
    } catch (err) {
      throw new Error('测试用例 JSON 格式不正确')
    }
  }
  const language = normalizeCodingLanguage(form.codingLanguage)
  const functionName = extractFunctionName(form.codingTemplate, language) || form.codingFunctionName || 'solution'
  return { language, functionName, signature: defaultSignature(functionName, language), template: form.codingTemplate || buildDefaultTemplate(functionName, language), tests }
}
function isQuestionAnswered(item) {
  const value = String(answers[item.questionId] || '').trim()
  if (!value) return false
  if (isCodingQuestion(item)) {
    const language = currentCodingLanguage(item.questionId)
    const meta = codingMeta(item)
    const functionName = meta.functionName || extractFunctionName(value, language) || 'solution'
    const template = buildDefaultTemplate(functionName, language).trim()
    return value !== template && !/(TODO|pass\s*$)/i.test(value)
  }
  return true
}
function setActiveQuestion(questionId) {
  activeQuestionId.value = questionId
}
function goAdjacentQuestion(delta) {
  const list = currentExam.value?.questions || []
  const index = list.findIndex(item => item.questionId === activeQuestion.value?.questionId)
  const next = list[Math.min(Math.max(index + delta, 0), list.length - 1)]
  if (next) setActiveQuestion(next.questionId)
}
function selectedAnswerKeys(item) { return String(answers[item.questionId] || '').split(/[,，、\s]+/).filter(Boolean) }
function isOptionSelected(item, key) { return selectedAnswerKeys(item).includes(key) }
function updateOptionAnswer(item, key, checked) {
  if (isMultiChoice(item)) {
    const set = new Set(selectedAnswerKeys(item))
    checked ? set.add(key) : set.delete(key)
    answers[item.questionId] = Array.from(set).sort().join(',')
  } else {
    answers[item.questionId] = key
  }
}
function handleAiDocumentChange(event) {
  modalError.value = ''
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    aiForm.documentName = file.name
    aiForm.documentText = String(reader.result || '').slice(0, 20000)
  }
  reader.onerror = () => { modalError.value = '文档读取失败，请换成 txt、md 或可读取的文本文件' }
  reader.readAsText(file, 'utf-8')
}
function clearAiDocument() {
  aiForm.documentName = ''
  aiForm.documentText = ''
}

async function submitAiGenerate() {
  saving.value = true
  try {
    validateAiForm()
    await generateQuestions(aiForm)
    showModal.value = false
    await loadQuestions()
  } catch (err) { modalError.value = err.message || 'AI 生成失败' } finally { saving.value = false }
}
function removeQuestion(questionId) {
  Object.assign(deleteDialog, { visible: true, mode: 'single', questionId, count: 1 })
}
function closeDeleteDialog() {
  if (saving.value) return
  Object.assign(deleteDialog, { visible: false, mode: 'single', questionId: '', count: 0 })
}
async function confirmDelete() {
  saving.value = true
  error.value = ''
  try {
    if (deleteDialog.mode === 'batch') {
      await batchQuestions({ action: 'delete', questionIds: selectedIds.value })
      clearSelection()
    } else if (deleteDialog.questionId) {
      await deleteQuestion(deleteDialog.questionId)
    }
    Object.assign(deleteDialog, { visible: false, mode: 'single', questionId: '', count: 0 })
    await loadQuestions()
  } catch (err) {
    error.value = err.message || '删除失败'
  } finally {
    saving.value = false
  }
}

async function startExam() {
  examLoading.value = true
  practiceModalError.value = ''
  error.value = ''
  try {
    validatePracticeConfig()
    const payload = {
      title: examConfig.title,
      durationMinutes: Number(examConfig.durationMinutes || 30),
      showAnswer: examConfig.answerMode === 'visible',
      rules: examConfig.rules.map(rule => ({ bankType: rule.bankType, category: rule.category, difficulty: rule.difficulty, questionType: rule.questionType, count: Number(rule.count || 0) })).filter(rule => rule.count > 0),
    }
    currentExam.value = await createRandomExam(payload)
    resetPracticeAnswers(currentExam.value)
    startExamTimer(Number(currentExam.value.remainingSeconds || payload.durationMinutes * 60), true)
    showPracticeModal.value = false
    await loadExams()
  } catch (err) { practiceModalError.value = err.message || '随机组卷失败' } finally { examLoading.value = false }
}
async function openExam(examId) {
  stopExamTimer()
  examDetailLoading.value = true
  currentExam.value = null
  activeQuestionId.value = ''
  error.value = ''
  try {
    currentExam.value = await getExam(examId)
    resetPracticeAnswers(currentExam.value, true)
    if (currentExam.value?.status !== 'submitted') startExamTimer(Number(currentExam.value.remainingSeconds || 0), true)
  } catch (err) {
    error.value = err.message || '练习详情加载失败'
  } finally {
    examDetailLoading.value = false
  }
}
function resetPracticeAnswers(exam, keepUserAnswer = false) {
  Object.keys(answers).forEach(key => delete answers[key])
  Object.keys(codingResults).forEach(key => delete codingResults[key])
  Object.keys(codingRunning).forEach(key => delete codingRunning[key])
  Object.keys(codingLanguageByQuestion).forEach(key => delete codingLanguageByQuestion[key])
  const list = exam?.questions || []
  for (const q of list) {
    const meta = codingMeta(q)
    const language = 'python'
    const functionName = meta.functionName || extractFunctionName(meta.template, normalizeCodingLanguage(meta.language)) || 'solution'
    codingLanguageByQuestion[q.questionId] = language
    answers[q.questionId] = keepUserAnswer ? (q.userAnswer || (isCodingQuestion(q) ? buildDefaultTemplate(functionName, language) : '')) : (isCodingQuestion(q) ? buildDefaultTemplate(functionName, language) : '')
    if (q.correct != null) codingResults[q.questionId] = { passed: Boolean(q.correct), rows: [] }
  }
  activeQuestionId.value = list[0]?.questionId || ''
}
async function submitCurrentExam() {
  if (!currentExam.value || examLoading.value) return
  examLoading.value = true
  error.value = ''
  try {
    await runAllCodingBeforeSubmit()
    currentExam.value = await submitExam(currentExam.value.examId, answers, codingSubmitPayload())
    stopExamTimer()
    for (const q of currentExam.value.questions || []) answers[q.questionId] = q.userAnswer || answers[q.questionId] || ''
    await loadExams()
  } catch (err) { error.value = err.message || '提交失败' } finally { examLoading.value = false }
}
async function runCodingSample(item) {
  const meta = codingMeta(item)
  const tests = (Array.isArray(meta.tests) ? meta.tests : []).filter(row => row.sample)
  return runCodingTests(item, tests.length ? tests : (Array.isArray(meta.tests) ? meta.tests : []))
}
async function runCodingTests(item, tests) {
  const meta = codingMeta(item)
  const rows = Array.isArray(tests) ? tests : []
  const language = currentCodingLanguage(item.questionId)
  const functionName = meta.functionName || extractFunctionName(answers[item.questionId], language) || 'solution'
  if (!rows.length) {
    codingResults[item.questionId] = { passed: false, rows: [], message: '未维护测试用例' }
    return codingResults[item.questionId]
  }
  codingRunning[item.questionId] = true
  try {
    const result = await runCodeSample({ language, source: answers[item.questionId] || '', functionName, tests: rows })
    const resultRows = Array.isArray(result.rows) ? result.rows : []
    const passed = Boolean(result.passed)
    codingResults[item.questionId] = { passed, rows: resultRows, message: result.message || '' }
    return codingResults[item.questionId]
  } catch (err) {
    codingResults[item.questionId] = { passed: false, rows: [], message: err?.message || '运行失败' }
    return codingResults[item.questionId]
  } finally {
    codingRunning[item.questionId] = false
  }
}
async function runAllCodingBeforeSubmit() {
  const codingQuestions = (currentExam.value?.questions || []).filter(isCodingQuestion)
  for (const item of codingQuestions) {
    const meta = codingMeta(item)
    const tests = Array.isArray(meta.tests) ? meta.tests : []
    await runCodingTests(item, tests)
  }
}
function codingSubmitPayload() {
  const result = {}
  for (const q of currentExam.value?.questions || []) {
    if (isCodingQuestion(q)) result[q.questionId] = Boolean(codingResults[q.questionId]?.passed)
  }
  return result
}
function codingResultRows(questionId) { return codingResults[questionId]?.rows || [] }
function codingResultSummary(questionId) {
  const result = codingResults[questionId]
  if (!result) return '尚未运行'
  if (result.message) return result.message
  const rows = result.rows || []
  return rows.length ? `${rows.filter(row => row.passed).length} / ${rows.length} 通过` : '尚未运行'
}
function startExamTimer(value, secondsMode = false) {
  stopExamTimer()
  timerRemaining.value = secondsMode ? Math.max(0, Number(value || 0)) : Math.max(1, Number(value || 30)) * 60
  timerId = window.setInterval(() => {
    timerRemaining.value = Math.max(0, timerRemaining.value - 1)
    if (timerRemaining.value <= 0) {
      stopExamTimer(false)
      submitCurrentExam()
    }
  }, 1000)
}
function stopExamTimer(reset = true) {
  if (timerId) window.clearInterval(timerId)
  timerId = null
  if (reset) timerRemaining.value = 0
}
</script>
