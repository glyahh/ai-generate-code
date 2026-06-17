---
name: search
description: Search for external information, competitors, or references and return sourced conclusions with links.
metadata:
  short-description: Search the web with sourced results
---

# search

用法：当用户说“搜索/帮我查/找资料/找竞品”时使用。

工作流：
1) 先问清楚：搜索目标、语言、时间范围、是否要中文结果。
2) 优先用 MCP `search-scrape`（如果配置了 SERPER_API_KEY）或 `firecrawl-mcp`（抓取正文）。
总之必须用一个 MCP,firecrawl-mcp登录失效或者没额度了提醒我
3) 输出：结论先行 + 引用来源（链接/标题/要点）。

注意：若用户未配置 `SERPER_API_KEY`，改用 `web.run` 进行搜索。