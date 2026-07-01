# 潜在 bug 清单

> 2026-06-29 · 两轮扫描 · 未改代码

## 摘要

两轮把 controller、Deploy、rateLimiter、LangGraph4j、Redis TTL、git diff 和新增/删除测试都过了一遍。现在一共 **26** 条有代码依据的问题（BUG-001~026），外加 **10** 条还没跑过 E2E 的 VERIFY。

我觉得最值得先动手的三条：BUG-001 压缩链路回归、BUG-002 压缩后 Caffeine 不同步、BUG-015 部署静态资源路径穿越。其次 BUG-016/BUG-017 的 memory 竞态、BUG-021 workflow Vue 重试清不掉 Redis 里的失败 AI。

---

## 已确认 / 高优先级

### BUG-001: AI 长文 Redis 压缩链路被移除，重建时全量灌入

- **严重程度**：高（已确认回归）
- **位置**：`ChatHistoryAiMemoryRebuildSupport.java` **L112-L124**；`ChatHistorySchemaMigrationSupport.java` **L196-L237**
- **现象**：HTML/MULTI_FILE 多轮后，Redis ChatMemory 里 AI 消息可能是完整代码块，容易 token 爆、截断或费用飙升；和「非最后一轮主 AI 做片段压缩」设计对不上。
- **触发**：HTML/MULTI_FILE 应用，Redis miss 或 `trySummarizeOldestRoundsIfNeeded` 触发 `rebuildAiChatMemoryFromShrink`。
- **证据**：`rebuildAiChatMemoryFromShrink` 对 AI 行只做 `StrUtil.blankToDefault`，没调 `compactAiMessageForMemory`。git diff 里 `ChatHistoryServiceImpl.turnHistoryToMemory`、`compactMemoryMessagesIfNeeded` 和单测 `ChatHistoryServiceImplMemoryCompressionTest` 都删了，`compactAiMessageForMemory` 全仓库无其它调用。

### BUG-002: 会话压缩后 Caffeine 缓存的 ChatMemory 与 Redis 不同步

- **严重程度**：高（已确认）
- **位置**：`ChatHistoryServiceImpl.java` **L882-L897**；`aiCodeGeneratorServiceFactory.java` **L100-L118**
- **现象**：`trySummarizeOldestRoundsIfNeeded` 用临时 `MessageWindowChatMemory` 重建写 Redis，但没 `serviceCache.invalidate`；已缓存的 service 还拿着压缩前的内存，摘要白合并。
- **触发**：同一 appId Caffeine 未过期（10–30 min），compress 触发后立即生成。
- **证据**：压缩分支只本地 `rebuildAiChatMemoryFromShrink`；Factory 仅在 `isRedisMemoryEmpty` 时失效，不会因 shrink 失效。

### BUG-003: modifyFile 同轮去重 Set 为全局单例，并发请求互相清空/污染

- **严重程度**：高（已确认）
- **位置**：`FileModifyTool_Assist.java` **L22-L33**；`AiCodeGeneratorFacade.java` **L420-L425**
- **现象**：多 app 并发生成时，A 轮 `clearRoundDedup()` 会清掉 B 的去重记录，重复写盘或误跳过合法修改。
- **触发**：两个及以上 SSE 生成同时进行。
- **证据**：`roundModifyDedupSet` 是 `@Component` 单例；每轮入口全量 `clear()`，无 appId 隔离。

### BUG-004: modifyFile 使用 `String.replace` 全局替换

- **严重程度**：高（已确认）
- **位置**：`FileModifyTool.java` **L167**
- **现象**：`oldContent` 在文件里出现多次时，一次调用全部替换。
- **触发**：模型给的片段非唯一（重复 CSS 类名、模板片段等）。
- **证据**：`originalContent.replace(matchedOldContent, newContent)` 是 Java 全局 replace。

### BUG-005: onRoundCompleted 无事务，snapshot 与 memory_state 可能不一致

- **严重程度**：高（部分写入待压测确认）
- **位置**：`ConversationMemoryStateServiceImpl.java` **L97-L126**；`ConversationMemoryStateCache.java` **L125-L137**
- **现象**：`insertSnapshotHistory` 成功而 `stateCache.upsert` 或 Redis 回填失败时，DB 有 orphan snapshot，`changedFiles` 没更新。
- **触发**：upsert 抛异常或 Redis 不可用；外层 catch 把 `dbStatus=skip` 但 snapshot 已提交。
- **证据**：`onRoundCompleted` 无 `@Transactional`；步骤 3、4 独立调用，失败只 log.warn。

### BUG-015: DeployResourceController 未做路径规范化，存在目录穿越

- **严重程度**：高（代码路径明确，还没打 PoC）
- **位置**：`DeployResourceController.java` **L33-L50**
- **现象**：请求 `/deploy/{deployKey}/../../...` 时，拼接后的 `File` 未 `normalize`/`toRealPath`，也未校验最终路径是否仍在 `CODE_DEPLOY_ROOT/{deployKey}` 下，可能读到 deploy 目录外的本地文件。
- **触发**：知道或猜到 `deployKey` 的攻击者构造带 `..` 的 URL。
- **证据**：`filePath = CODE_DEPLOY_ROOT + "/" + deployKey + resourcePath` 后直接 `new File(filePath).exists()`；对比 `FileModifyTool.java` **L103-L106** 有 `startsWith(projectRoot)` 校验，Deploy 侧没有。

### BUG-021: workflow Vue 质检重试调用的 Redis 清理对 VUE 类型直接 no-op

- **严重程度**：高（逻辑缺口，和 retry 设计冲突）
- **位置**：`CodeGenWorkflow.java` **L89-L98**；`ChatHistoryServiceImpl.java` **L931-L936**
- **现象**：质检失败走 `retry` 时，`removeLatestFailedAiMessageForRetry` 对 VUE 直接 return false，Redis ChatMemory 里失败轮的 AI/工具消息可能留着，污染下一轮 modify 上下文。
- **触发**：LangGraph workflow + VUE，`code_quality_check` 未通过后重进 `code_generator`。
- **证据**：Workflow 无条件调用 cleanup；实现里 `codeGenTypeEnum != HTML && != MULTI_FILE` 就 skip 并打 info 日志。

---

## 中等风险

### BUG-006: soft/hard 摘要写入 state 但从未注入 ChatMemory

- **严重程度**：中（已确认功能缺口）
- **位置**：`ConversationMemoryStateServiceImpl.java` **L158-L160**；`ConversationMemorySummarySupport.java` **L10-L12**
- **现象**：长会话 DB/Redis 有 soft/hard summary，模型侧收不到。
- **触发**：USER 轮 ≥ 12（soft）或 ≥ 24（hard）后任意生成。
- **证据**：inject 只有 TODO，无 SystemMessage 注入。

### BUG-007: ref 大文件归档被注释，changedFiles 可能无限膨胀

- **严重程度**：中
- **位置**：`ConversationMemoryStateServiceImpl.java` **L113-L114**
- **现象**：大文件 diff 堆在 `changedFilesJson`，注入和 state 持续变大。
- **触发**：多轮 Vue/HTML 编辑产生大文件变更。
- **证据**：`refArchiver.archiveLargeChangedFilesIfNeeded(...)` 整行注释。

### BUG-008: inject 结果 `source` 字段逻辑反置

- **严重程度**：中
- **位置**：`ConversationMemoryStateServiceImpl.java` **L155-L168**
- **现象**：Redis miss、DB 回源且 state 非空时，`source` 仍报 `"redis"`。
- **触发**：`cm:state:{appId}` 过期但 DB 有数据。
- **证据**：`String source = state.isEmpty() ? "db" : "redis"` 未区分实际读路径。

### BUG-009: memory 注入 SystemMessage 追加在窗口尾部，可能挤掉会话风格

- **严重程度**：中
- **位置**：`ConversationMemoryStateInjectSupport.java` **L52-L68**；`ChatHistoryConstant.CHAT_MEMORY_MAX_MESSAGES = 160`
- **现象**：rebuild 头部注入风格后，inject 再追加 1–3 条 SystemMessage；窗口超限从最旧淘汰，风格 SystemMessage 可能先丢。
- **触发**：历史 + 注入块接近 160 条。
- **证据**：inject 用 `chatMemory.add` 顺序追加，无 reserved slot。

### BUG-010: 目录稳定性等待超时后仍构建 manifest

- **严重程度**：中
- **位置**：`ConversationMemoryDirStabilitySupport.java` **L33-L57**
- **现象**：落盘未完成时 diff 可能基于半写文件。
- **触发**：慢磁盘、大 Vue 工程，重试窗口内 metrics 一直变。
- **证据**：循环结束无失败分支，超时仍继续 `buildManifest`。

### BUG-011: AiServiceStreamingResponseHandler 取消后仍转发 complete 工具帧

- **严重程度**：中
- **位置**：`AiServiceStreamingResponseHandler.java` **L127-L133**（对比 **L104-L105**、**L137-L138**）
- **现象**：用户取消 SSE 后，`onCompleteToolExecutionRequest` 仍向 sink 推工具卡片。
- **触发**：disconnect 触发 `tokenStream.cancel()`，上游仍发 complete tool_calls。
- **证据**：partial/complete response 检查了 `cancelledChecker`，complete tool 回调没检查。

### BUG-012: 摘要阈值统计与轮数统计口径不一致

- **严重程度**：中
- **位置**：`ConversationMemorySummarySupport.java` **L39-L43**；`ChatHistoryServiceImpl.java` **L686-L689**
- **现象**：`buildSummaryBundle` 显式 `isDelete=0`，`countUserRoundsInternal` 靠 Flex 逻辑删除默认行为，配置不一致时 soft/hard 级别可能错位。
- **触发**：存在逻辑删除 USER 行或 Flex 配置变更。
- **证据**：前者显式过滤，后者无显式条件。

### BUG-016: applySessionStyle 在流式生成期间 clear 并重写 ChatMemory，存在竞态

- **严重程度**：中（我比较确定，缺并发复现）
- **位置**：`aiCodeGeneratorServiceFactory.java` **L251-L306**；`ChatToGenCodeImpl.java` **L121-L122**
- **现象**：每轮请求在 AI 流开始前调 `injectOrUpdateSessionStyle` → `applySessionStyle`，内部 `memory.clear()` 再逐条 `add`。若上一轮 TokenStream 还在往同一 `appMemoryMap` 实例写消息，可能把进行中的 USER/AI 清掉或乱序。
- **触发**：同一 appId 快速连点、或上一轮生成尚未结束又开新请求（限流 12s  dedup 拦不住不同 message）。
- **证据**：`applySessionStyle` 全量 clear 重建，无 app 级锁；与 Factory 注释「避免内存与 Store 不同步」的意图相冲突。

### BUG-017: appMemoryMap 不随 Caffeine serviceCache 淘汰而清理

- **严重程度**：中
- **位置**：`aiCodeGeneratorServiceFactory.java` **L81-L84**、**L67-L74**、**L231-L233**
- **现象**：`serviceCache` removalListener 只打日志；`appMemoryMap` / `appLastSessionStyle` 永不清。淘汰后 `applySessionStyle` 可能操作已 detached 的 memory，或和新 service 绑定的 Store 状态不一致。
- **触发**：appId 长时间不用触发 Caffeine 过期，再次请求同一 app。
- **证据**：removalListener 未 `appMemoryMap.remove`；`prepareChatMemory` 直接 `put` 覆盖但不 invalidate 旧实例引用。

### BUG-018: legacy `/gen/code` 缺少 InputGuardrailException 回滚，workflow 有

- **严重程度**：中（行为不一致）
- **位置**：`ChatToGenCodeImpl.java` **L137-L155** vs **L207-L211**
- **现象**：Loop 注入后的 enhanced prompt 若被 LangChain4j `PromptSafetyInputGuardrail` 拒掉，legacy 路径 user 消息已入库且不回滚；workflow 路径有 `doOnError(InputGuardrailException)` 调 `removeUserMessageByContent`。
- **触发**：入口审查 ALLOW，但增强后 prompt（含 loop XML）触发 InputGuardrail。
- **证据**：workflow handlerFlux 显式回滚；legacy handlerFlux 无对等逻辑。

### BUG-019: CodeGeneratorNode 在 appId 无效时用时间戳伪造 appId

- **严重程度**：中
- **位置**：`CodeGeneratorNode.java` **L39-L43**
- **现象**：`appId == null/<=0` 时赋 `System.currentTimeMillis()` 并写回 context，后续落盘到 `vue_project_{ts}` / `{type}_{ts}`，和 MySQL app 记录、权限、memory 全脱节。
- **触发**：WorkflowContext 未正确透传 appId（测试/直接调 workflow、状态丢失）。
- **证据**：无校验直接 `setAppId`；正常链路 `ChatToGenCodeImpl` 会先校验 app，但 LangGraph 节点本身不防。

### BUG-022: ProjectBuilderNode 构建失败仍把 buildResultDir 设为源码目录并结束

- **严重程度**：中
- **位置**：`ProjectBuilderNode.java` **L35-L46**
- **现象**：`buildProject` 抛异常或返回 false 时，catch 里 `buildResultDir = generatedCodeDir`（源码树而非 dist），工作流仍走 END；下游/前端可能把未构建目录当预览根。
- **触发**：Vue 工程 npm build 失败、依赖缺失。
- **证据**：失败分支只 log.error，不 set `errorMessage`、不中断图；成功路径才是 `dist`。

### BUG-023: Redisson 限流器 trySetRate 只在首次生效，改注解参数不更新

- **严重程度**：中（运维/配置漂移）
- **位置**：`rateLimiterAspect.java` **L45-L54**
- **现象**：`trySetRate` 首次成功后 Redis 里桶配置固定；后续改 `@RateLimit(rate=…)` 只打 debug「复用旧配置」，实际限流阈值可能和代码不一致。
- **触发**：部署新版本改了 rate/interval，Redis key 未过期（还有 `expire(Duration.ofHours(1))` 续命）。
- **证据**：注释已承认 trySetRate 后续返回 false；无强制 reset 逻辑。

### BUG-026: AI ChatMemory Redis TTL（600s）远短于 echo 缓存（3600s）

- **严重程度**：中
- **位置**：`ConversationMemoryProperties.java` **L78-L83**；`application.yml` **L61-L63**
- **现象**：10 分钟无对话 AI 轨 Redis key 过期，但 echo 全文缓存仍热；Factory 命中 Caffeine 时只 refresh TTL，若 Redis 已空会触发 rebuild（BUG-001 叠加更糟），或短窗口内双轨不一致。
- **触发**：用户 10–60 分钟后再开同一 app 对话。
- **证据**：默认 `chatTtlSeconds=600`，`echoMemoryTtlSeconds=3600`；Factory **L106-L110** 仅在 get service 时检测 Redis 空。

---

## 低优先级

### BUG-013: modifyFile 体积限制文案与阈值不一致

- **严重程度**：低（已确认文案错误）
- **位置**：`FileModifyTool.java` **L120-L128**
- **现象**：提示写「最大 8000」，实际阈值 12000。
- **触发**：片段长度 8001–12000。
- **证据**：条件用 12000，format 写 8000。

### BUG-014: modifyFile 去重 key 使用 hashCode，存在碰撞误跳过

- **严重程度**：低
- **位置**：`FileModifyTool_Assist.java` **L71-L73**
- **现象**：hash 碰撞时合法第二次修改被跳过。
- **触发**：极低概率碰撞 + 同路径同轮。
- **证据**：dedupKey 仅 `oldHash|newHash`。

### BUG-020: FIRST_ROUND_LOCKS 只增不减，长期运行可能堆积

- **严重程度**：低
- **位置**：`ChatToGenCodeImpl.java` **L48-L49**、**L256-L258**
- **现象**：每个 appId `computeIfAbsent` 一个 `ReentrantLock`，无 remove；大量历史 appId 后 map 常驻。
- **触发**：长跑实例、大量 distinct appId（比 Caffeine max 1000 更大）。
- **证据**：静态 `ConcurrentHashMap<Long, ReentrantLock>`，全文件无 `remove`。

### BUG-024: UserPersonalization 用 userId.toString().intern() 当锁，可能占满字符串常量池

- **严重程度**：低（经典 footgun）
- **位置**：`UserPersonalizationServiceImpl.java` **L262**
- **现象**：每个 distinct userId intern 一个永久字符串作锁对象；高用户量下常量池膨胀，且锁粒度不可控。
- **触发**：大量用户首次读个性化配置触发 cache miss 回源。
- **证据**：`synchronized (userId.toString().intern())`；注释还写「JVM 层面锁」。

### BUG-025: JsonMessageStreamHandler 取消回调里 getById 无 null 防护

- **严重程度**：低
- **位置**：`JsonMessageStreamHandler.java` **L103-L110**
- **现象**：`doFinally` 里直接 `appService.getById(appId).getCodeGenType()`，app 被删或 id 异常时 NPE，可能打断 cancel 日志路径（Reactor 里算次要，但不干净）。
- **触发**：取消流时 app 刚好不存在。
- **证据**：无 null check；同文件其它路径多用 service 前校验。

### BUG-027: loadFromDb 吞异常返回空 map，inject 误判「无 state」

- **严重程度**：低
- **位置**：`ConversationMemoryStateCache.java` **L78-L96**；`ConversationMemoryStateServiceImpl.java` **L168**
- **现象**：DB 短暂故障时 `loadFromDb` 返回 `{}`，`source` 被判 `"db"` 且跳过 changedFiles 注入，直到下次成功读库。
- **触发**：MySQL 超时、连接池打满。
- **证据**：catch 块 `return Collections.emptyMap()`，无区分「真无行」和「读失败」。

### BUG-028: 删除压缩单测与实现同步移除，回归无自动化兜底

- **严重程度**：低（过程/测试债）
- **位置**：git diff 删除 `ChatHistoryServiceImplMemoryCompressionTest.java`（原 **L38-L50** 测 `compactMemoryMessagesIfNeeded`）
- **现象**：BUG-001 的核心行为曾经有单测锁死，现在测试删、实现也删，后续很难再自动发现压缩回归。
- **触发**：任何人恢复 rebuild 路径但不恢复 compact。
- **证据**：`git show HEAD:...MemoryCompressionTest.java` 仍可见对 10k 字符 AI 截断的断言；工作区文件已删。

---

## 还没跑过验证

| ID | 标题 | 说明 |
|---|---|---|
| VERIFY-001 | `generateAndSaveCodeStream` 三参重载的 `isFirstRound(appId,false)` | 用户消息入库后用三参重载可能误判非首轮。主链路有锁+显式传参，风险在 `CodeGeneratorNode` 外直接调用。 |
| VERIFY-002 | `applySessionStyle` 与 rebuild 双路径风格注入 | compress 前 inject 时 memory 可能尚未创建；E2E 看有没有重复/过期 `<inject_prompt>`。 |
| VERIFY-003 | `pendingByApp` fileNote 在 `onRoundCompleted` 失败时丢失 | 内存队列登记后 persist 失败，fileNote 可能拖到下次改盘才 flush。 |
| VERIFY-004 | `WorkflowCodeGeneratorFacade` 无界 `CompletableFuture.runAsync` | 高并发 workflow 占满 ForkJoinPool；与 `CodeGeneratorNode.blockLast(10min)` 叠加需压测。 |
| VERIFY-005 | Vue 流 `handleComplete` 无条件 npm build | 写盘/工具失败仍 build，预览可能空或旧产物。 |
| VERIFY-006 | SSE cancel 与 LangChain4j `addToMemory` 时序 | `removeUserMessageByContent` 会 `deleteMessages`，但若 cancel 后仍收到 `onCompleteResponse`，Redis 可能又写入半轮 AI；需抓包看 race。 |
| VERIFY-007 | Loop 注入后 prompt 超 InputGuardrail 长度 | 入口 `PromptSafetyAuditEvaluator` 只审原始 message；Loop/XML 追加后 InputGuardrail 才看到全文，legacy 还不回滚（见 BUG-018）。 |
| VERIFY-008 | `ConversationMemoryStateServiceImplInjectTest` 降级为纯文本格式化测试 | git diff 删了原 inject 集成断言，只剩 `ConversationMemoryInjectTexts` 格式测；`source` 反置（BUG-008）无测试覆盖。 |
| VERIFY-009 | DeployResourceController `@RequiredArgsConstructor` 但 `AppService` 未 final | **L28** 字段未注入也未使用，死代码；若后续加权限校验容易误以为已接线。 |
| VERIFY-010 | workflow SSE `sink.error` 经 Controller `onErrorResume` 仍 concat `done` | 用户看到失败文案 + done，是否符合前端「失败不应 done」的产品预期。 |

---

## 第二轮新增（2026-06-29）

本轮新编号 **BUG-015 ~ BUG-028**（跳过 014 已有），**VERIFY-006 ~ VERIFY-010**。按主题：

| 编号 | 一句话 |
|---|---|
| BUG-015 | Deploy 静态资源路径穿越 |
| BUG-016 | applySessionStyle 与进行中的流竞态 |
| BUG-017 | appMemoryMap 与 Caffeine 生命周期脱节 |
| BUG-018 | legacy 路径 InputGuardrail 不回滚 DB |
| BUG-019 | workflow 节点伪造 appId |
| BUG-020 | FIRST_ROUND_LOCKS 无清理 |
| BUG-021 | Vue workflow retry 清不掉 Redis AI |
| BUG-022 | ProjectBuilder 失败仍返回源码目录 |
| BUG-023 | 限流注解改了 Redis 桶不更新 |
| BUG-024 | intern 锁 userId |
| BUG-025 | JsonMessageStreamHandler cancel NPE |
| BUG-026 | chat TTL 600s vs echo 3600s |
| BUG-027 | loadFromDb 失败伪装空 state |
| BUG-028 | 压缩单测删除 = 回归无人盯 |

---

## 建议优先修（Top 3）

1. **BUG-001** — rebuild 恢复 `compactAiMessageForMemory` 或等价在线压缩；否则 HTML/MULTI 长对话 token 风险一直在。
2. **BUG-002** — 压缩成功后 invalidate Caffeine / 同步 `appMemoryMap`，或统一从 Redis hydrate。
3. **BUG-015 + BUG-003 + BUG-004** — Deploy 加 canonical 校验；modifyFile 去重按 appId 隔离；replace 改单次替换。
