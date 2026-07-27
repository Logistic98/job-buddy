# agent-frontend

`agent-frontend` 是 `job-buddy` 的 Web 工作台，采用 Vue 3 + Vite 构建，围绕完整求职流程组织页面和交互，并通过 Vite proxy 将 `/api` 转发到 `agent-backend`。

## 页面能力

- 登录与认证：账号登录、登录态恢复、Boss 二维码和扫码状态轮询。
- 聊天工作台：普通问答、SSE 流式输出、会话历史、Trace/意图/计划展示。
- 简历能力：简历库、简历管理、求职画像、Boss 在线简历同步、简历写作、资源弹窗。
- 岗位与旅程：岗位卡片、收藏与详情、岗位分析、求职目标、投递记录和进展分析。
- 面试与项目：题库、练习、隔离判题、项目材料和项目面试题生成。
- 设置中心：系统设置和记忆管理。

## 技术栈

前端使用 Vue 3、Vite 7、Pinia、Vitest、ESLint 和原生 CSS。

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

Vite 7 要求 Node.js `^20.19.0 || >=22.12.0`。本项目不依赖特定 npm 小版本，使用与所选 Node.js 版本配套的 npm 即可。

```bash
$ cd agent-frontend
$ npm install
$ npm run dev
```

默认访问：<http://localhost:5173>

环境变量统一维护在仓库根目录的 `.env` 和 `.env.example`，禁止在 `agent-frontend` 或其他子目录创建同名环境文件。首次启动前应在仓库根目录复制模板：

```bash
$ cp .env.example .env
```

默认代理目标为 `http://localhost:8080`。如需临时覆盖：

```bash
VITE_PROXY_TARGET=http://localhost:8080 npm run dev
```

另开终端并在仓库根目录执行一键启动脚本；脚本会自动注入 `FRONTEND_PORT` 和 `VITE_PROXY_TARGET`：

```bash
$ ./scripts/start-all.sh
```

## 脚本

```bash
$ npm run dev      # 本地开发
$ npm run build    # 生产构建
$ npm run preview  # 预览构建产物
$ npm run lint     # ESLint
$ npm test         # Vitest
```

## 验证要求

前端普通改动至少执行：

```bash
$ npm run format:check
$ npm run lint
$ npm test
$ npm run build
```

也可以从仓库根目录执行：

```bash
$ ./.agent-harness/scripts/verify.sh agent-frontend --quick
```

涉及登录弹窗、Boss 扫码、SSE、岗位卡片、简历预览、会话恢复、状态管理或任何用户可见交互时，不能只跑构建和测试，还必须启动前后端并用浏览器验证关键路径。交付说明应写明访问地址、执行路径、观察结果和未覆盖项。
