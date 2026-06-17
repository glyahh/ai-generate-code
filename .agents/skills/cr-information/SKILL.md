---
name: cr-information
description: Generate structured project materials such as overviews, interface docs, deployment notes, or status summaries from a repository.
metadata:
  short-description: Generate project documentation
---

# cr-information

用法：当用户说“cr-information”或需要“整理项目信息/产出说明”时使用。

说明：这个命令在 Cursor 里常见但含义可能因你的自定义而不同。

默认行为：
1) 我先问一句：你想要“项目概览/接口说明/需求文档/部署说明/日报周报”里的哪一种？
2) 读取仓库结构（`ls`/`rg`/`README`），生成对应文档。