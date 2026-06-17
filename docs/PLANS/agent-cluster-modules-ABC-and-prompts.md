# Agent 集群编排：模块 A / B / C 与阶段 A 实现提示词

本文档与总体规划对照阅读：`docs/PLANS/agent-cluster-orchestration.md`。

---

## 总览：三个大类

| 模块 | 对应总体规划 | 一句话 |
|------|----------------|--------|
| **模块 A** | 阶段 A（MVP） | 无真实 Codex/CLI 也能跑通「配置 → Job → 假 Runner（HTTP）→ 总控结构化校验」闭环；表、API、前端配置页与时间线、安全基线。 |
| **模块 B** | 阶段 B | 接入真实 Runner（`ProcessBuilder` + `RunnerAdapter`）；沙箱目录与运维文档；至少一步由 CLI 产出可核验 artifact。 |
| **模块 C** | 阶段 C | 队列与并行度、Rubric 模板、成本统计、产品化观测与配额等。 |

**当前优先级：** 先完成 **模块 A**，不跳阶段；B/C 仅作边界预留，实现见主规划文档分期与验收。

---

## 模块 A：完成要求（阶段 A MVP）

目标：先做「没有真 Codex 也能跑通闭环」，降低认知负担。顺序建议与主规划一致。

1. **定表**  
   `model_profile`、`cluster_job`、`cluster_step`、`cluster_event`（或先将 event 合并为 JSON 列，后期再拆表）。

2. **代码生成**  
   使用 `src/main/java/com/dbts/glyahhaigeneratecode/MybatisFlexGenerateSource/MyBatisCodeGenerator.java`，将 **`TABLE_NAMES`** 改为上述最终表名后生成 entity / mapper / service 骨架；生成物迁入正式包后，**手写**业务状态迁移与编排逻辑（勿把状态机全交给生成器）。

3. **API**  
   - 模型配置 **CRUD**（归属当前登录用户，与现有会话鉴权一致）。  
   - **创建 Job**、查询 Job/Step/Event。  
   - **假 Runner**：例如固定走项目内已有 **OpenAI 兼容 HTTP**（LangChain4j），将每步输出写入与 artifact 相关的字段（路径或摘要，按表设计落地）。

4. **总控**  
   - 固定 **Prompt 模板**，要求模型输出 **结构化 JSON**（规划步骤、Rubric 校验结果等）。  
   - **服务端校验 JSON 字段存在且类型合理**后再采纳，禁止仅信任自然语言「已通过」。

5. **前端**  
   - **模型配置页** + **集群任务时间线**（步骤状态、事件列表或等价展示）。  
   - 接口稳定后执行：`cd ai-generate-code-frontend && npm run openapi2ts`（需后端 `http://localhost:8124/api/v3/api-docs` 可访问）。  
   - UI 栈与信息架构对齐 `ai-generate-code-frontend/frontend-design.md`（Vue 3、Ant Design Vue 4、Pinia、`@/request`）。

6. **安全基线**  
   - API Key **加密存储**；列表与详情 **永不回显明文 Key**（新建一次提交、编辑可用占位或不改不传）。  
   - 传输 **HTTPS**（生产）；开发环境仍按团队惯例。

**模块 A 验收（与主规划一致）：** 不接真实 Codex，也能演示「多步子任务 → 总控按 Rubric 校验 → 通过/失败及理由」；超时、失败、部分成功状态可查；敏感字段不回显前端。

**后端惯例（实现时必读）：** 根目录 `CLAUDE.md` — 端口 **8124**、context path **`/api`**、**`@MyRole` + 会话**、Snowflake、逻辑删除 **`isDelete`**、OpenAPI **`/api/v3/api-docs`**、现有 LangChain4j 与限流模式等。

---

## 模块 B / C（简述，详细见主规划）

- **模块 B：** `RunnerAdapter`（Codex 或 Claude Code 等其一优先）、环境变量注入密钥、超时与日志采集、沙箱/工作副本策略；验收：真实 CLI 一步可核验。  
- **模块 C：** 并行与队列、死信、模板库、token/成本粗估等。

---

## 模块 A — 给 Codex（Cloud / CLI）用的提示词

将下面整段作为 **单条任务说明** 发给 Codex；若 Cloud 有「仓库上下文」，确保已绑定本仓库。

```text
你在仓库 glyahh-ai-generate-code 中实现「Agent 集群编排」的模块 A（阶段 A MVP），严格对照：
- docs/PLANS/agent-cluster-orchestration.md（§4 对象模型、§5 流程、§8 与现有库衔接）
- docs/PLANS/agent-cluster-modules-ABC-and-prompts.md（模块 A 完成要求）

必读项目约定：根目录 CLAUDE.md（Spring Boot 3.5 + Java 21、MyBatis-Flex、端口 8124、context-path /api、@MyRole 与会话、Snowflake、isDelete、LangChain4j、OpenAPI /api/v3/api-docs）。

实现要点（不要跳步）：
1) MySQL：建表 model_profile、cluster_job、cluster_step、cluster_event（或 event 暂存 JSON，文档允许）。字段对齐规划文档 §4；含 userId 归属、Job/Step 状态枚举、重试/错误摘要、artifact 存放处等。
2) 用 MybatisFlexGenerateSource/MyBatisCodeGenerator.java 的 TABLE_NAMES 填入最终表名，生成骨架后把实体/mapper/service 合入正式包 com.dbts.glyahhaigeneratecode 下现有分层习惯；手写状态机迁移（禁止 PASSED 回退 RUNNING 等）。
3) REST：模型配置 CRUD + 创建/查询集群任务 + 触发执行；所有接口走现有鉴权（@MyRole、userService.getUserInSession），仅操作当前用户数据。响应体永不返回明文 apiKey。
4) 假 Runner：用 LangChain4j 调 OpenAI 兼容 HTTP（可复用或并行于现有 ChatModel 工厂），按 Step 写入输出/摘要到 artifact 相关字段；总控使用固定 Prompt，要求输出严格 JSON（规划 steps、Rubric 结果）；Java 侧用 Jackson 等校验必填字段，非法则标记失败或可重试，不采信纯文本「通过」。
5) Key：加密存储（项目内若有统一方案则沿用；否则最小可用 AES + 配置密钥版本号，避免日志打印明文）。
6) 前端 ai-generate-code-frontend：新增「模型配置」页 + 「集群任务」详情时间线；Ant Design Vue 4、Pinia、@/request；路由与导航可挂在 GlobalHeader 或用户菜单（与 frontend-design.md 一致）。接口稳定后只通过 npm run openapi2ts 更新 src/api，不手改生成文件业务逻辑。
7) 完成后 ./mvnw test 或通过相关模块测试；删除一次性调试日志与临时代码。

约束：最小改动原则；不实现真实 ProcessBuilder CLI Runner（留到模块 B）；SSE 可选，模块 A 可用轮询；新 SSE 若做则与 ChatToGenCodeController 流式路径分离避免背压混用。
```

---

## 模块 A — 给 Cursor Agent 用的提示词

在 Cursor 中新建 Agent 任务时，可将下方作为 **User instructions / 初始消息**（可附带 @ 引用文件）。

```text
@CLAUDE.md @docs/PLANS/agent-cluster-orchestration.md @docs/PLANS/agent-cluster-modules-ABC-and-prompts.md @ai-generate-code-frontend/frontend-design.md

请在本仓库实现「模块 A」：Agent 集群编排的阶段 A MVP（无真实 Codex/CLI）。

范围（必须做）：
- 数据库表：model_profile、cluster_job、cluster_step、cluster_event（或 event 先 JSON）。
- 用 src/main/java/com/dbts/glyahhaigeneratecode/MybatisFlexGenerateSource/MyBatisCodeGenerator.java 配置 TABLE_NAMES 生成 MyBatis-Flex 骨架，再迁入正式代码结构；状态迁移手写。
- 后端 API：ModelProfile CRUD；ClusterJob 创建与查询；执行链路使用「假 Runner」——通过 LangChain4j 调用 OpenAI 兼容端点，把每步结果写入 artifact 相关字段。
- 总控 Prompt：固定模板 + 强制结构化 JSON（步骤规划、Rubric 结果）；服务端解析并校验字段，失败则不标记为通过。
- 安全：API Key 加密存储；任何 API 不返回明文 Key。
- 前端：配置页 + 任务时间线；遵循 frontend-design.md（Vue3、Ant Design Vue 4、openapi2ts、勿手改生成 API 逻辑）。

范围（不要做）：真实 Codex/Claude CLI 子进程（模块 B）、大规模重构无关模块。

验收：本地可演示完整闭环；openapi2ts 可生成新接口类型；敏感字段不回显。

实现过程中优先对齐现有 Controller/Service/Mapper 风格与 @MyRole 鉴权；OpenAPI 暴露后我再运行 npm run openapi2ts。
```

---

## 文档维护

- 总体规划：`docs/PLANS/agent-cluster-orchestration.md`  
- 本文档：`docs/PLANS/agent-cluster-modules-ABC-and-prompts.md`  
- 学习清单：`learn/agent-cluster-最小必学清单.md`

修订模块 B/C 的提示词时，在本文件对应章节追加 Codex/Cursor 块即可。
