---
name: deploy
description: Prepare deployment steps, build validation, and rollback guidance for supported hosting or server targets.
metadata:
  short-description: Plan and validate deployment
---

# deploy

用法：当用户说“部署/发布”时使用。

工作流：
1) 确认目标平台（EdgeOne Pages/自建服务器/Cloudflare 等）。
2) 先跑构建与产物检查。
3) 若使用 EdgeOne Pages：优先通过 MCP `edgeone-pages-mcp-server` 进行部署/配置。

输出：
- 部署步骤 + 回滚策略（如果可行）。