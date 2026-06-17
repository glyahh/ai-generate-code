# Agent 集群编排：实现思路与分期计划

面向读者：本仓库后端/平台负责人。语气：内部技术方案，不写营销话。

---

## 1. 目标与边界

**目标**

- 用户可配置多条「执行体」：模型名、Base URL、API Key、（可选）温度与上下文长度等。
- 平台能**派发任务**到子 Agent（初期以 **Codex CLI / Claude Code 等宿主侧命令行** 为执行载体；不在本仓库内重写 IDE）。
- 存在**总控模型**（可配置为另一条模型）：读子任务输出、做合并与**达标校验**（Rubric），必要时触发重试或改派。

**明确不做的（第一期）**

- 不承诺在服务端内嵌完整「另一个 Cursor」；执行仍依赖**已安装 CLI、合法登录态、用户机器或专用 Runner 机器**上的环境。
- 不把用户 Key 明文进日志；不在未加密字段落库。

**验收（第一期可测）**

- 用户 CRUD 自己的模型配置；Key 以服务端可解密或 KMS 思路存储（具体选型见 §6）。
- 能创建一次「集群任务」：总控生成子任务列表 → 至少一种 Runner 执行 → 总控收到汇总材料并给出通过/不通过与理由。
- 超时、失败、部分成功状态可查；敏感字段不回显给前端。

---

## 2. 与 Kimi / 豆包 / 智谱 / 通义等「多 Agent」产品的对照（抽象层）

公开产品里常见的共性（不绑定某家内部架构）：

| 模式 | 含义 | 在本方案中的落点 |
|------|------|------------------|
| 总控 + 执行分身 | 一个对话或一次任务里，由「调度面」拆分子问题 | 总控模型 + 任务 DAG / 队列 |
| 规划—执行—检查 | 先出计划，再跑工具或子调用，最后自检 | Planner 节点 → Worker 执行 → Verifier 节点（可同一模型多轮） |
| 长上下文汇总 | 子 Agent 输出碎片，由上层压缩成结论 | 总控的「合并 Prompt」+ 截断策略 + 结构化中间件（JSON） |
| 路由 | 不同子任务走不同模型或不同 Runner | 配置表 `model_profile` + `runner_type` |
| 人在回路 | 关键步骤需用户确认 | 可选：关键 Rubric 未通过时挂起，推前端待办 |

这些厂商的具体编排实现未公开到可照抄的粒度；本方案只借**产品形态与交互习惯**，工程上采用你可控的 **Spring 编排 + 外部 CLI/HTTP**。

---

## 3. 总体架构（逻辑分层）

```
                    ┌─────────────────────────────┐
                    │  API（Spring）               │
                    │  集群任务 CRUD / 触发 / 查询  │
                    └──────────────┬──────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                          ▼
 ┌──────────────┐          ┌──────────────┐            ┌──────────────┐
 │ 配置与凭证    │          │ 编排状态机   │            │ Runner 适配   │
 │ ModelProfile │          │ Job / Step   │            │ Codex / Claude │
 │ (用户级加密)  │          │ 事件日志     │            │ （进程/队列）  │
 └──────────────┘          └──────────────┘            └──────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    ▼                             ▼
            ┌──────────────┐              ┌──────────────┐
            │ 总控 LLM 调用 │              │ 子任务 LLM   │
            │ LangChain4j  │              │ （可选：与   │
            │ 独立 ChatModel │              │  总控同池）  │
            └──────────────┘              └──────────────┘
```

**要点**

- **编排**与**推理**拆开：编排是确定性的状态机（谁跑、跑没跑完、重试几次）；推理是带 Prompt 的 HTTP 调用。
- **子 Agent** 在本期语义里 =「被派发的一段可执行单元」：可能是一次 CLI 会话，也可能是对某 OpenAI 兼容端点的单次 chat（若你后续加「纯 HTTP 子 Worker」）。

---

## 4. 核心对象模型（建议）

**ModelProfile（用户级）**

- `id`, `userId`, `displayName`, `providerKind`（openai-compatible / anthropic / …）
- `baseUrl`, `modelName`, `defaultParams`（JSON）
- `secretRef` 或加密后的 `apiKeyCipher`、`keyVersion`
- `allowedRoles`：`ORCHESTRATOR` | `WORKER_HTTP` | `UNUSED`（便于总控与子任务强制隔离）

**ClusterJob**

- `id`, `userId`, `title`, `objective`（用户目标描述）
- `orchestratorModelProfileId`
- `status`：`DRAFT` | `PLANNING` | `RUNNING` | `VERIFYING` | `PASSED` | `FAILED` | `NEEDS_HUMAN`
- `rubricJson`（校验标准，可由总控生成并经用户确认）

**ClusterStep**

- `jobId`, `stepIndex`, `assignee`：`RUNNER_CODEX` | `RUNNER_CLAUDE` | `HTTP_MODEL`
- `payloadJson`（子任务说明、工作目录、允许改动的路径白名单）
- `runnerModelProfileId`（若 HTTP 子调用）
- `artifactUri`（日志、diff、产出物路径）
- `status`, `attempt`, `lastError`

**ClusterEvent（追加日志）**

- 便于 SSE 推送与审计：`STEP_STARTED`, `STEP_STDOUT`, `ORCHESTRATOR_DECISION`, `RUBRIC_RESULT`, …

表名与代码生成：若落库，可后续用 `MyBatisCodeGenerator.java` 按实际表名生成；本期文档只定义概念字段。

---

## 5. 总控与子 Agent 的协作流程（一轮标准流水线）

1. **建单**：用户写目标 +（可选）Rubric；选总控模型配置。
2. **规划**：总控模型输出结构化计划：`steps[]`，每步含 `description`、`acceptance`、建议 `runner`。
3. **确认（可选）**：前端展示计划；用户确认后入队。
4. **执行**：编排器按序或有限并行（注意 Runner 所在宿主并发上限）启动 Runner。
5. **收集**：每步结束写入 `artifactUri`（stdout、退出码、目录 diff 摘要路径）。
6. **校验**：总控读取「目标 + Rubric + 各步摘要」，输出 `passed`、`gaps[]`、`next_actions`（无则结束）。
7. **闭环**：若不通过且在 `maxRetries` 内，生成**修正子任务**回到步骤 4；否则 `NEEDS_HUMAN` 或 `FAILED`。

总控与子任务**可以**共用同一 OpenAI 兼容端点，但**建议**拆两条配置：避免单 Key 限流把调度也打死；Rubric 校验可用更小更快模型降低成本。

---

## 6. Runner 层：Codex / Claude Code（实现要点）

**共性**

- 以**子进程**方式启动（`ProcessBuilder`），工作目录指向用户项目副本或沙箱目录（强烈建议**不要**直接对生产仓库无备份写入）。
- 环境变量注入：`OPENAI_API_KEY` / 各家兼容变量按 CLI 文档映射；**禁止**把 Key 拼进命令行参数（`ps` 可见）。
- 超时、最大输出长度、落盘日志轮转。
- 退出码非 0 → Step `FAILED`，把 stderr 摘要给总控决定是否重试。

**差异**

- Codex CLI 与 Claude Code 的参数、登录方式不同；各做 `RunnerAdapter` 接口：`start(StepContext) -> RunningHandle`，`awaitResult()`。
- 若 CLI 需要交互式 TTY，要么用 `script`/`pty`（复杂），要么限定为**非交互批注模式**（优先走各工具 documented 的 headless 模式）。

**与「用户自配模型」的关系**

- Runner 若走「自带云模型」，可能**忽略**用户配的 `modelName`；需在 UI 标明「此 Runner 使用 CLI 绑定账号」。
- 若子任务是 **HTTP 直连**用户配置的兼容端点，则完全由 `ModelProfile` 驱动。

---

## 7. 安全与合规（必须写进第一期）

- Key：**加密存储** + 传输 HTTPS；列表接口永不返回明文 Key。
- **路径白名单**：子进程 `cwd`、可写目录、禁止 `..` 逃逸。
- **速率与配额**：每用户并发 Runner 数、每日调用上限（对齐现有 `@RateLimit` 思路）。
- **审计**：谁、何时、用哪条 ModelProfile 触发了哪条 Job（不落 Prompt 里的密码类内容）。

---

## 8. 与现有 glyahh 代码库的衔接建议

- **鉴权**：沿用会话 + `@MyRole`，Job 归属 `userId`。
- **LLM 调用**：总控与子 HTTP Worker 可走现有 LangChain4j 工厂模式，但需支持**多组** dynamic `ChatModel`（按 `ModelProfile` 构建），与当前「单应用多模型缓存」并存时注意缓存键：`userId + profileId`。
- **实时反馈**：新建 SSE 或使用现有 Reactor 模式，将 `ClusterEvent` 推前端（与 `/chat/gen/code` 分流，避免互相背压）。
- **前端**：新页面 + `openapi2ts` 生成类型（你方既定流程）。

---

## 9. 分期计划（Plan）

### 阶段 A — MVP（约 1～2 周体量，视 Runner 调研而定）

- 表：`model_profile`、`cluster_job`、`cluster_step`、`cluster_event`（或合并 event 为 JSON 列，后期再拆）。
- API：配置的增删改查；创建 Job；触发「仅 HTTP 子任务」模拟 Runner（不接 Codex，也能跑通总控闭环）。
- 总控：固定 Prompt 模板 + Rubric JSON 输出解析（失败重试一次）。
- 前端：配置页 + 任务详情时间线。

**阶段 A 验收**：不用真实 Codex，也能演示「多步子任务 → 总控校验 → 通过/失败」。

### 阶段 B — Runner 真接入（约 2～3 周）

- 实现 `CodexRunnerAdapter` 或 `ClaudeRunnerAdapter` 之一（先选一个主路径）。
- 主机要求写清文档：依赖安装、登录、网络。
- 沙箱目录与 git 工作副本策略（浅克隆 / 用户上传 zip）。

**阶段 B 验收**：一条 Job 内至少一步由真实 CLI 执行并产生可核验 artifact。

### 阶段 C — 产品化

- 并行度、队列（Redis 或 DB 乐观锁）、死信队列。
- Rubric 模板库、任务模板（代码审查 / 生成文档 / 跑测试）。
- 成本统计：每步 token 粗估（若端点返回 usage）。

---

## 10. 风险与未决问题（开工前建议拍板）

- **Runner 所在位置**：服务端若无法装 CLI，则需要 **Remote Runner**（单独 worker 服务注册心跳），第一期是否接受「仅内网 Runner」？
- **总控幻觉**：校验环节必须**结构化输出**（JSON schema）+ 服务端校验字段存在，避免模型口头说「已通过」。
- **版权与责任**：用户 Key 发起的生成内容责任归属与日志保留周期。

---

## 11. 预估改动面（数量级）

| 区域 | 文件类型 | 量级（行数级，粗估） |
|------|-----------|---------------------|
| 新建 Controller / Service / Mapper | Java | +800～1500 |
| 多 Profile 动态 ChatModel | Java 改造 | +200～400 |
| Runner 适配与进程管理 | Java | +300～800 |
| 前端配置与任务页 | Vue/TS | +600～1200 |
| 文档与运维说明 | Markdown | +100～200 |

实际随「是否上 Remote Runner」「表设计粒度」波动较大。

---

## 12. 文档维护

- 本文件路径：`docs/PLANS/agent-cluster-orchestration.md`
- 最小必学清单（四条技术栈学习要点）：`learn/agent-cluster-最小必学清单.md`
- 若架构决策变更（例如改为纯消息队列 + Python worker），在本文件顶部追加「修订记录」日期与摘要即可。
