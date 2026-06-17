---
name: History_memory_redis优化计划
status: draft
owner: glyahh
created: 2026-06-02
scope:
  - echo 回显缓存（chat_history ↔ Redis）
  - 并发重建保护（singleflight/互斥锁）
  - invalidate 抖动与击穿问题描述（暂不落方案）
---

## 背景与目标

当前回显链路采用 `chat:echo_memory:{appId}` 存储**整段历史数组 JSON（String）**：

- **问题 1：全量缓存是“重锤”**：一次 miss 即 `chat_history` 全表扫描 + 全量序列化 JSON + Redis SET；对话变大后网络与 GC/CPU 峰值明显。
- **问题 2：更新粒度粗**：新增消息本质是追加一行，但缓存策略是 `invalidate → 下次全量重建`，没有利用“追加”性质。
- **问题 3：Redis 内存占用不可控**：单 key 越来越大，TTL 续期下可能长期占用。
- **问题 4：并发一致性无防护**：并发 miss 会并发重建，产生 DB 放大与重复写 Redis。
- **问题 5：invalidate 抖动/击穿倾向**：活跃会话中频繁 invalidate 导致反复全量重建；热门 appId 在 miss 时可能出现击穿。

本计划聚焦于：

1. 用 **ZSet + Hash** 替换“大 JSON String”，让回显缓存支持**增量追加**与**稳定分页**。
2. 增加 **singleflight/互斥锁**，避免并发 miss 下的 DB 放大与重复构建。
3. 对 invalidate 抖动/击穿先做 **5W 问题描述**（本期不输出具体落地方案大纲）。

---

## 1）回显缓存升级：ZSet + Hash（结构/逻辑图/分页语义）

### 1.1 Redis 结构定义（推荐）

#### 1）ZSet：排序索引（用于倒序分页/游标）

- **key**：`chat:echo_zset:{appId}`
- **member**：`chatHistoryId`（建议用 `chat_history.id`）
- **score**：`createTime` 的毫秒时间戳（Long）
  - 为保证严格单调（同毫秒多条）推荐复合 score：
    - \(score = tsMs * 1_000_000 + (id \% 1_000_000)\)
    - 约束：`id % 1_000_000` 不需要全局唯一，只需在同一毫秒内“足够打散”，并确保 score 不冲突概率极低（或直接用更大基数）。

#### 2）Hash：内容存储（用于按 id 批量取回）

- **key**：`chat:echo_hash:{appId}`
- **field**：`chatHistoryId`
- **value**：`ChatHistory` 的 JSON（单条记录）

#### 3）TTL 约束（建议）

- `chat:echo_zset:{appId}` 与 `chat:echo_hash:{appId}` **同 TTL**（沿用 `echo-memory-ttl-seconds`），并在命中/写入时同步续期，避免索引与内容不一致。

---

### 1.2 逻辑结构图（直观表示）

```mermaid
flowchart LR
  subgraph MySQL
    CH[(chat_history)]
  end

  subgraph Redis Echo Cache
    ZS[ZSET: chat:echo_zset:{appId}\nmember=id\nscore=tsMs*1e6+(id%1e6)]
    HS[HASH: chat:echo_hash:{appId}\nfield=id\nvalue=ChatHistory JSON]
  end

  CH -->|批量回填/增量写入| ZS
  CH -->|批量回填/增量写入| HS

  ZS -->|分页取 id 列表| API[History API]
  HS -->|按 id 批量取内容| API
```

---

### 1.3 读路径（前端分页/倒序/游标）

#### 目标
保持现有分页语义（基于 `lastCreateTime`），将“从 Redis 拿全量 JSON 再内存切片”升级为“从 Redis 直接分页取回”。

#### 建议游标（兼容旧接口）
- **继续沿用**：`lastCreateTime`（datetime）作为游标输入。
- **推荐增强（可选）**：增加 `lastId`（Long）形成复合游标，彻底避免同一 `createTime` 的重复/跳跃。

#### Redis 查询建议
- 首页（无游标）：按 score 倒序取最近 N 条 id：
  - `ZREVRANGE chat:echo_zset:{appId} 0 (pageSize-1)`
- 有游标（`lastCreateTime`）：按 score 上限取 <= 的最近 N 条：
  - 将 `lastCreateTime` 转 `tsMs`
  - `ZREVRANGEBYSCORE key (max=tsMs*1e6+MAX) (min=-inf) LIMIT 0 pageSize`
- 拿到 id 列表后，批量取单条 JSON：
  - `HMGET chat:echo_hash:{appId} id1 id2 ...`
- 将 JSON 反序列化为 VO 并返回。

---

### 1.4 写路径（新增消息/回滚/删除）

#### 新增消息（增量写缓存）
当写入 `chat_history` 成功（得到 `chatHistoryId` 与 `createTime`）：

- `ZADD chat:echo_zset:{appId} score chatHistoryId`
- `HSET chat:echo_hash:{appId} chatHistoryId ChatHistoryJson`
- 对两个 key 同步 `EXPIRE`（滑动续期）

#### 删除/回滚（增量删缓存）
当业务删除/回滚某条历史（按 id）：

- `ZREM chat:echo_zset:{appId} chatHistoryId`
- `HDEL chat:echo_hash:{appId} chatHistoryId`
- 同步续期（可选）

---

### 1.5 Miss/回源策略（避免“全量重锤”）

当 Redis 不存在 key（或为空）时：

- **回源 MySQL**：只拉取“需要的一页”（最近 pageSize 或最近 K）即可，不再拉全量。
- **回填 Redis**：将回源数据批量写入 ZSet + Hash（Pipeline）。

> 该策略直接消除“一次 miss 全表扫描 + 全量 JSON”与“单 key 巨大”问题，同时把更新粒度从“全量重建”降到“增量追加/删除”。

---

## 2）互斥锁/Singleflight：5W 简介 + 项目落地大纲

### 2.1 互斥锁原理（5W）

- **What（是什么）**：一种并发控制机制，保证同一资源（这里是同一 `appId` 的 echo 缓存重建）在同一时间只有一个执行者，其它请求等待或复用结果。
- **Why（为什么需要）**：避免并发 miss 时出现“多请求同时回源 DB + 重复写 Redis”的放大效应，导致 DB 压力、网络与 CPU 峰值。
- **Who（谁来用）**：历史回显接口的服务端（读 echo 缓存、必要时回源并回填的那段逻辑）。
- **When（何时触发）**：检测到缓存 miss/不完整，需要进行“回源+回填”时。
- **Where（用在哪里）**：以 `appId` 为粒度的重建临界区；锁 key 形如 `lock:chat:echo:{appId}`。

---

### 2.2 运用于本项目的“解决问题大纲”（用于并发 miss 的 DB 放大）

#### 目标问题
并发一致性没有防护：无 singleflight 时，多请求同时 miss 会并发重建，产生 DB 放大与重复写 Redis。

#### 大纲（面向落地）

1. **锁粒度**
   - 以 `appId` 为锁粒度：`lock:chat:echo:{appId}`
2. **加锁时机**
   - 仅在“缓存 miss 且需要回源/回填”时尝试加锁；命中时不加锁。
3. **锁实现选择**
   - 单机部署：JVM 本地锁即可。
   - 多实例部署：Redis 分布式锁（SET NX PX）或 Redisson（若项目已有依赖与规范）。
4. **等待策略**
   - 获取不到锁：短暂 sleep + 重试读取缓存（读到即返回），或直接回源 DB 但不回填（避免重复写）。
5. **失败兜底**
   - 回填失败：不影响本次回源结果返回；记录日志；下次请求再尝试回填。
6. **观测指标**
   - 锁竞争次数、回源 DB 次数、回填成功率、历史接口 P95/P99、Redis QPS、DB QPS。

---

## 3）invalidate 抖动/击穿：问题 5W（暂不写解决大纲）

### 5W 描述

- **What（是什么问题）**：每新增一条消息就 `invalidate` 删除整份 echo 缓存，导致下一次请求必然 miss 并触发回源重建；在活跃会话下形成“删缓存→重建→再删缓存”的抖动循环。
- **Why（为什么会发生）**：缓存存的是“全量 JSON String”，无法增量更新；为了正确性只能粗粒度删除；并发场景下 miss 重建会相互放大。
- **Who（谁受影响）**：热门 `appId` 的用户（高频刷新历史/导出/翻页），以及服务端 DB/Redis（承担更高峰值压力）。
- **When（何时更明显）**：生成中/多人同时查看同一会话、前端高频轮询历史、以及缓存 TTL 恰好过期或被频繁 invalidate 时。
- **Where（出现在哪）**：回显缓存 key（`chat:echo_memory:{appId}` 或未来的 echo 缓存结构）与历史回显接口的读写路径上，表现为并发 miss → 同时打 DB（击穿倾向）。

---

## 附：与 AI ChatMemory / changedFilesJson 的边界（声明）

本计划只优化“回显 echo 缓存”（`chat_history` ↔ Redis），不改变：

- AI ChatMemory Redis（LangChain4j memoryId=appId）的压缩/续期逻辑；
- Conversation Memory V4 的 `changedFilesJson` / `fileNotesJson` 等文件注入机制。

