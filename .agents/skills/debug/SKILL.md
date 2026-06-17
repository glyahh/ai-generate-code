---
name: debug
description: Reproduce, isolate, and fix bugs with minimal changes, then verify the result with focused commands or tests.
metadata:
  short-description: Reproduce and fix bugs
---

# debug

用法：当用户说“debug/排查/为什么报错/修 bug”时使用。

目标：最小改动修复根因，并用命令复现/验证。

工作流：
1) 先复现：读报错栈、环境信息、最小复现路径。
2) 再定位：用 `rg` / 读相关文件 / 运行最小测试。
3) 再修复：优先修根因，不做无关重构。
4) 再验证：跑最相关的测试/构建命令；输出复现与验证命令。

输出要求：
- 给出修改的文件路径与关键点。
- 如果需要用户操作（例如提供日志/步骤），只问 1-2 个最关键问题。