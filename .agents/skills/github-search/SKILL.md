---
name: github-search
description: Search GitHub repositories, code, and examples, then return the most relevant results with rationale.
metadata:
  short-description: Search GitHub repositories and code
---

# github-search

用法：当用户说“搜 github 仓库/找代码/找示例”时使用。

工作流：
1) 优先用 MCP `github` 做检索（更结构化）。
2) 需要网页搜索时再用 `search-scrape` / `web.run`。
3) 返回：3-7 个高相关结果 + 你为什么推荐。