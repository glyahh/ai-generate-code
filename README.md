# glyahh-ai-generate-code

一个「**基于应用(App) 的 AI 代码生成平台**」：用户先创建应用并配置 `initPrompt`，再通过对话流式生成代码，系统会自动完成**解析 → 落盘 → 预览/部署**，并沉淀完整的对话历史（支持导出与自动总结）。

## 功能总览

- **应用(App) 作为生成单元**：为每个应用保存 `initPrompt`、生成类型、部署信息、优先级（精选）等。
- **对话式生成代码（SSE 流式）**：`/api/chat/gen/code` 以 `text/event-stream` 推送生成片段，前端可边收边展示。
- **代码解析器（Parser）**：将 AI 输出的原始字符串解析为结构化结果（HTML / 多文件）。
- **代码落盘器（Saver）**：将结构化结果保存到本地目录（`temp/code_output`），并生成可预览的静态产物。
- **应用部署/取消部署**：将产物复制到 `temp/code_deploy/{deployKey}`，返回可访问 URL；支持取消部署并清理部署信息。
- **静态资源预览**：`/api/static/{deployKey}/**` 直接托管部署目录下的静态文件（含 `index.html` 默认页、目录重定向）。
- **对话历史沉淀**：
  - 保存用户/AI消息、错误消息
  - 分页游标加载、按应用导出全量历史、统计对话轮数
  - **超 20 轮自动摘要**（将最早两轮总结为一轮，减少上下文与 Token 消耗）
- **权限与运营能力**：
  - 用户注册/登录/注销、会话态登录
  - 管理员：用户管理、应用管理（列表/更新/删除）
  - 精选应用：按 `priority >= 99` 对外展示
  - **申请/审核流**：普通用户可提交应用/权限申请，管理员可查看待审、同意/驳回，用户可查看申请历史

## 关键接口（后端）

> 以 `http://localhost:8124/api` 为例（以实际 `server.port` / `context-path` 为准）

- **生成代码（流式 SSE）**：`GET /api/chat/gen/code?appId={id}&message={text}`
- **应用管理**：
  - `POST /api/app/add` 创建应用（需 `initPrompt`，默认 `MULTI_FILE`）
  - `POST /api/app/update` 修改应用名
  - `POST /api/app/delete` 删除应用（并尝试级联删除该应用对话历史）
  - `POST /api/app/my/list/page/vo` 分页查询我的应用
  - `POST /api/app/good/list/page/vo` 分页查询精选应用
  - `POST /api/app/deploy` 部署应用（复制产物到 `temp/code_deploy` 并返回 URL）
  - `POST /api/app/undeploy` 取消部署（删除部署目录并清空部署信息）
  - `POST /api/app/apply` 提交申请；`/apply/list/pending` 待审；`/apply/agree` 同意；`/apply/reject` 驳回；`/apply/list/my/history` 我的申请历史
- **对话历史**：
  - `POST /api/chatHistory/save` 保存对话消息（需应用权限）
  - `GET /api/chatHistory/app/{appId}?lastCreateTime=&size=10` 游标分页加载
  - `GET /api/chatHistory/export/{appId}` 导出全量历史（用于生成本地 Markdown）
  - `GET /api/chatHistory/roundCount/{appId}` 对话轮数
  - `POST /api/chatHistory/admin` 管理员审计查询
- **静态预览**：`GET /api/static/{deployKey}/`（默认 `index.html`）

更完整的 OpenAPI 文档见：`docs/后端接口文档.md`。

## 架构亮点（Parser & Saver）

项目在 `core/parser` 与 `core/saver` 抽象出一套可扩展的“解析-落盘”管线（接口/模板/执行器三种模式复用），便于后续扩展更多代码产物类型（例如：React、Vue、SpringBoot 脚手架等）。

设计总结见：`docs/ARCHITECTURE-Parser-Saver-重构模式总结.md`。

## 目录结构（你最可能关心的）

- **后端**：`src/main/java/com/dbts/glyahhaigeneratecode/`
  - `controller/`：应用、对话生成、历史、静态资源、用户等接口
  - `core/`：`AiCodeGeneratorFacade` + parser/saver 执行器
  - `service/`：应用部署、对话历史、申请审核等业务
- **前端**：`ai-generate-code-frontend/`（Vue3 + Vite）
- **生成产物**：
  - `temp/code_output/`：生成并落盘后的可预览代码
  - `temp/code_deploy/`：部署后的静态站点目录（按 `deployKey`）

## 技术栈

- **后端**：Spring Boot 3.5.x、Java 21、MyBatis-Flex、MySQL、Redis、Spring Session、Reactor（SSE）
- **AI 能力**：LangChain4j（OpenAI-Compatible）、Redis Chat Memory Store（可配置 TTL）
- **工程**：Knife4j(OpenAPI3)、Hutool、Lombok、Caffeine
- **前端**：Vue 3 + Vite + TypeScript（见 `ai-generate-code-frontend/README.md`）

## 快速开始

### 后端

1. 准备 MySQL、Redis（用于会话与对话记忆/摘要相关能力）
2. 配置后端 `application.yml`（数据库、Redis、大模型 key/endpoint 等）
3. 启动：

```bash
./mvnw spring-boot:run
```

### 前端

```bash
cd ai-generate-code-frontend
npm install
npm run dev
```

## Roadmap（未来开发计划）

> 依据你提供的规划截图整理（可按迭代逐步勾选）

- [ ] **第八期：功能扩展**
  - [ ] 应用封面图
  - [ ] 项目下载
  - [ ] 智能路由
- [ ] **第九期：可视化修改**
  - [ ] 方案设计
  - [ ] 前端开发
  - [ ] 后端开发
- [ ] **第十期：AI 工作流**
  - [ ] LangGraph4j 调研 / 接入
  - [ ] 工作流开发
- [ ] **第十一期：系统优化**
  - [ ] 性能优化
  - [ ] 实时性优化
  - [ ] 安全限流
  - [ ] Prompt 审核/治理
  - [ ] 稳定性优化
  - [ ] 成本优化
- [ ] **第十二期：部署上线（公开）**
- [ ] **第十三期：可观测性**
  - [ ] 介绍 / 方案落地

## License

个人项目，可按需补充 License（MIT/Apache-2.0 等）。

