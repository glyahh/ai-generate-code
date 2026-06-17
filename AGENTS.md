# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Repository layout

> **Project overview (Chinese):** see [README.md](README.md) — this is an AI-powered conversational app synthesis & artifact delivery platform (对话式 AI 应用合成与制品交付平台). MIT licensed.

- **Backend:** single Maven module at repo root (`pom.xml`); not a multi-module parent POM.
- **Frontend:** sibling directory `ai-generate-code-frontend/` (Vue 3 + Vite); not built by the root `pom.xml`. **UI conventions:** `ai-generate-code-frontend/frontend-design.md` — this is the **authoritative frontend guide**; read it before any frontend work (tech stack, routes, component tree, data flow, API generation, known gaps like unregistered route guard).
- **Ops / utilities:** `Platform-Utils/script/` (DB/temp/workflow cleanup scripts), `Platform-Utils/model/` (optional GUI/helpers around `application.yml` / `application-local.yml` model names). See each folder’s `readme.txt` / `README.md`.
- **SQL:** `sql/` — `create_table.sql`, `conversation_memory_tables.sql`, `conversation_memory_state_add_file_notes_json.sql`, `memory_shrink.sql`, `alter_user_account_case_sensitive.sql`, `alter_app_add_is_beta.sql`. Session-memory tables are also created at runtime via `DbSchemaGuardRunner` / mapper DDL where applicable.
- **Learn / architecture notes:** `learn/` (Chinese deep-dives, e.g. parser-saver, conversation memory, iframe preview).
- **Plans (non-runtime):** `docs/PLANS/` — especially conversation-memory V4 as-is vs to-be.
- **Agent skills (optional):** `.cursor/commands/` — project-specific workflows invoked via slash commands. See [Agent skills ecosystem](#agent-skills-ecosystem) for the full reference table. There are also parallel skill definitions in `.agents/skills/` (Cursor native) and `.cursor/skills/` — see the skills table below.
- **Superpowers (近期新增):** `docs/superpowers/` — 前端 UI 交互增强的 specs/plans，如工具卡片折叠、工具状态随机阈值等。见 [Planning / product notes](#planning--product-notes)。

## Build & Run Commands

```bash
# Compile only (no tests) — fast check for compilation errors
./mvnw -q -DskipTests compile

# Build (Unix/macOS/Git Bash)
./mvnw clean package

# Run (dev)
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run single test class (full package path or simple class name)
./mvnw test -Dtest=ClassName

# Run single test method
./mvnw test -Dtest="ClassName#methodName"

# Run all tests in a package
./mvnw test -Dtest="com.dbts.glyahhaigeneratecode.LangGraph4j.**"

# Install dependencies
./mvnw install
```

**Windows (PowerShell):** use `.\mvnw.cmd` instead of `./mvnw` for the same goals.

Server runs on **port 8124**, context path **`/api`**. OpenAPI JSON: **`/api/v3/api-docs`**. Knife4j UI is typically **`/api/doc.html`** (verify in running app).

### Frontend (separate project in `ai-generate-code-frontend/`)

```bash
cd ai-generate-code-frontend
npm install
npm run dev          # Dev server (Vite default port, usually 5173)
npm run build        # type-check + vite build
npm run lint         # oxlint + eslint
npm run format       # prettier on src/
npm run openapi2ts   # runs `openapi` CLI from openapi-ts-request; needs backend up
```

Node requirement: **^20.19.0 || >=22.12.0** (see `package.json` `engines`).

**OpenAPI → TypeScript:** `openapi-ts-request.config.ts` points `schemaPath` at `http://localhost:8124/api/v3/api-docs`, outputs to `src/api/`, and uses `requestLibPath: @/request` (Axios wrapper in `src/request.ts`). Dev proxy in `vite.config.ts` forwards `/api` and `/static` to `http://localhost:8124`.

**Frontend layout (high level):**

| Area | Path | Notes |
|------|------|--------|
| Pages | `src/page/` | `App/` (`AppChatView` — core chat/SSE, `CodeGenerateEntry`, `AppEditView`), `HomeView.vue`, `MainIndex/`, `Admin/`, `User/` |
| API clients | `src/api/` | OpenAPI-generated controllers (app, chatHistory, chatToGenCode, staticResource, user, test) |
| Chat / workflow UX | `src/utils/workflowChatFilters.ts`, `toolOutputAdapters/`, `appGenPipeline.ts` | SSE parsing, workflow cards, pipeline badges |
| Preview / edit | `src/utils/visualWebsiteEditor.ts` | iframe preview helpers |
| State | `src/stores/`, `src/access/` | Pinia login (`UserLogin.ts`), route access |
| Layout | `src/layouts/BasicLayout.vue` | Top bar + content + footer slots |
| Shared | `src/components/` | `GlobalHeader.vue`, `GlobalFooter.vue`, `CodeBlock.vue` (code highlight/copy/streaming) |

**Routes** (Vue Router 5, `createWebHistory`):

| path | name | Component |
|------|------|-----------|
| `/` | home | `page/HomeView.vue` |
| `/user/login` | user-login | `page/User/UserLogin.vue` |
| `/user/settings` | user-settings | `page/User/UserSettings.vue` |
| `/admin/users` | admin-users | `page/Admin/AdminHome.vue` |
| `/admin/apply` | admin-apply | `page/Admin/AdminApplyManage.vue` |
| `/admin/apps` | admin-apps | `page/Admin/AdminAppManage.vue` |
| `/admin/chats` | admin-chats | `page/Admin/AdminChatManage.vue` |
| `/code/generate` | code-generate | `page/App/CodeGenerateEntry.vue` |
| `/app/:id/chat` | app-chat | `page/App/AppChatView.vue` |
| `/app/:id/edit` | app-edit | `page/App/AppEditView.vue` |
| `/user/chats` | user-chats | `page/User/UserChatHistory.vue` |
| `/main_index` | main-index | `page/MainIndex/MainIndexView.vue` |

**Env vars:** `VITE_DEPLOY_DOMAIN`, `VITE_API_BASE_URL` (default `/api`), `VITE_OSS_ORIGIN`. Proxy in `vite.config.ts` forwards `/api` and `/static` to `http://localhost:8124`.

**Data flow:** Axios wrapper (`src/request.ts`) uses `baseURL: /api`, `withCredentials: true`, 600s timeout, auto-transforms large integers via `transformLongToString`; 401 → login redirect. No test framework configured (no Vitest/Playwright).

## Architecture Overview

Spring Boot **3.5.10** / Java **21** backend for an AI-powered code generation platform. Users chat with AI to generate web apps (single HTML, multi-file HTML/CSS/JS, or Vue 3 projects), persisted to disk and deployable as static sites.

### Code generation entrypoints (two paths)

1. **Traditional (LangChain4j tools + parser/saver):** `chatToGenCode` → `AiCodeGeneratorFacade` → `CodeParserExecutor` / `CodeFileSaverExecutor`.
2. **Workflow (LangGraph4j):** `chatToGenCodeByWorkflow` → `WorkflowCodeGeneratorFacade` (graph: e.g. image collection → prompt enhancement → router → code generator → quality check → conditional retry / Vue / skip → optional project builder) → stream handling via `StreamHandlerExecutor` (e.g. `WorkflowTextStreamHandler` when `workflowMode` is on).

**HTTP / SSE:** `ChatToGenCodeController` exposes streaming endpoints under `/chat`:

- `GET /gen/code` — traditional generation
- `GET /gen/workflow` — LangGraph4j workflow

Both return `TEXT_EVENT_STREAM` with `ServerSentEvent` payloads; workflow steps may emit `[workflow] 第 N 步完成：…` chunks parsed for UI cards. Stream ends with a `done` event (see controller `toSseEvent`).

**Output locations (runtime convention):**

- Generated code: **`{user.dir}/temp/code_output/{type}_{snowflakeId}/`** (see `AppConstant` and controllers; some saver paths may still use environment-specific literals—check `core/saver` when debugging path issues).
- Deployed sites: **`{user.dir}/temp/code_deploy/{deployKey}/`**

### Parser / Saver Pattern (`core/`)

**Parser layer** (`core/parser/`): `CodeParser<T>` → concrete parsers (`HtmlCodeParser`, `MultiFileCodeParser`, …) → `CodeParserExecutor` dispatches by `CodeGenTypeEnum`.

**Saver layer** (`core/saver/`): `CodeFileSaverTemplate<T>` (template method) → concrete savers → `CodeFileSaverExecutor` dispatches by `CodeGenTypeEnum`.

**To add a new code generation type:** add a `CodeGenTypeEnum` value, implement a parser + saver, register both executors.

Related internal docs: `learn/ARCHITECTURE-Parser-Saver-重构模式总结.md`.

### Stream Handlers (`core/handler/`)

Two key handlers bridge SSE streaming from the AI service to the controller:

| Handler | Role |
|---------|------|
| `SimpleTextStreamHandler` | Traditional code generation path — accumulates AI token stream, triggers parser+saver on completion |
| `WorkflowTextStreamHandler` | LangGraph4j workflow path — emits `[workflow] 第 N 步完成：…` progress chunks parsed by the frontend for step cards |

Both implement a common pattern: collect streaming tokens → detect completion/error → finalize (parse/save/notify).

### Conversation memory V4 (dual-track context)

Long conversations use **two cooperating layers**—do not conflate them:

| Track | Storage | Role in LLM context |
|-------|---------|---------------------|
| **A — Chat timeline** | MySQL `chat_history` + Redis LangChain4j `ChatMemoryStore` (memoryId = `appId`) | Primary window: USER/AI/SYSTEM/TOOL messages; preloaded up to **40** rows (`ChatHistoryConstant.MEMORY_PRELOAD_MESSAGE_ROWS`), window cap **80** messages |
| **B — Project memory state** | MySQL `conversation_memory_state`, `snapshot_history`, `conversation_memory_ref` + Redis `cm:state:{appId}`, `cm:ref:{refId}` | Summaries, changed-file index, fileNote map, manifest snapshots, large-file refs |

**Read path (before each generation):** `aiCodeGeneratorServiceFactory.getAiCodeGeneratorService` → `ChatHistoryService.loadConversationMemoryStateAndInject` → `ConversationMemoryStateService.loadConversationMemoryStateAndInject`:

1. `turnHistoryToMemory` — DB → Redis chat window  
2. Optional `trySummarizeOldestRoundsIfNeeded` — merge oldest rounds in DB when round count exceeds threshold  
3. `compactMemoryMessagesIfNeeded` — per-message truncation for oversized AI blobs  
4. Inject tagged `SystemMessage`s: `[memory_policy]`, `[memory_index]`, `[memory_file_note]`, then disk `[memory_inject]` file heads for changed paths (priority from `changedFilesJson`)

**Write path (after SSE completes):** `ChatHistoryService.onRoundCompleted` → `ConversationMemoryStateService.onRoundCompleted` — stable directory poll → manifest diff → `snapshot_history` → upsert `conversation_memory_state` → archive large changed files to `conversation_memory_ref` → refresh `cm:state`.

**fileNote (way 2):** after `FileWriteTool` / `FileModifyTool` persist to disk, `ConversationMemoryFileNoteService` debounces and batch-summarizes paths via dedicated `file-note-chat-model` (`FileNoteChatModelConfig`); merged into `fileNotesJson` on state. Support: `core/memory/ConversationMemoryFileNoteSupport`, `ConversationMemoryStateInjectSupport`.

**Other memory support classes in `core/memory/`:** `ChatHistoryEchoRedisSupport` (用户回显全文 Redis TTL 管理), `ChatAiMemoryRedisSupport` (LangChain4j ChatMemory Redis 读写桥接), `ChatHistoryAiMemoryRebuildSupport` (从 MySQL 重建 AI memory), `AiMemoryTimelineItem` (memory 时间线数据结构), `ConversationMemoryInjectTexts` (注入标签文本常量).

**Config:** `conversation.memory.*` in `application.yml`, bound by `ConversationMemoryProperties` (TTLs, ref governance, manifest retry, fileNote limits).

**Known gaps (check code TODOs before assuming behavior):** `softSummary` / `hardSummary` are persisted but **not yet injected** into chat memory (`ConversationMemoryStateServiceImpl`); `cm:ref` read-back into prompts is incomplete vs target doc. Authoritative as-is diagram: `docs/PLANS/conversation-memory-v4-current-architecture.md`. Target: `docs/PLANS/conversation-memory-v4-target-architecture.md`. Learning guide: `learn/会话记忆指南（会话记忆重构V4学习复盘）.md`.

### AI Service Layer (`ai/`)

- **`aiCodeGeneratorServiceFactory`** — per-app LangChain4j services, Caffeine-cached (~30min write / ~10min access TTL). On cache miss: preload history, run conversation-memory inject, then serve streaming/non-streaming calls.
- **Models** — multi-model routing configured in `application.yml` and bound by config classes in `config/`:

  | Model bean | Model name (example) | Role |
  |------------|----------------------|------|
  | `chat-model` | `${langchain4j.open-ai.chat-model.model-name}` | Summaries, workflow images, non-stream calls |
  | `streaming-chat-model` | `qwen3.6-plus-2026-04-02` | HTML / multi-file SSE stream |
  | `reasoning-streaming-chat-model` | `qwen3.6-35b-a3b` | Vue 3 project stream |
  | `routing-chat-model` | `qwen3.6-flash-2026-04-16` | Lightweight classification routing |
  | `code-exam-chat-model` | `qwen3.6-plus-2026-04-02` | Workflow quality check |
  | `file-note-chat-model` | `gui-plus-2026-02-26` | Batch file-note summarization |

  Configuration classes: `StreamChatModelConfig`, `ReasoningChatModelConfig`, `RoutingAiModelConfig`, `CodeExamChatModelConfig`, `FileNoteChatModelConfig`.
- **`ToolManager`** (`ai/tool/`) — file tools: `FileReadTool`, `FileWriteTool`, `FileModifyTool`, `FileDeleteTool`, `FileDirReadTool`, `VueSfcSyntaxCheckFixTool`, `ExitTool`; Vue projects may restrict tools (e.g. `writeFile` only on first round). Write/modify tools trigger fileNote scheduling when enabled. Tools are organized under `ai/tool/tools/` with support utilities in `ai/tool/support/`.
- **Chat memory:** `MessageWindowChatMemory` + Redis store (`RedisChatMemoryStoreConfig`).
- **Legacy / parallel compaction:** `ChatHistorySchemaMigrationSupport` — round-level AI summary (`summarizeTwoRoundsWithAi`) and message-level truncation constants in `ChatHistoryMemoryCompactionConstant`.

### LangGraph4j (`LangGraph4j/`)

- **`CodeGenWorkflow`** — workflow graph implementation (**langgraph4j-core** **1.8.3**). Node pipeline order: `ImageCollectorNode` → `PromptEnhancerNode` → `RouterNode` → `CodeGeneratorNode` → `CodeQualityCheckNode` → (conditional retry / proceed) → `ProjectBuilderNode`.
  - `node/`: 6 node classes, each implementing `Node<WorkflowContext>`.
  - `state/`: `WorkflowContext` (shared state across nodes), `QualityResult` (quality-check outcome).
  - `tools/`: `ImageSearchTool` (Pexels), `LogoGeneratorTool`, `MermaidDiagramTool`, `UndrawIllustrationTool` — workflow-specific tools, separate from the main `ToolManager`.
  - `ai/`: AI service integration for workflow nodes.
  - `enums/`: workflow-specific enums (generation type, node names, edge conditions).
- **`WorkflowCodeGeneratorFacade`** — adapts graph execution to `Flux<String>` for SSE.
- Tests live under `src/test/java/.../LangGraph4j/` (unit, stream bridge, retry, integration-style `@SpringBootTest` classes).

### Vendored LangChain4j patches (`src/main/java/dev/langchain4j/`)

The repo vendors a **small subset** of LangChain4j classes to fix tool-stream / SSE behavior:

| Vendored class | Role |
|----------------|------|
| `model.chat.StreamingChatModel` / `.response.StreamingChatResponseHandler` | Streaming contract |
| `service.TokenStream` / `AiServiceTokenStream` / `AiServiceStreamingResponseHandler` | Token stream bridging |
| `model.openai.OpenAiStreamingResponseBuilder` / `OpenAiStreamingChatModel` | OpenAI streaming patch |
| `internal.ToolExecutionRequestBuilder` | Tool execution request wiring |

**Spring Boot DevTools is disabled** in `pom.xml` so these classes override dependency JARs at runtime (devtools uses a separate classloader that would load the original JAR classes instead). Do not re-enable devtools without understanding this conflict.

### Supporting subsystems

- **`rateLimiter/`** — `@RateLimit` + AOP; `ChatToGenCodeController` uses **5 requests / 60s per user** on streaming generation methods. Redisson-backed distributed rate limiter.
- **`guardrail/`** — prompt safety / output guardrails integrated with LangChain4j config.
- **`AOP/` + `annotation/`** — `@MyRole` and `Authorities` for authorization.
- **`Listener/ai/`** — optional streaming chat model diagnostics.
- **`manage/OssManager`** + **`config/OssClientConfig`** — screenshot / asset upload to Alibaba OSS.
- **Pexels API** (`pexels.api-key` in config) — image search used by workflow image-collection step (see `image_generate_and_choose_prompt.txt`).
- **`core/util/LegacyHtmlStreamIntegrity`**, **`LegacyHtmlToolStreamSupport`** — legacy single-file HTML streaming integrity.
- **`core/util/ChatHistorySchemaMigrationSupport`** — round-level AI summary compaction (`summarizeTwoRoundsWithAi`); large (~39KB) utility for merging oldest rounds when round count exceeds threshold. See `ChatHistoryConstant.MEMORY_PRELOAD_MESSAGE_ROWS` / window caps.
- **`core/util/EditRoundStreamFenceFilter`** — filters out Markdown code-fence content from model streaming output during edit rounds, keeping only the natural-language commentary outside fences.
- **`core/util/MultiFileStructureSupport`** — detects when multi-file HTML output has degenerated into a monolithic inline-styled single file (missing `style.css` / `script.js` references).
- **`config/DbSchemaGuardRunner`** — startup schema guards for chat/memory-related DDL.
- **`config/SchedulingConfig`** — enables `@Scheduled` ref/snapshot cleanup in `ConversationMemoryStateServiceImpl`.

### System Prompts

Directory: `src/main/resources/Prompt/`

- `Single_File_Prompt.txt` — HTML single-file generation  
- `Single_File_Prompt_Modify.txt` — HTML single-file edit/modify rounds
- `Various_File_Prompt.txt` — multi-file HTML/CSS/JS  
- `Various_File_Prompt_Modify.txt` — multi-file edit/modify rounds
- `Vue_File_Prompt.txt` — Vue 3 project generation  
- `Vue_File_Prompt_Modify.txt` — Vue 3 project edit/modify rounds
- `Generate_Code_Enum_Routine.txt` — enum / routing routine  
- `Code/` — subdirectory for generation-type-specific routine prompts
- Additional workflow- or feature-specific prompts: `code_exam.txt` (quality check), `image_generate_and_choose_prompt.txt` (workflow image generation), `all_picture_search_plan.txt` (image search planning), `file_note_batch.txt` (batch file-note summarization) — open the file when editing that feature.

### REST controllers (base paths are under `server.servlet.context-path`)

| Controller | Base path | Notes |
|------------|-----------|--------|
| `ChatToGenCodeController` | `/chat` | SSE code + workflow generation |
| `AppController` | `/app` | apps, deploy/undeploy, download, admin flows |
| `UserController` | `/user` | auth and user CRUD |
| `ChatHistoryController` | `/chatHistory` | history, export, admin |
| `StaticResourceController` | `/static` | preview under `temp/code_output` |
| `DeployResourceController` | `/deploy` | deployed static sites under `temp/code_deploy` |
| `Test` | `/test` | local/dev smoke endpoint — do not rely on in production |

### Authorization

- **AOP** via `@MyRole` + `Authorities` aspect.
- **Session-based auth:** `userService.getUserInSession()`; Spring Session + Redis.
- **Roles:** `USER` / `ADMIN` (`UserRoleEnum`).
- No Spring Security — custom AOP authorization.
- **Known security gap:** `src/access/access.ts` implements a `beforeEach` route guard (admin routes → `admin` role; `/user/settings` → logged in), but **is never imported** in `main.ts` or `router/index.ts` — the guard is effectively not registered. Actual frontend auth relies on `src/request.ts` 40100 redirects and per-page checks. Fix this if adding route-level protection.

### Deployment System

- `AppController` → deploy generates `deployKey`, copies artifacts to `temp/code_deploy/{deployKey}/`.
- `DeployResourceController` serves that tree with content-type detection.
- `StaticResourceController` serves preview assets from output tree.

### Database & MyBatis-Flex

- Entities: `model/Entity/`
- DTOs: `model/DTO/`
- VOs: `model/VO/`
- Enums: `model/enums/`
- Memory-specific models: `model/memory/`
- Mapper interfaces: `mapper/`
- XML: `src/main/resources/mapper/` — `AppMapper.xml`, `ChatHistoryMapper.xml`, `UserMapper.xml`, `ConversationMemoryStateMapper.xml`, `SnapshotHistoryMapper.xml`, `SchemaMetadataMapper.xml`, `ConversationMemoryRefMapper.xml`

**Tables (domain):**

| Table | Purpose |
|-------|---------|
| `user`, `app`, `chat_history`, `user_app_apply` | Core product domain (Snowflake IDs, logic delete **`isDelete`**) |
| `conversation_memory_state` | Per-app soft/hard summary, `changedFilesJson`, `fileNotesJson`, latest round/snapshot pointers |
| `snapshot_history` | Per-round manifest (path/hash/size/mtime), no full file bodies |
| `conversation_memory_ref` | Large archived snippets (`refId`, optional `filePath`, `content`) |

`roundId` in memory tables = **`chat_history.id`** for that generation round. ER diagrams: `learn/数据库表结构图-会话记忆与核心域.md`.

### MyBatis-Flex code generator (offline)

- **Entry:** `src/main/java/com/dbts/glyahhaigeneratecode/MybatisFlexGenerateSource/MyBatisCodeGenerator.java`
- Reads **`application-local.yml`** from the working directory (Hutool `YamlUtil`) for `spring.datasource` URL/username/password.
- **`TABLE_NAMES`** — edit this array for which tables to generate (default in repo: `chat_history`).
- Generated package base (for generated output): `com.dbts.glyahhaigeneratecode.MybatisFlexGenerate` — typically generate to a temp folder then move artifacts into the real packages.

Run as a normal `main` after fixing `TABLE_NAMES` and ensuring `application-local.yml` exists locally.

### Backend package map (Java root `com.dbts.glyahhaigeneratecode`)

| Package | Responsibility |
|---------|----------------|
| `controller` | REST + SSE |
| `service` / `service.impl` / `service.support` | Business orchestration (chat, app, memory, deploy) — impl/ for MyBatis-Flex implementations, support/ for helper classes |
| `core` | Facades, parser/saver template pattern (`parser/`, `saver/`), stream handlers (`handler/`), `core/memory/*` inject helpers, `core/util/`, context builders for edit rounds (`context/`), Vue project builder (`Builder/`) |
| `ai` | LangChain4j services, tools, factory |
| `LangGraph4j` | Workflow graph |
| `mapper` / `model` | Persistence — `Entity/`, `DTO/`, `VO/`, `enums/`, `memory/` sub-packages |
| `config` | Beans, Redis, OSS, model routing, `ConversationMemoryProperties` |
| `constant` | Thresholds (`ChatHistoryConstant`, `ConversationMemoryConstant`, …) |
| `common` | Shared DTOs: `BaseResponse<T>`, `ResultUtils`, `PageRequest`, `DeleteRequest` |
| `utils` | Global utilities: `CacheKeyUtils`, `SpringContextUtil`, `WebScreenShotUtil` |
| `manage` | OSS upload, screenshot asset management |
| `debug` | Debug-only endpoints / diagnostics |
| `rateLimiter`, `guardrail`, `AOP`, `annotation`, `exception` | Cross-cutting |

## Configuration

- **`src/main/resources/application.yml`** — main config; **`spring.profiles.active: local`**.
- **`application-local.yml`** — used for secrets / local overrides and by **MyBatisCodeGenerator**. This file **is tracked in git** with placeholder values; populate real credentials locally. If missing, create from team template. Includes AI API keys (`langchain4j.open-ai.*`), MySQL/Redis credentials, and Pexels API key (`pexels.api-key`).
- **`.Codex/settings.json`** — project-level pre-authorized CLI permissions (build/test/jar extraction for LangChain4j class patching) and additional working directories (`D:\pptx-work`, etc.). Local overrides in `.Codex/settings.local.json` take precedence.
- **`.cursor/settings.json`** — Cursor IDE 插件配置（`redis-development`, `firecrawl`, `linear`, `neon-postgres` 已启用）。
- **`.editorconfig`** — enforces UTF-8, LF line endings, trailing newline, trailing whitespace trim. Note: **no `indent_style` / `indent_size` is specified for Java**; the frontend sub-project sets 2-space indent in its own `.editorconfig`. **Consider adding** `indent_style = space, indent_size = 4` for `[*.java]` to root `.editorconfig` for consistent Java formatting. Currently `[*.{java,vue,ts,js,json,xml,yml,yaml,md,sql}]` only has charset/eol/trim rules — formatting depends on IDE defaults.
- **Config classes** (`config/` — 14 classes): model routing (`StreamChatModelConfig`, `ReasoningChatModelConfig`, `RoutingAiModelConfig`, `CodeExamChatModelConfig`, `FileNoteChatModelConfig`), persistence (`RedisChatMemoryStoreConfig`, `RedisCacheManagerConfig`, `ConversationMemoryProperties`), web (`CorsConfig`, `JsonConfig`, `OssClientConfig`), safety (`OpenAiOutputGuardrailsConfig`), lifecycle (`DbSchemaGuardRunner`, `SchedulingConfig`).
- MySQL URL default in `application.yml`: `jdbc:mysql://localhost:3306/gly_ai_generate_code` (credentials via placeholders).
- Redis: `localhost:6379`, session TTL entries in YAML.
- **`conversation.memory.*`** — state/ref TTL, ref retention caps, manifest stability retries, fileNote toggles (see comments in YAML).
- LangChain4j OpenAI-compatible base URL (example): Alibaba DashScope `https://dashscope.aliyuncs.com/compatible-mode/v1`; API keys via `${langchain4j.open-ai...}` placeholders.
- Session cookie max-age and Redis session store as in YAML.

## Tech Stack

| Layer | Technology |
|-------|------------|
| Framework | Spring Boot 3.5.10, Java 21 |
| ORM | MyBatis-Flex 1.11.1 + MySQL |
| Cache / session | Redis (Lettuce), Spring Session Redis, Caffeine |
| AI | LangChain4j **1.1.0**; starters **langchain4j-open-ai-spring-boot-starter**, **langchain4j-reactor**, **langchain4j-community-redis-spring-boot-starter** at **1.1.0-beta7** |
| Workflow | LangGraph4j **langgraph4j-core 1.8.3** |
| Other AI SDK | Alibaba DashScope Java SDK **2.22.9** |
| Distributed locks / rate limit infra | Redisson **3.50.0** |
| Reactive | Project Reactor (SSE / `Flux`) |
| Storage | Alibaba Cloud OSS **3.15.1** |
| Connection pool | HikariCP **4.0.3** |
| Utilities | Hutool **5.8.40**, Lombok **1.18.36**, Selenium **4.33.0** + WebDriverManager **6.1.0** (browser screenshots for app preview thumbnails) |
| API docs | Knife4j **4.4.0** (OpenAPI 3) |
| Frontend | Vue **3.5** + Vite **7** + TypeScript + Ant Design Vue **4** + Pinia **3** + Vue Router **5** + Axios + Three.js (landing visuals) |

## Tests

- **Package layout:** `src/test/java/com/dbts/glyahhaigeneratecode/` — mirrors main package structure with dedicated sub-packages for each major area:
  - `ai/` (including `ai/tool/tools/`) — AI service and tool tests
  - `constant/`, `controller/`, `mapper/`, `service/`, `service/impl/`, `utils/`
  - `core/`, `core/handler/`, `core/memory/`, `core/util/`
  - `LangGraph4j/`, `LangGraph4j/ai/`, `LangGraph4j/tools/` — workflow graph tests
  - `guardrail/` — guardrail integration tests
- **~40 test classes** across all packages (run `find src/test -name "*Test*.java"` to list).
- **Two tiers of tests:**
  - **Unit tests** (most classes) — mock external dependencies, fast to run without infrastructure.
  - **Integration-style** (`@SpringBootTest`, e.g. `*WorkflowTest.java`, `*InjectTest.java`, `*PersistenceTest.java`) — may call real beans, Redis/MySQL, or external AI APIs. Read class-level comments before running in CI without credentials; some need local infrastructure (Redis, MySQL, AI endpoint keys).
- Memory inject / state: e.g. `ConversationMemoryStateServiceImplInjectTest`.
- **No frontend tests** (no Vitest/Playwright/Cypress configured in `ai-generate-code-frontend/`).
- **Spring Boot DevTools** is intentionally commented out in `pom.xml` (see comment about classloader / overriding LangChain4j classes).

## Important: Plan-Plus rule (always applied, from `.cursor/rules/Plan-Plus.mdc`)

> Note: `.mdc` files are Cursor-specific format; Codex relies on this AGENTS.md transcription only.

**Before planning or implementing, always ask 2–5 clarifying questions** to fill context gaps — skip only when the request is already unambiguous. Question priority:

1. Goal & success criteria (what counts as done)
2. Scope & boundaries (what to do, what NOT to do)
3. Environment & constraints (stack, time, permissions, compatibility)
4. Preferences & trade-offs (speed vs quality, minimal change vs thorough)
5. Output format (checklist, steps, example code)

Allow moderate expansion marked as "扩展建议" if it doesn't deviate from the user's goal.

## Agent skills ecosystem

The repo has a rich set of project-specific workflows invoked via slash commands. Most live in `.cursor/commands/` (22 `.md` files) with supporting content in `.cursor/rules/`. Parallel definitions exist in `.agents/skills/` (Cursor native) and `.cursor/skills/` (additional skills not in commands: `backend-design`, `firecrawl-mcp`, `superpowers`, `comment-only-annotation`).

### Skills reference (which to use when)

| Skill | Trigger | When to use |
|-------|---------|-------------|
| `build-frontend` | `/build-frontend` | Frontend feature development — follows `frontend-design.md` conventions |
| `check-fronted` | `/check-fronted` | Frontend code quality / structure review |
| `check_git_code` | `/check_git_code` | Pre-commit staged diff review — read-only, 5W report, root-cause analysis |
| `code_separate` | `/code_separate` | Structural decoupling: extract logic to new Support/Helper class while preserving original code verbatim |
| `color_diff` | `/color_diff` | Color-aware diff review |
| `copy-element` | `/copy-element` | Element copying utilities |
| `cr-information` | `/cr-information` | Code review with information gathering |
| `debug` | `/debug` | Deep troubleshooting with minimal fix — read error stack, scan related code, apply smallest fix |
| `deploy` | `/deploy` | Deployment tasks |
| `exam_codex` | `/exam_codex` | Code examination / quality check |
| `git_commit` | `/git_commit` | Generate conventional commit messages from staged changes |
| `humanizer` / `humanizer_codex` | `/humanizer` | Text humanization — reduce AI-signature phrasing in comments/docs |
| `new-techno` | `/new-techno` | New technology integration / research |
| `pptx` | `/pptx` | PowerPoint generation / editing |
| `search` / `github-search` | `/search` | Code search and GitHub search workflows |
| `test` | `/test` | Test-related tasks |
| `ui` | `/ui` | General UI adjustments |
| `ui-ux-pro-max` | `/ui-ux-pro-max` | UI/UX improvements |
| `xue-xi-tong` | `/xue-xi-tong` | XueXiTong (学习通) platform integration |

以下技能存在于 `.cursor/skills/` 但不在 `.cursor/commands/` 中：

| Skill | 说明 |
|-------|------|
| `backend-design` | 后端 Java 编码规范与分层架构约束（controller → service → mapper, BaseResponse, ThrowUtils, 构造器注入） |
| `firecrawl-mcp` | Firecrawl MCP 服务集成 |
| `superpowers` | 前端超级能力（折叠卡片、hover-copy 等）——与 `docs/superpowers/` 对应 |
| `comment-only-annotation` | 注释类注解工具 |

### Key coding conventions (from `.cursor/skills/backend-design/SKILL.md`)

- All controllers return `BaseResponse<T>` via `ResultUtils.success` / `ResultUtils.error`.
- Validation uses `ThrowUtils.throwIf(condition, ErrorCode.XXX, "message")` — no `if (...) return` in controllers.
- Dependency injection: `@RequiredArgsConstructor` + `final` fields; logging: `@Slf4j`.
- **Never modify `ai-generate-code-frontend/**`** when working on backend tasks.
- For structural changes: prefer the "facade + executor + parser/saver template" pattern from `core/`.
- When refactoring: keep old implementation as `legacy*` private methods with comments, don't delete.

## Planning / product notes (non-runtime)

| Doc | Topic |
|-----|--------|
| `docs/PLANS/conversation-memory-v4-current-architecture.md` | Memory V4 as implemented |
| `docs/PLANS/conversation-memory-v4-target-architecture.md` | Memory V4 target state |
| `docs/PLANS/History_memory_redis优化计划.md` | Redis memory optimization plan |
| `docs/PLANS/Codex_plan_1.md` | Codex integration plan |
| `docs/PLANS/agent-cluster-orchestration.md` | Future multi-agent orchestration |
| `docs/PLANS/agent-cluster-modules-ABC-and-prompts.md` | Agent cluster modules / prompts |
| `docs/PLANS/README.md` | Plans directory overview / index |
| `docs/superpowers/specs/` | 前端 UI 交互增强 spec：工具卡片折叠 (2026-06-10)、工具状态随机阈值 (2026-06-11) |
| `docs/superpowers/plans/` | 与之对应的实现计划 |
| `docs/后端接口文档.md` | API reference (Chinese) |
| `docs/PLAN.md` | Higher-level product roadmap |
| `docs/check_fronted/` | Frontend QA check results |
| `learn/` | Chinese deep-dives: parser-saver pattern, conversation memory, iframe preview, DB ER diagrams |
| `sql/` | DDL: `create_table.sql`, `conversation_memory_tables.sql`, `memory_shrink.sql`, incremental alters |

## Parallel Agent 执行规则

使用 `/subagent-driven-development` 或 "子 agent 并行执行"时，必须遵守以下规则才能实现**真正的同时并行**而非串行等待：

### 三条铁律
1. **同批启动** — N 个独立子任务必须在同一条消息中一次性调用 N 个 Agent 工具同时启动，不得逐个 start→wait→next
2. **互无依赖** — 并行执行的子任务之间不能有共享状态或顺序依赖（每个 Agent 独立负责一个模块）
3. **统一汇总** — 所有 Agent 全部完成后统一合并结果，再继续下一步
