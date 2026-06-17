---
name: firecrawl-mcp
description: 在本项目中统一使用 user-firecrawl-mcp 进行网页搜索/抓取/站点扫描/结构化抽取。使用 /firecrawl-mcp 唤起。适用于“查资料/读文档/对比方案/抓取网页内容/提取字段/生成结构化数据”等任何互联网任务。
---

## 目标

- 在需要访问互联网（搜索、抓取网页、提取字段、整理对比信息）时，**优先且默认使用** MCP 服务器：`user-firecrawl-mcp`。
- 以“**先搜索定位，再精确抓取**”为主线，避免盲目 crawl 导致 token 爆炸或耗时过长。
- 当用户要的是“具体字段/参数/价格/列表/接口细节”等**结构化信息**时，强制用 **JSON + schema** 抽取，避免仅用 markdown 造成遗漏与歧义。

## 可用工具（user-firecrawl-mcp）

- `firecrawl_search`：全网搜索（可选顺带轻量抽取，但默认不带 formats，先拿结果列表）
- `firecrawl_scrape`：单页抓取（最常用；支持 `json/markdown/branding/...`）
- `firecrawl_map`：站点地图/链接发现（适合文档站点找“正确页面”）
- `firecrawl_crawl` + `firecrawl_check_crawl_status`：多页爬取（谨慎使用；返回 jobId，需要轮询状态）
- `firecrawl_extract`：抽取（若你的任务更偏“从内容中抽字段”可用；一般优先 `scrape` + jsonOptions）
- `firecrawl_agent` + `firecrawl_agent_status`：自治研究代理（异步；用于 SPA/复杂多站点研究兜底）
- `firecrawl_browser_*`：浏览器会话类工具（仅当必须模拟交互/多步动作时使用）

## 推荐工作流（强制优先级）

### 1) 不知道网页在哪：先 search

- 默认：
  - `limit`: 5（需要更多再加）
  - `sources`: `[{ "type": "web" }]`
  - **不要**在 `firecrawl_search` 里上来就用 `scrapeOptions.formats`（除非非常必要且 `limit<=5`）

示例（搜索，不带 formats）：

```json
{
  "server": "user-firecrawl-mcp",
  "toolName": "firecrawl_search",
  "arguments": {
    "query": "Spring Boot 3.5.10 Knife4j doc.html 配置",
    "limit": 5,
    "sources": [{ "type": "web" }]
  }
}
```

### 2) 已知具体页面：用 scrape（按需求选格式）

#### 2.1 用户要“具体字段/参数/数字/列表/接口细节” → 必须 JSON + schema

示例（抽取接口参数）：

```json
{
  "server": "user-firecrawl-mcp",
  "toolName": "firecrawl_scrape",
  "arguments": {
    "url": "https://example.com/api-docs",
    "formats": ["json"],
    "jsonOptions": {
      "prompt": "提取鉴权接口的请求头参数与说明",
      "schema": {
        "type": "object",
        "properties": {
          "parameters": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "type": { "type": "string" },
                "required": { "type": "boolean" },
                "description": { "type": "string" }
              }
            }
          }
        }
      }
    }
  }
}
```

#### 2.2 用户要“通读全文/总结文章” → markdown

```json
{
  "server": "user-firecrawl-mcp",
  "toolName": "firecrawl_scrape",
  "arguments": {
    "url": "https://example.com/blog-post",
    "formats": ["markdown"],
    "onlyMainContent": true
  }
}
```

#### 2.3 用户要“品牌/设计风格提取” → branding

```json
{
  "server": "user-firecrawl-mcp",
  "toolName": "firecrawl_scrape",
  "arguments": {
    "url": "https://example.com",
    "formats": ["branding"]
  }
}
```

### 3) 文档站/SPA 抓不到内容：按顺序处理

当 `scrape(json)` 结果空/只剩导航/内容明显缺失，按顺序尝试：

- 先加 `waitFor`: 5000~10000（让 JS 渲染）
- 若 URL 含 `#fragment`：换成不带 `#` 的页面，或找更具体的直达 URL
- 用 `firecrawl_map` + `search` 找“真正承载内容”的子页面，然后再 `scrape`
- 仍失败：用 `firecrawl_agent` 作为兜底（异步研究）

### 4) 需要覆盖多页：优先 map，再小批 scrape；crawl 只在必要时用

- **优先**：`map` 找到一组候选 URL → 选 3~10 个关键页逐个 `scrape`
- **谨慎**：`crawl` 可能很大，容易超 token；必须控制：
  - `limit`（比如 20）
  - `maxDiscoveryDepth`
  - `allowExternalLinks=false`
  - 需要 jobId 后用 `firecrawl_check_crawl_status` 轮询

### 5) agent（异步）使用规范

- `firecrawl_agent` 会立刻返回 jobId
- 之后用 `firecrawl_agent_status` **耐心轮询 2~3 分钟以上**（每 15~30 秒一次）
- 适用：复杂研究、多站点信息汇总、强动态页面、map+scrape 仍拿不到内容

## 输出规范（写给“后续执行该 Skill 的你”）

- 对用户最终输出时：
  - **优先给结论与来源**（来源用页面标题 + 域名即可，不必粘贴长链接；必要时用反引号包 URL）
  - 结构化结果按用户语义组织（字段、参数、对比点、清单）
  - 避免把整页 markdown 原样倾倒给用户（除非用户明确要全文）

