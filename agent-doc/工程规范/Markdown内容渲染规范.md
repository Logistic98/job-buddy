# Markdown 内容渲染规范

## 能力目标

平台内凡是把 Markdown 转换为用户可见内容的区域，统一支持基础 Markdown、围栏代码块、Mermaid 图表和 LaTeX 数学公式。当前范围包括对话消息、面试题题干与参考答案、模拟练习、项目深挖问题，以及简历撰写器的预览、PDF 和 HTML 导出。

Mermaid 使用标准围栏语法：

````text
```mermaid
graph LR
    A[输入] --> B[处理]
    B --> C[输出]

    classDef input fill:#EAF2FF,stroke:#2563EB,color:#172554;
    classDef process fill:#F3E8FF,stroke:#7C3AED,color:#3B0764;
    classDef output fill:#ECFDF5,stroke:#059669,color:#064E3B;
    class A input;
    class B process;
    class C output;
```
````

LaTeX 同时支持 `$...$` 行内公式、`$$...$$` 块级公式、`\(...\)` 行内公式和 `\[...\]` 块级公式。代码围栏和行内代码中的 Mermaid 或 LaTeX 标记按原始代码展示，不参与二次渲染。

## 正式方案

对话、面试题库、模拟练习和项目深挖使用统一的 Vue Markdown 渲染组件。组件集中启用 Mermaid 与 KaTeX、统一不可信 HTML 的转义策略，并保留流式消息的终态和批量渲染参数。业务组件不得直接创建另一套 Markdown 渲染配置。

简历撰写器保留现有的 A4 排版 DSL 和 HTML 清洗边界。公式在 Markdown 转换时由 KaTeX 生成静态标记；Mermaid 围栏先生成不可执行的占位节点，再在清洗后的预览 DOM 中转换为 SVG。PDF 导出复用已完成渲染的预览页，HTML 导出必须把 SVG 与 KaTeX 样式内联到独立文档，保证离线打开时不依赖外部 CDN 或脚本。

Mermaid 和 KaTeX 作为前端固定版本依赖随应用构建，不从运行时 CDN 下载。Mermaid 使用严格安全级别并禁止原始 HTML 标签；所有来自 Markdown 的原始 HTML继续按转义策略显示，简历链路生成的 HTML 和 SVG 仍需经过 DOMPurify 清洗。

## 模块与接口

- `agent-frontend/src/components/MarkdownContent.vue`：通用 Markdown 渲染入口，负责基础渲染、流式状态和 Mermaid/KaTeX 开关。
- `agent-frontend/src/components/interview/PracticeMarkdown.vue`：练习与编辑预览外壳，负责空状态和代码复制反馈。
- `agent-frontend/src/components/ChatPanel.vue`：聊天消息只传递内容、终态和流式性能参数，不直接配置渲染能力。
- `agent-frontend/src/utils/markdownFeatures.js`：固定 Mermaid/KaTeX 加载器和安全配置。
- `agent-frontend/src/utils/resumeRender.js`：简历 DSL 的公式、Mermaid 占位、静态 SVG 渲染和打印样式。

该能力只改变前端展示，不修改 Markdown 存储格式、后端接口、SSE 事件、题库数据结构或简历 Workspace State。

## 风险边界

Mermaid 源码和 LaTeX 表达式均视为不可信文本。渲染失败时应显示可理解的源码或错误提示，不能执行其中的 HTML、脚本、链接事件或任意 JavaScript。图表渲染必须使用唯一 DOM 标识，避免同一页面多个消息或预览之间互相覆盖。

超长或复杂图表可能增加浏览器计算负担。通用 Markdown 渲染沿用流式批处理；简历预览只在 Markdown 内容变化后重新渲染，并在分页前等待图表完成。单个图表失败不得阻断页面其他 Markdown、简历分页或已有内容展示。

## 验证方法

自动化测试至少覆盖 Mermaid 围栏转成图表节点、行内和块级 LaTeX 转成 KaTeX 标记、普通代码围栏不被误判、原始 HTML 不执行，以及渲染失败降级。前端必须执行格式检查、ESLint、Vitest 和生产构建。

浏览器验证至少覆盖一处编辑预览和一条聊天或详情展示路径。使用包含中文节点的 Mermaid 架构图、行内公式 `$E=mc^2$` 和块级公式 `$$\sum_{i=1}^{n} i=\frac{n(n+1)}{2}$$`，确认页面出现 SVG 与 KaTeX 标记、源码围栏不再作为普通代码块显示、控制台无渲染异常，并确认现有复制代码与流式结束状态不受影响。
