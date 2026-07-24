<template>
  <div v-if="visible" class="modal-mask question-maintain-mask" @click.self="close">
    <div
      class="modal-card interview-modal-card practice-create-modal maintain-modal question-maintain-modal"
      :class="{
        'question-maintain-modal--compact': currentStep === 0,
        'question-maintain-modal--with-tabs': !isEditing,
      }"
      role="dialog"
      aria-modal="true"
      aria-labelledby="question-maintain-title"
    >
      <button type="button" class="close" :disabled="busy" aria-label="关闭题目维护弹窗" @click="close">×</button>
      <header class="practice-modal-head">
        <h2 id="question-maintain-title">{{ modalTitle }}</h2>
        <p>{{ modalDescription }}</p>
      </header>
      <div v-if="!isEditing" class="interview-modal-tabs" role="tablist" aria-label="题目录入方式">
        <button
          type="button"
          role="tab"
          :aria-selected="modalMode === 'manual'"
          :disabled="busy"
          :class="{ active: modalMode === 'manual' }"
          @click="setModalMode('manual')"
        >
          手动录入
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="modalMode === 'ai'"
          :disabled="busy"
          :class="{ active: modalMode === 'ai' }"
          @click="setModalMode('ai')"
        >
          AI 生成
        </button>
      </div>

      <nav class="question-wizard-steps" aria-label="题目维护步骤">
        <button
          v-for="(step, index) in wizardSteps"
          :key="step.key"
          type="button"
          :class="{ active: currentStep === index, done: currentStep > index }"
          :disabled="busy || index > furthestStep"
          :aria-current="currentStep === index ? 'step' : undefined"
          @click="goToStep(index)"
        >
          <b>{{ index + 1 }}</b>
          <span
            ><strong>{{ step.label }}</strong
            ><small>{{ step.description }}</small></span
          >
        </button>
      </nav>

      <div ref="scrollContainer" class="question-maintain-scroll">
        <p
          v-if="modalError"
          class="error settings-error form-error-alert question-wizard-error"
          role="alert"
          aria-live="assertive"
        >
          {{ modalError }}
        </p>
        <div v-if="modalMode === 'manual'" class="practice-form question-wizard-panel">
          <div v-if="currentStep === 0" class="practice-section">
            <div class="question-section-heading">
              <div>
                <span class="practice-field-label">基本信息</span>
                <small>{{ manualBasicHint }}</small>
              </div>
            </div>
            <div class="maintain-field-grid">
              <label class="practice-field wide"
                ><span class="practice-field-label form-required">{{ manualTitleLabel }}</span
                ><input
                  v-model="form.title"
                  maxlength="200"
                  aria-required="true"
                  :placeholder="manualTitlePlaceholder"
                /><small class="field-hint">{{ manualTitleHint }}</small></label
              >
              <label class="practice-field"
                ><span class="practice-field-label form-required">分类</span
                ><input
                  v-model="form.category"
                  maxlength="64"
                  aria-required="true"
                  :placeholder="manualCategoryPlaceholder"
              /></label>
              <div class="practice-field">
                <span class="practice-field-label form-required">难度</span>
                <div class="question-segmented-control" role="group" aria-label="题目难度">
                  <button
                    v-for="difficulty in difficultyOptions"
                    :key="difficulty"
                    type="button"
                    :class="{ active: form.difficulty === difficulty }"
                    @click="form.difficulty = difficulty"
                  >
                    {{ difficulty }}
                  </button>
                </div>
              </div>
              <label v-if="form.bankType === 'qa'" class="practice-field wide"
                ><span class="practice-field-label form-required">问答题型</span
                ><select v-model="form.questionType" aria-required="true" @change="handleManualQuestionTypeChange">
                  <option v-for="type in qaQuestionTypeOptions" :key="type" :value="type">{{ type }}</option>
                </select>
                <small class="field-hint">{{ manualQuestionTypeHint }}</small></label
              >
              <div class="practice-field question-tag-field wide">
                <span class="practice-field-label">标签</span>
                <div v-if="formTags.length" class="question-tag-list" aria-label="当前题目标签">
                  <span v-for="tag in formTags" :key="tag"
                    >{{ tag
                    }}<button type="button" :aria-label="`移除标签 ${tag}`" @click="removeFormTag(tag)">×</button></span
                  >
                </div>
                <div class="question-tag-input-row">
                  <input v-model.trim="tagDraft" :placeholder="manualTagPlaceholder" @keydown="handleTagKeydown" />
                  <button type="button" class="secondary-btn" :disabled="!tagDraft.trim()" @click="addFormTag">
                    添加标签
                  </button>
                </div>
                <small v-if="tagError" class="field-hint question-tag-error" role="alert">{{ tagError }}</small>
                <small v-else class="field-hint">请逐个添加标签，点击标签右侧 × 可移除。</small>
              </div>
            </div>
          </div>

          <div v-else-if="currentStep === 1" class="practice-section">
            <div class="question-section-heading">
              <div>
                <span class="practice-field-label">{{ manualContentSectionLabel }}</span>
                <small>{{ manualContentHint }}</small>
              </div>
            </div>
            <div class="practice-field markdown-editor-field">
              <div class="markdown-editor-head">
                <span class="practice-field-label form-required">{{ isChoiceForm ? '题干' : '题目描述' }}</span>
                <div class="markdown-editor-tabs" role="tablist" aria-label="题目描述编辑模式">
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="contentEditorMode === 'edit'"
                    :class="{ active: contentEditorMode === 'edit' }"
                    @click="contentEditorMode = 'edit'"
                  >
                    编辑
                  </button>
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="contentEditorMode === 'preview'"
                    :class="{ active: contentEditorMode === 'preview' }"
                    @click="contentEditorMode = 'preview'"
                  >
                    预览
                  </button>
                </div>
              </div>
              <label
                v-if="contentEditorMode === 'edit'"
                class="markdown-editor-pane markdown-source-pane"
                for="question-content-markdown"
                ><span>Markdown 源码</span
                ><textarea
                  id="question-content-markdown"
                  v-model="form.content"
                  class="question-content-textarea question-content-textarea--standalone"
                  aria-required="true"
                  :placeholder="manualContentPlaceholder"
                />
              </label>
              <section v-else class="markdown-editor-pane markdown-preview-pane" aria-label="题目描述 Markdown 预览">
                <span>渲染预览</span>
                <div class="markdown-preview-content">
                  <PracticeMarkdown
                    :content="form.content"
                    custom-id="question-content-preview"
                    empty-text="输入 Markdown 后可在这里查看题目描述效果"
                  />
                </div>
              </section>
            </div>
            <div v-if="isChoiceForm" class="choice-option-editor">
              <div class="choice-option-head">
                <span class="form-required">选项（支持 Markdown）</span
                ><button type="button" class="secondary-btn" @click="addOption">新增选项</button>
              </div>
              <label v-for="(option, index) in form.options" :key="option.key" class="choice-option-row">
                <b>{{ option.key }}</b>
                <input v-model="option.text" :placeholder="`请输入选项 ${option.key} 的内容`" aria-required="true" />
                <button
                  type="button"
                  class="danger-text"
                  :disabled="form.options.length <= 2"
                  @click="removeOption(index)"
                >
                  删除
                </button>
              </label>
            </div>
            <div v-else-if="form.bankType === 'leetcode'" class="coding-meta-editor">
              <div class="coding-entry-heading wide">
                <div>
                  <strong>代码入口</strong>
                  <small>语言和函数名会写入判题配置，参数数量由测试用例自动识别。</small>
                </div>
                <button type="button" class="secondary-btn" @click="refreshCodingTemplate">按当前设置重建模板</button>
              </div>
              <label class="practice-field"
                ><span class="practice-field-label form-required">编程语言</span
                ><select v-model="form.codingLanguage" aria-required="true" @change="handleCodingLanguageChange">
                  <option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label form-required">函数入口</span
                ><input
                  v-model.trim="form.codingFunctionName"
                  maxlength="64"
                  aria-required="true"
                  placeholder="例如：twoSum"
              /></label>
              <label class="practice-field wide"
                ><span class="practice-field-label form-required">初始代码模板</span
                ><CodeHighlightEditor
                  v-model="form.codingTemplate"
                  :language="codingHighlightLanguage"
                  :required="true"
                  aria-label="初始代码模板"
                  textarea-class="question-code-template-textarea"
                  :placeholder="buildDefaultTemplate('solution', codingHighlightLanguage)"
                />
                <small class="field-hint"
                  >可直接修改生成的模板；切换语言或点击“重建模板”会按当前函数入口重新生成。</small
                >
              </label>
            </div>
          </div>

          <div v-else class="practice-section">
            <label v-if="isChoiceForm" class="practice-field"
              ><span class="practice-field-label form-required">正确选项编号</span
              ><input
                v-model="form.answer"
                aria-required="true"
                :placeholder="form.questionType === '多选' ? '例如：A,C' : '例如：A'"
              /><small class="field-hint">{{
                form.questionType === '多选' ? '填写所有正确选项编号，使用英文逗号分隔。' : '填写唯一正确选项的编号。'
              }}</small></label
            >
            <div v-else>
              <div v-if="form.bankType === 'leetcode'" class="coding-test-editor">
                <div class="coding-test-heading">
                  <div>
                    <span class="practice-field-label form-required">测试用例</span>
                    <small>每个参数和期望结果使用 JSON；参数必须用数组包裹并按函数顺序填写。</small>
                  </div>
                  <div>
                    <button type="button" class="secondary-btn" @click="addCodingTest">新增用例</button>
                  </div>
                </div>
                <article
                  v-for="(test, index) in form.codingTests"
                  :key="test.id"
                  class="coding-test-card"
                  :aria-label="`测试用例 ${index + 1}`"
                >
                  <header>
                    <b>用例 {{ index + 1 }}</b>
                    <label class="coding-sample-toggle"
                      ><input v-model="test.sample" type="checkbox" /> 练习时公开为样例</label
                    >
                    <button
                      type="button"
                      class="danger-text"
                      :disabled="form.codingTests.length <= 1"
                      @click="removeCodingTest(index)"
                    >
                      删除
                    </button>
                  </header>
                  <div class="coding-test-grid">
                    <label class="practice-field wide"
                      ><span class="practice-field-label">用例名称</span
                      ><input v-model.trim="test.name" maxlength="80" :placeholder="`例如：公开样例 ${index + 1}`"
                    /></label>
                    <label class="practice-field"
                      ><span class="practice-field-label form-required">函数参数</span
                      ><textarea
                        v-model="test.argsText"
                        class="coding-test-value"
                        aria-required="true"
                        placeholder="例如：[[2,7,11,15],9]"
                      /><small class="field-hint">多参数也放在同一个数组中。</small></label
                    >
                    <label class="practice-field"
                      ><span class="practice-field-label form-required">期望结果</span
                      ><textarea
                        v-model="test.expectedText"
                        class="coding-test-value"
                        aria-required="true"
                        placeholder="例如：[0,1]"
                      />
                    </label>
                  </div>
                </article>
              </div>
              <div class="practice-field markdown-editor-field markdown-answer-editor">
                <div class="markdown-editor-head">
                  <span class="practice-field-label" :class="{ 'form-required': form.bankType === 'qa' }">{{
                    manualAnswerLabel
                  }}</span>
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
                  for="question-answer-markdown"
                  ><span>Markdown 源码</span
                  ><textarea
                    id="question-answer-markdown"
                    v-model="form.answer"
                    class="question-answer-textarea"
                    :class="{ 'question-answer-textarea--standalone': form.bankType !== 'leetcode' }"
                    :aria-required="form.bankType === 'qa' ? 'true' : undefined"
                    :placeholder="manualAnswerPlaceholder"
                  />
                </label>
                <section v-else class="markdown-editor-pane markdown-preview-pane" aria-label="参考答案 Markdown 预览">
                  <span>渲染预览</span>
                  <div class="markdown-preview-content">
                    <PracticeMarkdown
                      :content="form.answer"
                      custom-id="question-answer-preview"
                      empty-text="输入 Markdown 后可在这里查看答案效果"
                    />
                  </div>
                </section>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="practice-form ai-generate-panel question-wizard-panel">
          <div v-if="currentStep === 0" class="practice-section">
            <div class="question-section-heading">
              <div>
                <span class="practice-field-label">生成设置</span>
                <small>{{ aiSettingsHint }}</small>
              </div>
            </div>
            <div class="maintain-field-grid">
              <label class="practice-field wide"
                ><span class="practice-field-label">{{ aiTopicLabel }}</span
                ><input v-model="aiForm.topic" maxlength="200" :placeholder="aiTopicPlaceholder"
              /></label>
              <label class="practice-field"
                ><span class="practice-field-label form-required">分类</span
                ><input
                  v-model="aiForm.category"
                  maxlength="64"
                  aria-required="true"
                  :placeholder="aiCategoryPlaceholder"
              /></label>
              <div class="practice-field">
                <span class="practice-field-label form-required">难度</span>
                <div class="question-segmented-control" role="group" aria-label="生成题目难度">
                  <button
                    v-for="difficulty in difficultyOptions"
                    :key="difficulty"
                    type="button"
                    :class="{ active: aiForm.difficulty === difficulty }"
                    @click="aiForm.difficulty = difficulty"
                  >
                    {{ difficulty }}
                  </button>
                </div>
              </div>
              <label v-if="aiForm.bankType === 'qa'" class="practice-field"
                ><span class="practice-field-label form-required">问答题型</span
                ><select v-model="aiForm.questionType" aria-required="true">
                  <option v-for="type in qaQuestionTypeOptions" :key="type" :value="type">{{ type }}</option>
                </select></label
              >
              <label v-if="aiForm.bankType === 'leetcode'" class="practice-field"
                ><span class="practice-field-label form-required">代码语言</span
                ><select v-model="aiForm.language" aria-required="true">
                  <option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </option>
                </select></label
              >
              <label class="practice-field"
                ><span class="practice-field-label form-required">数量</span
                ><input
                  v-model.number="aiForm.count"
                  aria-required="true"
                  type="number"
                  min="1"
                  max="20"
                  step="1"
                  placeholder="请输入 1-20 的整数"
              /></label>
            </div>
          </div>

          <div v-else-if="currentStep === 1" class="practice-section">
            <div v-if="aiForm.bankType === 'leetcode'" class="question-source-notice">
              <b>LeetCode 链接只作为来源标识</b>
              <span
                >当前没有可依赖的公开题目导入
                API，系统不会抓取网页。若要按原题精确生成，请同时粘贴题面或上传资料。</span
              >
            </div>
            <label v-if="aiForm.bankType === 'leetcode'" class="practice-field"
              ><span class="practice-field-label">LeetCode 题目链接</span
              ><input
                v-model.trim="aiForm.sourceUrl"
                maxlength="500"
                placeholder="https://leetcode.com/problems/two-sum/"
              /><small class="field-hint">支持 leetcode.com 和 leetcode.cn 的标准题目地址，可不填。</small></label
            >
            <div class="practice-field">
              <span class="practice-field-label">上传资料</span>
              <div class="doc-upload-field">
                <label class="doc-upload-box">
                  <input
                    type="file"
                    accept=".pdf,.doc,.docx,.txt,.md,.markdown,.json,.csv,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    :disabled="busy"
                    @change="handleAiDocumentChange"
                  />
                  <b>{{ documentReading ? '正在读取' : '选择文档' }}</b>
                  <small>{{ documentReading ? aiDocumentReadingText : aiForm.documentName || aiUploadHint }}</small>
                </label>
                <button
                  v-if="aiForm.documentName"
                  type="button"
                  class="doc-clear-btn"
                  :disabled="busy"
                  @click="clearAiDocument"
                >
                  清除文档
                </button>
              </div>
              <small v-if="documentNotice" class="field-hint">{{ documentNotice }}</small>
            </div>
            <label class="practice-field"
              ><span class="practice-field-label">{{ aiSourceTextLabel }}</span
              ><textarea
                v-model="aiForm.documentText"
                class="question-source-textarea"
                maxlength="20000"
                :placeholder="aiSourceTextPlaceholder"
              /><small class="field-hint">{{ aiForm.documentText.length }} / 20000</small></label
            >
            <label class="practice-field"
              ><span class="practice-field-label">出题要求</span
              ><textarea
                v-model="aiForm.requirements"
                class="question-requirements-textarea"
                maxlength="2000"
                :placeholder="aiRequirementsPlaceholder"
              />
            </label>
            <div class="question-generation-summary" aria-live="polite">
              <span>本次将生成待审核候选题</span>
              <strong>{{ generationSummary }}</strong>
              <small>{{
                aiForm.bankType === 'leetcode'
                  ? '生成后请逐题核对题目描述、函数入口、公开样例和边界用例。'
                  : aiForm.questionType === '简答'
                    ? '生成后请逐题核对题干、参考答案和评分要点。'
                    : '生成后请逐题核对题干、全部选项和正确答案。'
              }}</small>
            </div>
          </div>

          <div v-else class="practice-section question-candidate-review">
            <div class="question-section-heading">
              <div>
                <span class="practice-field-label">审核候选题</span>
                <small>生成结果尚未入库。请修改不准确内容，并逐题勾选“已核对”。</small>
              </div>
              <span class="candidate-review-progress"
                >{{ reviewedCandidateCount }} / {{ aiCandidates.length }} 已核对</span
              >
            </div>
            <div v-if="aiForm.bankType === 'leetcode'" class="question-source-notice question-source-notice--warning">
              <b>测试用例需要人工负责</b>
              <span>请确认参数顺序、边界数据和期望结果真实可执行；勾选不代表系统已经运行过这些用例。</span>
            </div>
            <div v-else class="question-source-notice question-source-notice--warning">
              <b>答案内容需要人工负责</b>
              <span>{{
                aiForm.questionType === '简答'
                  ? '请确认参考答案覆盖核心知识点，评分要点清晰且不存在事实错误。'
                  : '请确认选项互斥且完整，并核对正确答案使用现有选项编号。'
              }}</span>
            </div>
            <article
              v-for="(candidate, candidateIndex) in aiCandidates"
              :key="candidate.id"
              class="question-candidate-card"
            >
              <header>
                <div>
                  <b>候选题 {{ candidateIndex + 1 }}</b>
                  <span>{{ candidate.form.difficulty }} · {{ candidate.form.category }}</span>
                </div>
                <label class="candidate-confirm-toggle"
                  ><input v-model="candidate.confirmed" type="checkbox" @change="clearCandidateError" />
                  {{ candidateConfirmLabel(candidate.form) }}</label
                >
              </header>
              <div class="maintain-field-grid candidate-field-grid">
                <label class="practice-field wide"
                  ><span class="practice-field-label form-required">标题</span
                  ><input
                    v-model="candidate.form.title"
                    maxlength="200"
                    aria-required="true"
                    :placeholder="candidateTitlePlaceholder(candidate.form)"
                    @input="markCandidateChanged(candidate)"
                /></label>
                <label class="practice-field"
                  ><span class="practice-field-label form-required">分类</span
                  ><input
                    v-model="candidate.form.category"
                    maxlength="64"
                    aria-required="true"
                    :placeholder="candidateCategoryPlaceholder(candidate.form)"
                    @input="markCandidateChanged(candidate)"
                /></label>
                <label class="practice-field"
                  ><span class="practice-field-label form-required">难度</span
                  ><select
                    v-model="candidate.form.difficulty"
                    aria-required="true"
                    @change="markCandidateChanged(candidate)"
                  >
                    <option v-for="difficulty in difficultyOptions" :key="difficulty" :value="difficulty">
                      {{ difficulty }}
                    </option>
                  </select></label
                >
                <label v-if="candidate.form.bankType === 'qa'" class="practice-field wide"
                  ><span class="practice-field-label form-required">问答题型</span
                  ><select
                    v-model="candidate.form.questionType"
                    aria-required="true"
                    @change="handleCandidateQuestionTypeChange(candidate)"
                  >
                    <option v-for="type in qaQuestionTypeOptions" :key="type" :value="type">{{ type }}</option>
                  </select></label
                >
                <label class="practice-field wide"
                  ><span class="practice-field-label form-required">{{
                    candidate.form.bankType === 'leetcode' ? '算法题面' : '问答题干'
                  }}</span
                  ><textarea
                    v-model="candidate.form.content"
                    class="candidate-content-textarea"
                    aria-required="true"
                    :placeholder="candidateContentPlaceholder(candidate.form)"
                    @input="markCandidateChanged(candidate)"
                  />
                </label>
                <div
                  v-if="candidate.form.bankType === 'qa' && isChoiceType(candidate.form.questionType)"
                  class="choice-option-editor wide"
                >
                  <div class="choice-option-head">
                    <span class="form-required">选项</span
                    ><button type="button" class="secondary-btn" @click="addCandidateOption(candidate)">
                      新增选项
                    </button>
                  </div>
                  <label
                    v-for="(option, optionIndex) in candidate.form.options"
                    :key="option.key"
                    class="choice-option-row"
                  >
                    <b>{{ option.key }}</b>
                    <input
                      v-model="option.text"
                      :placeholder="`请输入选项 ${option.key} 的内容`"
                      aria-required="true"
                      @input="markCandidateChanged(candidate)"
                    />
                    <button
                      type="button"
                      class="danger-text"
                      :disabled="candidate.form.options.length <= 2"
                      @click="removeCandidateOption(candidate, optionIndex)"
                    >
                      删除
                    </button>
                  </label>
                </div>
                <template v-if="candidate.form.bankType === 'leetcode'">
                  <label class="practice-field"
                    ><span class="practice-field-label form-required">代码语言</span
                    ><select
                      v-model="candidate.form.codingLanguage"
                      aria-required="true"
                      @change="markCandidateChanged(candidate)"
                    >
                      <option v-for="item in codingLanguageOptions" :key="item.value" :value="item.value">
                        {{ item.label }}
                      </option>
                    </select></label
                  >
                  <label class="practice-field"
                    ><span class="practice-field-label form-required">函数入口</span
                    ><input
                      v-model.trim="candidate.form.codingFunctionName"
                      maxlength="64"
                      aria-required="true"
                      @input="markCandidateChanged(candidate)"
                  /></label>
                  <label class="practice-field wide"
                    ><span class="practice-field-label form-required">初始代码模板</span
                    ><textarea
                      v-model="candidate.form.codingTemplate"
                      class="candidate-code-template"
                      spellcheck="false"
                      aria-required="true"
                      @input="markCandidateChanged(candidate)"
                    />
                  </label>
                  <div class="practice-field wide candidate-tests-review">
                    <div class="coding-test-heading">
                      <div>
                        <span class="practice-field-label form-required">测试用例</span>
                        <small>参数和期望结果均使用 JSON；至少保留一条公开样例。</small>
                      </div>
                      <button type="button" class="secondary-btn" @click="addCandidateTest(candidate)">新增用例</button>
                    </div>
                    <article
                      v-for="(test, testIndex) in candidate.form.codingTests"
                      :key="test.id"
                      class="coding-test-card"
                    >
                      <header>
                        <b>用例 {{ testIndex + 1 }}</b>
                        <label class="coding-sample-toggle"
                          ><input v-model="test.sample" type="checkbox" @change="markCandidateChanged(candidate)" />
                          公开样例</label
                        >
                        <button
                          type="button"
                          class="danger-text"
                          :disabled="candidate.form.codingTests.length <= 1"
                          @click="removeCandidateTest(candidate, testIndex)"
                        >
                          删除
                        </button>
                      </header>
                      <div class="coding-test-grid">
                        <label class="practice-field wide"
                          ><span class="practice-field-label">名称</span
                          ><input v-model.trim="test.name" @input="markCandidateChanged(candidate)"
                        /></label>
                        <label class="practice-field"
                          ><span class="practice-field-label form-required">函数参数</span
                          ><textarea
                            v-model="test.argsText"
                            class="coding-test-value"
                            aria-required="true"
                            @input="markCandidateChanged(candidate)"
                          />
                        </label>
                        <label class="practice-field"
                          ><span class="practice-field-label form-required">期望结果</span
                          ><textarea
                            v-model="test.expectedText"
                            class="coding-test-value"
                            aria-required="true"
                            @input="markCandidateChanged(candidate)"
                          />
                        </label>
                      </div>
                    </article>
                  </div>
                </template>
                <label
                  v-if="candidate.form.bankType === 'qa' && isChoiceType(candidate.form.questionType)"
                  class="practice-field wide"
                  ><span class="practice-field-label form-required">正确答案</span
                  ><input
                    v-model.trim="candidate.form.answer"
                    aria-required="true"
                    :placeholder="candidate.form.questionType === '多选' ? '例如：A,C' : '例如：A'"
                    @input="markCandidateChanged(candidate)"
                /></label>
                <label v-else class="practice-field wide"
                  ><span class="practice-field-label" :class="{ 'form-required': candidate.form.bankType === 'qa' }">{{
                    candidate.form.bankType === 'leetcode' ? '解题思路 / 参考答案' : '参考答案 / 评分要点'
                  }}</span
                  ><textarea
                    v-model="candidate.form.answer"
                    class="candidate-answer-textarea"
                    :aria-required="candidate.form.bankType === 'qa' ? 'true' : undefined"
                    :placeholder="candidateAnswerPlaceholder(candidate.form)"
                    @input="markCandidateChanged(candidate)"
                  />
                </label>
              </div>
            </article>
          </div>
        </div>
      </div>
      <div class="modal-actions practice-modal-actions question-wizard-actions">
        <div class="question-wizard-action-buttons">
          <button
            v-if="currentStep > 0"
            type="button"
            class="secondary-btn question-wizard-previous"
            :disabled="busy"
            @click="previousStep"
          >
            上一步
          </button>
          <button
            v-if="!isLastStep"
            type="button"
            class="secondary-btn question-wizard-next"
            :disabled="busy"
            @click="nextStep"
          >
            {{ nextButtonText }}
          </button>
          <button
            v-if="isLastStep"
            type="button"
            class="primary-btn question-wizard-save"
            :disabled="busy"
            @click="submitModal"
          >
            {{ documentReading ? '读取文档中' : saving ? '处理中' : modalSubmitText }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// Create/edit modal for interview questions with manual entry and AI generation tabs.
// Owns the two form states and question CRUD/generate API calls; emits `saved` with the raw
// saved row (or null after AI generation) so the parent can refresh the bank list.
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  createQuestion,
  extractInterviewDocument,
  generateQuestions,
  importQuestions,
  updateQuestion,
} from '../../api/interview'
import { detectCodeLanguage } from '../../utils/codeHighlight'
import { validateFile } from '../../utils/formValidation'
import {
  buildDefaultTemplate,
  codingLanguageOptions,
  codingMeta,
  defaultOptions,
  extractFunctionName,
  isChoiceType,
  normalizeCodingLanguage,
  optionItems,
  questionStem,
  tagLabels,
} from '../../utils/interviewBank'
import CodeHighlightEditor from './CodeHighlightEditor.vue'
import PracticeMarkdown from './PracticeMarkdown.vue'
import {
  buildQuestionPayload,
  createCodingTestRow,
  formatCodingTestRows,
  validateAiForm,
  validateAiStep,
  validateQuestionForm,
  validateQuestionStep,
} from '../../utils/interviewForm'

const props = defineProps({
  bankTypeOptions: { type: Array, default: () => [] },
  questionTypeOptions: { type: Array, default: () => [] },
})

const emit = defineEmits(['saved'])

const visible = ref(false)
const modalMode = ref('manual')
const editingId = ref('')
const saving = ref(false)
const documentReading = ref(false)
const documentNotice = ref('')
const modalError = ref('')
const currentStep = ref(0)
const contentEditorMode = ref('edit')
const answerEditorMode = ref('edit')
const scrollContainer = ref(null)
const tagDraft = ref('')
const tagError = ref('')
const furthestStep = ref(0)
const emptyQuestionForm = () => ({
  title: '',
  bankType: '',
  category: '',
  difficulty: '中等',
  questionType: '',
  tags: [],
  tagsText: '',
  content: '',
  answer: '',
  options: defaultOptions(),
  codingLanguage: 'python',
  codingFunctionName: 'solution',
  codingSignature: '',
  codingTemplate: buildDefaultTemplate('solution', 'python'),
  codingParameterCount: 1,
  codingTests: [createCodingTestRow({}, 0)],
})
const emptyAiForm = () => ({
  topic: '',
  bankType: '',
  category: '',
  difficulty: '中等',
  questionType: '',
  language: 'python',
  count: 3,
  requirements: '',
  sourceUrl: '',
  documentName: '',
  documentText: '',
})
const form = reactive(emptyQuestionForm())
const aiForm = reactive(emptyAiForm())
const aiCandidates = ref([])

const busy = computed(() => saving.value || documentReading.value)
const isEditing = computed(() => Boolean(editingId.value))
const activeBankType = computed(() => form.bankType || aiForm.bankType)
const activeQuestionLabel = computed(() => (activeBankType.value === 'leetcode' ? '算法题' : '问答题'))
const modalTitle = computed(() => `${isEditing.value ? '编辑' : '新增'}${activeQuestionLabel.value}`)
const modalDescription = computed(() =>
  activeBankType.value === 'leetcode'
    ? isEditing.value
      ? '修改算法题面、代码入口、测试用例和解题思路。'
      : '维护可执行的算法题，也可基于题面资料智能生成后人工审核。'
    : isEditing.value
      ? '修改问答题型、题干、选项和参考答案。'
      : '维护知识问答题，可选择简答、单选或多选，也可使用 AI 辅助生成。',
)
const modalSubmitText = computed(() =>
  modalMode.value === 'manual'
    ? isEditing.value
      ? '保存修改'
      : '保存题目'
    : `确认导入 ${aiCandidates.value.length} 道题`,
)
const isChoiceForm = computed(() => isChoiceType(form.questionType))
const codingHighlightLanguage = computed(() =>
  detectCodeLanguage(`${form.codingTemplate}\n${form.content}`, form.codingLanguage),
)
const formTags = computed(() => tagLabels({ tags: form.tags }))
const difficultyOptions = ['简单', '中等', '困难']
const supportedQaQuestionTypes = ['简答', '单选', '多选']
const qaQuestionTypeOptions = computed(() => {
  const configured = props.questionTypeOptions
    .map((item) => (typeof item === 'string' ? item : item?.value))
    .filter((item) => supportedQaQuestionTypes.includes(item))
  return Array.from(new Set([...supportedQaQuestionTypes, ...configured]))
})
const manualSteps = computed(() =>
  form.bankType === 'leetcode'
    ? [
        { key: 'basic', label: '基本信息', description: '算法主题与难度' },
        { key: 'content', label: '题面与代码', description: '约束与函数入口' },
        { key: 'answer', label: '判题与题解', description: '测试用例与思路' },
      ]
    : [
        { key: 'basic', label: '基本信息', description: '知识分类与题型' },
        {
          key: 'content',
          label: isChoiceForm.value ? '题干与选项' : '问答题干',
          description: isChoiceForm.value ? '问题与备选答案' : '问题与回答范围',
        },
        { key: 'answer', label: '答案设置', description: isChoiceForm.value ? '正确选项' : '参考答案与评分点' },
      ],
)
const aiSteps = computed(() =>
  aiForm.bankType === 'leetcode'
    ? [
        { key: 'settings', label: '生成设置', description: '算法主题与难度' },
        { key: 'requirements', label: '生成来源', description: '题面、链接与要求' },
        { key: 'review', label: '审核入库', description: '题面、代码与用例' },
      ]
    : [
        { key: 'settings', label: '生成设置', description: '知识主题与题型' },
        { key: 'requirements', label: '参考资料', description: '知识材料与要求' },
        { key: 'review', label: '审核入库', description: '题干、选项与答案' },
      ],
)
const manualBasicHint = computed(() =>
  form.bankType === 'leetcode'
    ? '先确定算法主题和难度，下一步再维护题面、代码入口和约束。'
    : '先确定知识分类和问答题型，后续字段会按简答或选择题自动调整。',
)
const manualTitleLabel = computed(() => (form.bankType === 'leetcode' ? '算法题标题' : '问答题标题'))
const manualTitlePlaceholder = computed(() =>
  form.bankType === 'leetcode'
    ? '例如：合并重叠区间'
    : isChoiceForm.value
      ? '例如：以下关于 Agent 工具权限的说法哪项正确'
      : '例如：解释 Agent 工具调用的失败恢复机制',
)
const manualTitleHint = computed(() =>
  form.bankType === 'leetcode'
    ? '建议直接描述待解决的算法任务，避免使用知识点名称充当题目。'
    : '建议明确考查对象和回答目标，便于检索与复习。',
)
const manualCategoryPlaceholder = computed(() =>
  form.bankType === 'leetcode' ? '例如：数组与排序' : '例如：Agent 工程',
)
const manualTagPlaceholder = computed(() =>
  form.bankType === 'leetcode' ? '输入一个标签后按回车，例如 双指针' : '输入一个标签后按回车，例如 工具调用',
)
const manualQuestionTypeHint = computed(() =>
  isChoiceForm.value ? '选择题需要在下一步维护至少两个选项。' : '简答题需要在最后一步维护参考答案和评分要点。',
)
const manualContentSectionLabel = computed(() =>
  form.bankType === 'leetcode' ? '算法题面与代码入口' : isChoiceForm.value ? '问答题干与选项' : '问答题干',
)
const manualContentHint = computed(() =>
  form.bankType === 'leetcode'
    ? '写清任务、输入输出、约束和示例，再配置可执行的函数入口。'
    : isChoiceForm.value
      ? '题干与选项分开维护，避免在题干中重复录入选项。'
      : '写清问题、回答范围和必要背景，支持 Markdown 预览。',
)
const manualContentPlaceholder = computed(() =>
  form.bankType === 'leetcode'
    ? '请说明算法任务、输入输出、约束条件和至少一个示例'
    : isChoiceForm.value
      ? '请输入完整题干，不要在这里重复填写选项'
      : '请输入需要回答的问题，可补充回答范围或评分要求',
)
const manualAnswerLabel = computed(() => (form.bankType === 'leetcode' ? '解题思路 / 参考答案' : '参考答案 / 评分要点'))
const manualAnswerPlaceholder = computed(() =>
  form.bankType === 'leetcode'
    ? '支持 Markdown，可填写核心算法、正确性说明、示例代码和复杂度分析'
    : '请填写参考答案，并列出判分时必须覆盖的核心知识点',
)
const aiSettingsHint = computed(() =>
  aiForm.bankType === 'leetcode'
    ? '确定算法范围、难度和代码语言；模型只生成候选题，不会自动入库。'
    : '确定知识范围、题型和难度；模型只生成候选题，不会自动入库。',
)
const aiTopicLabel = computed(() => (aiForm.bankType === 'leetcode' ? '算法主题' : '知识主题'))
const aiTopicPlaceholder = computed(() =>
  aiForm.bankType === 'leetcode' ? '例如：动态规划、图论、二分查找' : '例如：Agent 工程、RAG 与模型评测',
)
const aiCategoryPlaceholder = computed(() => (aiForm.bankType === 'leetcode' ? '例如：动态规划' : '例如：Agent 工程'))
const aiUploadHint = computed(() =>
  aiForm.bankType === 'leetcode'
    ? '支持 PDF / DOC / DOCX / TXT / MD / JSON / CSV，可上传算法题、题解或样例数据'
    : '支持 PDF / DOC / DOCX / TXT / MD / JSON / CSV，可上传技术文档、面试笔记或岗位要求',
)
const aiDocumentReadingText = computed(() =>
  aiForm.bankType === 'leetcode' ? '正在提取算法题资料，请稍候' : '正在提取问答知识资料，请稍候',
)
const aiSourceTextLabel = computed(() => (aiForm.bankType === 'leetcode' ? '题面 / 参考文本' : '知识资料 / 参考文本'))
const aiSourceTextPlaceholder = computed(() =>
  aiForm.bankType === 'leetcode'
    ? '可粘贴已合法取得的题面、输入输出说明和样例；上传文档后也可在这里继续修改'
    : '可粘贴技术文档、岗位要求、面试笔记或评分依据；上传文档后也可继续修改',
)
const aiRequirementsPlaceholder = computed(() =>
  aiForm.bankType === 'leetcode'
    ? '例如：生成 3 道动态规划算法题，覆盖状态定义、边界条件与时间复杂度分析（最多 2000 字）'
    : aiForm.questionType === '简答'
      ? '例如：答案需要覆盖定义、适用场景、常见误区和评分要点（最多 2000 字）'
      : '例如：每题提供 4 个互斥选项，干扰项应来自常见误区（最多 2000 字）',
)
const wizardSteps = computed(() => (modalMode.value === 'manual' ? manualSteps.value : aiSteps.value))
const isLastStep = computed(() => currentStep.value === wizardSteps.value.length - 1)
const nextButtonText = computed(() => (modalMode.value === 'ai' && currentStep.value === 1 ? '生成候选题' : '下一步'))
const reviewedCandidateCount = computed(() => aiCandidates.value.filter((candidate) => candidate.confirmed).length)
const generationSummary = computed(() => {
  const count = Number(aiForm.count) > 0 ? `${aiForm.count} 道` : '待填写数量的'
  const difficulty = aiForm.difficulty || '待选择难度'
  const category = aiForm.category || aiForm.topic || activeQuestionLabel.value
  const language = codingLanguageOptions.find((item) => item.value === aiForm.language)?.label || 'Python'
  return aiForm.bankType === 'leetcode'
    ? `${count}${difficulty} · ${category} · ${language} 算法题`
    : `${count}${difficulty} · ${category} · ${aiForm.questionType || '问答题'}`
})

onMounted(() => document.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))

function handleKeydown(event) {
  if (!['Escape', 'Esc'].includes(event.key)) return
  if (visible.value && !busy.value) close()
}

function openCreate(defaultBankType = 'qa') {
  const bankType = defaultBankType === 'leetcode' ? 'leetcode' : 'qa'
  editingId.value = ''
  modalMode.value = 'manual'
  modalError.value = ''
  resetWizard()
  resetForm()
  form.bankType = bankType
  syncFormBankType()
  Object.assign(aiForm, emptyAiForm(), { bankType })
  aiCandidates.value = []
  syncAiBankType()
  resetTagEditor()
  visible.value = true
}
function openEdit(item) {
  editingId.value = item.questionId
  modalMode.value = 'manual'
  modalError.value = ''
  resetWizard()
  resetTagEditor()
  const meta = codingMeta(item)
  const coding = normalizeEditingCodingMeta(item, meta)
  Object.assign(form, {
    title: item.title || '',
    bankType: item.bankType || '',
    category: item.category || '',
    difficulty: item.difficulty || '',
    questionType: item.questionType || (item.bankType === 'leetcode' ? '编程题' : '简答'),
    tags: tagLabels(item),
    tagsText: '',
    content: questionStem(item),
    answer: item.answer || '',
    options: optionItems(item).length ? optionItems(item) : defaultOptions(),
    codingLanguage: coding.language,
    codingFunctionName: coding.functionName,
    codingSignature: meta.signature || '',
    codingTemplate: coding.template,
    codingParameterCount: coding.parameterCount,
    codingTests: formatCodingTestRows(meta.tests),
  })
  furthestStep.value = manualSteps.value.length - 1
  visible.value = true
}
function normalizeEditingCodingMeta(item, meta) {
  if (item.bankType !== 'leetcode' && item.questionType !== '编程题') {
    return { language: '', functionName: '', template: '', parameterCount: '' }
  }
  const language = normalizeCodingLanguage(meta.language)
  const functionName = meta.functionName || extractFunctionName(meta.template, language) || 'solution'
  const template = meta.template || buildDefaultTemplate(functionName, language)
  const storedCount = Number(meta.parameterCount)
  const sampleArgs = Array.isArray(meta.tests) ? meta.tests.find((test) => Array.isArray(test?.args))?.args : null
  const inferredCount = Array.isArray(sampleArgs) ? sampleArgs.length : 0
  const parameterCount = Number.isInteger(storedCount) && storedCount >= 1 ? storedCount : inferredCount || 1
  return { language, functionName, template, parameterCount }
}
function close() {
  if (busy.value) return
  visible.value = false
}
function resetForm() {
  Object.assign(form, emptyQuestionForm())
}
function resetTagEditor() {
  tagDraft.value = ''
  tagError.value = ''
}
function addFormTag() {
  const normalized = tagDraft.value.trim()
  tagError.value = ''
  if (!normalized) return
  if (/[,，、;；\n\r\t]/.test(normalized)) {
    tagError.value = '请一次添加一个标签。'
    return
  }
  if (!formTags.value.some((tag) => tag.toLowerCase() === normalized.toLowerCase())) {
    form.tags = [...formTags.value, normalized]
  }
  tagDraft.value = ''
}
function removeFormTag(tag) {
  form.tags = formTags.value.filter((item) => item !== tag)
  tagError.value = ''
}
function handleTagKeydown(event) {
  if (event.key === 'Enter') {
    event.preventDefault()
    addFormTag()
  }
}
function resetWizard() {
  currentStep.value = 0
  furthestStep.value = 0
  contentEditorMode.value = 'edit'
  answerEditorMode.value = 'edit'
  scrollToStepTop()
}
function setModalMode(mode) {
  if (busy.value || modalMode.value === mode) return
  modalMode.value = mode
  modalError.value = ''
  if (mode === 'ai') aiCandidates.value = []
  resetWizard()
}
function scrollToStepTop() {
  nextTick(() => scrollContainer.value?.scrollTo?.({ top: 0, behavior: 'auto' }))
}
function goToStep(index) {
  if (busy.value || index < 0 || index >= wizardSteps.value.length || index > furthestStep.value) return
  modalError.value = ''
  currentStep.value = index
  scrollToStepTop()
}
function previousStep() {
  goToStep(currentStep.value - 1)
}
async function nextStep() {
  modalError.value = ''
  try {
    if (modalMode.value === 'manual') validateQuestionStep(form, currentStep.value)
    else {
      validateAiStep(aiForm, currentStep.value)
      if (currentStep.value === 1) {
        await generateAiCandidates()
        return
      }
    }
    const next = Math.min(currentStep.value + 1, wizardSteps.value.length - 1)
    furthestStep.value = Math.max(furthestStep.value, next)
    goToStep(next)
  } catch (err) {
    showSubmitError(err, modalMode.value)
  }
}
function showSubmitError(err, mode) {
  modalError.value = err.message || (mode === 'manual' ? '保存失败' : 'AI 生成失败')
  scrollToStepTop()
}
async function submitModal() {
  if (busy.value) return
  modalError.value = ''
  if (modalMode.value === 'manual') return saveQuestion()
  return importReviewedCandidates()
}
function validateManualFormAndFocusError() {
  for (let step = 0; step < manualSteps.value.length; step += 1) {
    try {
      validateQuestionStep(form, step)
    } catch (err) {
      currentStep.value = step
      throw err
    }
  }
}
async function saveQuestion() {
  saving.value = true
  try {
    validateManualFormAndFocusError()
    const payload = buildQuestionPayload(form)
    const saved = editingId.value ? await updateQuestion(editingId.value, payload) : await createQuestion(payload)
    visible.value = false
    emit('saved', saved)
  } catch (err) {
    showSubmitError(err, 'manual')
  } finally {
    saving.value = false
  }
}
function refreshCodingTemplate() {
  const functionName = String(form.codingFunctionName || '').trim() || 'solution'
  form.codingFunctionName = functionName
  form.codingTemplate = buildDefaultTemplate(functionName, form.codingLanguage)
}
function handleCodingLanguageChange() {
  refreshCodingTemplate()
}
function handleManualQuestionTypeChange() {
  modalError.value = ''
  if (isChoiceForm.value && form.options.filter((option) => String(option.text || '').trim()).length < 2) {
    form.options = defaultOptions()
  }
}
function addCodingTest() {
  form.codingTests.push(createCodingTestRow({}, form.codingTests.length))
}
function removeCodingTest(index) {
  if (form.codingTests.length <= 1) return
  form.codingTests.splice(index, 1)
}
async function generateAiCandidates() {
  saving.value = true
  try {
    validateAiForm(aiForm)
    const result = await generateQuestions(aiForm)
    const items = Array.isArray(result?.items) ? result.items : []
    if (!items.length) throw new Error('模型没有返回可审核的候选题，请调整要求后重试')
    aiCandidates.value = items.map((item, index) => ({
      id: `candidate-${index + 1}`,
      confirmed: false,
      form: candidateToQuestionForm(item),
    }))
    const reviewStep = aiSteps.value.length - 1
    furthestStep.value = reviewStep
    currentStep.value = reviewStep
    scrollToStepTop()
  } catch (err) {
    showSubmitError(err, 'ai')
  } finally {
    saving.value = false
  }
}
function candidateToQuestionForm(item) {
  const meta = codingMeta(item)
  const bankType = item.bankType === 'leetcode' ? 'leetcode' : 'qa'
  const language = normalizeCodingLanguage(meta.language || aiForm.language)
  const functionName = meta.functionName || extractFunctionName(meta.template, language) || 'solution'
  const parsedOptions = bankType === 'qa' ? optionItems(item) : []
  return {
    ...emptyQuestionForm(),
    title: item.title || '',
    bankType,
    category: item.category || aiForm.category,
    difficulty: item.difficulty || aiForm.difficulty,
    questionType: bankType === 'leetcode' ? '编程题' : item.questionType || '简答',
    tags: tagLabels(item),
    content: bankType === 'qa' && parsedOptions.length ? questionStem(item) : item.content || '',
    answer: item.answer || '',
    options: parsedOptions.length ? parsedOptions : defaultOptions(),
    codingLanguage: language,
    codingFunctionName: functionName,
    codingSignature: meta.signature || '',
    codingTemplate: meta.template || buildDefaultTemplate(functionName, language),
    codingParameterCount: Number(meta.parameterCount || 1),
    codingTests: formatCodingTestRows(meta.tests),
  }
}
function addCandidateTest(candidate) {
  candidate.form.codingTests.push(createCodingTestRow({}, candidate.form.codingTests.length))
  markCandidateChanged(candidate)
}
function handleCandidateQuestionTypeChange(candidate) {
  if (
    isChoiceType(candidate.form.questionType) &&
    candidate.form.options.filter((option) => String(option.text || '').trim()).length < 2
  ) {
    candidate.form.options = defaultOptions()
  }
  markCandidateChanged(candidate)
}
function addCandidateOption(candidate) {
  const key = String.fromCharCode(65 + candidate.form.options.length)
  candidate.form.options.push({ key, text: '' })
  markCandidateChanged(candidate)
}
function removeCandidateOption(candidate, index) {
  if (candidate.form.options.length <= 2) return
  candidate.form.options.splice(index, 1)
  candidate.form.options.forEach((item, itemIndex) => {
    item.key = String.fromCharCode(65 + itemIndex)
  })
  markCandidateChanged(candidate)
}
function removeCandidateTest(candidate, index) {
  if (candidate.form.codingTests.length <= 1) return
  candidate.form.codingTests.splice(index, 1)
  markCandidateChanged(candidate)
}
function markCandidateChanged(candidate) {
  candidate.confirmed = false
  clearCandidateError()
}
function clearCandidateError() {
  modalError.value = ''
}
function candidateConfirmLabel(candidateForm) {
  return candidateForm.bankType === 'leetcode'
    ? '已核对题干、代码和预期结果'
    : isChoiceType(candidateForm.questionType)
      ? '已核对题干、选项和正确答案'
      : '已核对题干、参考答案和评分要点'
}
function candidateTitlePlaceholder(candidateForm) {
  return candidateForm.bankType === 'leetcode'
    ? '例如：合并重叠区间'
    : isChoiceType(candidateForm.questionType)
      ? '例如：以下关于 Agent 工具权限的说法哪项正确'
      : '例如：解释 Agent 工具调用的失败恢复机制'
}
function candidateCategoryPlaceholder(candidateForm) {
  return candidateForm.bankType === 'leetcode' ? '例如：数组与排序' : '例如：Agent 工程'
}
function candidateContentPlaceholder(candidateForm) {
  return candidateForm.bankType === 'leetcode'
    ? '请核对任务、输入输出、约束条件和示例'
    : isChoiceType(candidateForm.questionType)
      ? '请核对完整题干，选项在下方单独维护'
      : '请核对问题描述、回答范围和必要背景'
}
function candidateAnswerPlaceholder(candidateForm) {
  return candidateForm.bankType === 'leetcode'
    ? '请核对核心算法、正确性说明和复杂度分析'
    : '请核对参考答案，并列出判分时必须覆盖的核心知识点'
}
async function importReviewedCandidates() {
  saving.value = true
  try {
    if (!aiCandidates.value.length) throw new Error('请先生成候选题')
    const items = aiCandidates.value.map((candidate, index) => {
      if (!candidate.confirmed) throw new Error(`请先核对并确认候选题 ${index + 1}`)
      try {
        validateQuestionForm(candidate.form)
        return buildQuestionPayload(candidate.form)
      } catch (err) {
        throw new Error(`候选题 ${index + 1}：${err.message}`)
      }
    })
    await importQuestions({ items })
    visible.value = false
    emit('saved', null)
  } catch (err) {
    showSubmitError(err, 'ai')
  } finally {
    saving.value = false
  }
}
function addOption() {
  const key = String.fromCharCode(65 + form.options.length)
  form.options.push({ key, text: '' })
}
function removeOption(index) {
  if (form.options.length <= 2) return
  form.options.splice(index, 1)
  form.options.forEach((item, idx) => {
    item.key = String.fromCharCode(65 + idx)
  })
}
function syncFormBankType() {
  form.questionType = form.bankType === 'leetcode' ? '编程题' : '简答'
}
function syncAiBankType() {
  aiForm.questionType = aiForm.bankType === 'leetcode' ? '编程题' : '简答'
}
async function handleAiDocumentChange(event) {
  modalError.value = ''
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || busy.value) return
  documentReading.value = true
  try {
    validateFile(file, '参考资料', {
      extensions: ['txt', 'md', 'markdown', 'json', 'csv', 'pdf', 'doc', 'docx'],
      maxBytes: 20 * 1024 * 1024,
    })
    const result = await extractInterviewDocument(file)
    aiForm.documentName = result?.fileName || file.name
    aiForm.documentText = String(result?.text || '')
    documentNotice.value = result?.truncated ? `原文共 ${result.characterCount} 个字符，已提取前 20000 个字符。` : ''
  } catch (err) {
    modalError.value = err.message || '参考资料读取失败，请确认文档格式和内容完整'
    scrollToStepTop()
  } finally {
    documentReading.value = false
  }
}
function clearAiDocument() {
  if (busy.value) return
  aiForm.documentName = ''
  aiForm.documentText = ''
  documentNotice.value = ''
}

defineExpose({ openCreate, openEdit })
</script>
