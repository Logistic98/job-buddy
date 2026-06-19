# agent-frontend

`agent-frontend` 是 `job-buddy` 的 Vue 3 + Vite 前端工作台。当前页面不再只是早期复杂问答 Demo，而是围绕求职流程组织的完整工作台，默认通过 Vite proxy 将 `/api` 转发到 `agent-backend`。

## 当前页面能力

- 登录中心与登录态维护。
- Boss 登录二维码弹窗和扫码状态轮询。
- 聊天工作台：普通问答、SSE 流式输出、会话历史、Trace/意图/计划展示。
- 简历能力：简历库、简历管理、求职画像、Boss 在线简历同步、简历写作、资源弹窗。
- 岗位能力：岗位卡片、收藏岗位、详情懒加载、岗位分析。
- 求职旅程：目标、记录、进展分析。
- 面试题库：题库、练习、笔试/机试相关页面。
- 项目深挖：项目材料维护和项目面试题生成。
- 设置中心：系统设置和记忆管理。

## 技术栈

- Vue 3
- Vite
- Pinia
- Vitest
- ESLint
- 原生 CSS

## 主要目录

```text
agent-frontend/
├── src/
│   ├── api/          # 后端接口封装
│   ├── components/   # 页面和业务组件
│   ├── stores/       # Pinia 状态
│   ├── styles/       # 全局样式
│   ├── utils/        # 简历渲染等工具
│   ├── App.vue
│   └── main.js
├── scripts/start.sh
├── Dockerfile
├── package.json
└── vite.config.js
```

## 本地启动

```bash
cd agent-frontend
npm install
npm run dev
```

默认访问：<http://localhost:5173>

默认代理目标为 `http://localhost:8080`。如需覆盖：

```bash
VITE_PROXY_TARGET=http://localhost:8080 npm run dev
```

根目录一键启动会自动注入 `FRONTEND_PORT` 和 `VITE_PROXY_TARGET`：

```bash
./scripts/start-all.sh
```

## 脚本

```bash
npm run dev      # 本地开发
npm run build    # 生产构建
npm run preview  # 预览构建产物
npm run lint     # ESLint
npm test         # Vitest
```

## 验证要求

前端普通改动至少执行：

```bash
npm run lint
npm test
npm run build
```

也可以从仓库根目录执行：

```bash
./.agent-harness/scripts/verify.sh agent-frontend --quick
```

涉及登录弹窗、Boss 扫码、SSE、岗位卡片、简历预览、会话恢复、状态管理或任何用户可见交互时，不能只跑构建和测试，还必须启动前后端并用浏览器验证关键路径。交付说明应写明访问地址、执行路径、观察结果和未覆盖项。
