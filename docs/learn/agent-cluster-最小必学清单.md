# Agent 集群编排：最小必学清单

本文档从「能开工」角度收敛四条必学项，对应 `docs/PLANS/agent-cluster-orchestration.md` 中阶段 A→B 的底座能力。与厂商具体产品无关，按本仓库技术栈（Spring Boot、LangChain4j、Vue、MySQL）编写。

---

## 1. LangChain4j：动态 ChatModel + 结构化输出

**目标**：按用户配置的 `baseUrl`、`modelName`、`apiKey` 构造可用的聊天模型实例；让模型按约定格式输出，便于服务端解析与重试。

**建议掌握点**

- **动态构建**：OpenAI 兼容端点下，根据配置对象（如 per-user `ModelProfile`）创建 `OpenAiChatModel` / `StreamingChatModel`，避免写死在 `application.yml` 单一路径。
- **缓存键**：`userId + profileId`（或等价维度），防止多用户配置串台；注意 TTL 与配置变更后的失效。
- **结构化输出**：约定 JSON 形态（或 LangChain4j 提供的 AI Service + bean 映射能力），失败时解析重试一次、记录原始片段便于排障。
- **与现有门面关系**：区分「总控模型」与「子任务 HTTP 模型」两套 profile，限流与 token 统计可按 profile 维度扩展。

**与本项目**：对照 `CLAUDE.md` 中 Ai 层与多模型配置，思考「再增加一层按用户表驱动的工厂」而非复制粘贴多套 Bean。

---

## 2. ProcessBuilder + 超时 + 日志采集

**目标**：安全拉起 Codex CLI / Claude Code（或其它 CLI），在可控时间内结束，并把 stdout/stderr 落盘或入库供总控模型消费。

**建议掌握点**

- **`ProcessBuilder`**：`directory(cwd)`、环境变量 `environment()`（密钥只放 env，不写进可 `ps` 窥见的命令行参数）、`redirectErrorStream(false)` 便于分开采集。
- **超时**：`process.waitFor(timeout, unit)` 或 `CompletableFuture` + `orTimeout`，超时后 `destroyForcibly` 并标记 Step 失败。
- **日志采集**：异步读 `InputStream`/`ErrorStream`，防止缓冲区塞满导致子进程挂死；大输出要截断或落文件再在 DB 存路径。
- **退出码**：`exitValue` 非 0 与 stderr 摘要一并写入事件表，供编排器重试或交给总控决策。

**与本项目**：Runner 适配层独立包或类，单元测试可用 `echo` / 小脚本模拟长输出与错误码，再接真实 CLI。

---

## 3. MySQL 状态机表设计 + 乐观锁 / 版本号防并发乱序

**目标**：`ClusterJob` / `ClusterStep` 在多请求、异步执行器并发更新时，状态迁移可预测、不出现「倒退」或覆盖他人更新。

**建议掌握点**

- **状态机**：明确枚举与允许迁移（如 `RUNNING` → `VERIFYING`，禁止 `PASSED` → `RUNNING`）；在 Service 层集中校验，不要只靠前端。
- **乐观锁**：表增加 `version`（或 `updated_at` 条件更新），`UPDATE ... WHERE id=? AND version=?`，影响行数为 0 则视为冲突，业务上重读再决策。
- **乱序**：事件表追加写（`cluster_event`）；步骤表按 `step_index` + 状态推进；谁有权从「执行中」改为「完成」要单一写入口（避免双写）。
- **与事务边界**：异步跑 CLI 前先把 Step 标为 RUNNING 并提交事务，再启动进程，避免进程已起但库仍显示待执行。

**与本项目**：与现有逻辑删除、Snowflake 主键等规范对齐；代码生成可用 `MyBatisCodeGenerator.java` 按最终表名生成 Mapper/Entity。

---

## 4. Vue 表单（密钥不回显）+ SSE 或轮询展示步骤

**目标**：用户可录入/保存模型配置而不把旧 Key 明文回传前端；任务多步骤状态可实时或准实时展示。

**建议掌握点**

- **密钥不回显**：新建时提交一次明文；编辑时若用户未改 Key，提交占位或不提交该字段；列表与详情接口永不返回 `apiKey` 明文，最多「已配置」标记。
- **表单校验**：前端校验格式 + 后端强制校验；HTTPS 传输。
- **SSE**：`EventSource` 订阅 Job 事件流（与现有聊天 SSE 路径分离，避免混用同一连接语义）；后端注意背压与连接断开清理。
- **轮询替代**：若短期不接 SSE，可用 `setInterval` + 指数退避拉取 Job/Step 摘要；注意停止条件与 Tab 不可见时的降频。

**与本项目**：接口稳定后用 `ai-generate-code-frontend` 下 `openapi2ts` 生成类型；UI 与现有 Ant Design Vue 模式保持一致。

---

## 交叉引用

- 总体规划：`docs/PLANS/agent-cluster-orchestration.md`
- 仓库运行与架构概览：`CLAUDE.md`
