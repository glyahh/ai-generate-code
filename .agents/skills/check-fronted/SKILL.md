---
name: check-fronted
description: Audit frontend projects for build, lint, UX, accessibility, and maintainability issues, then produce prioritized fixes.
metadata:
  short-description: Frontend audit and issue triage
---

# check-fronted

用法：当用户说“检查前端/体检/有哪些问题/性能可访问性”时使用。

工作流（按项目现状选择）：
1) 检查：依赖、构建、lint、类型检查。
2) 体验：首屏、路由、错误态、表单校验。
3) 质量：可访问性（a11y）、一致性、可维护性。
4) 产出：问题清单（P0/P1/P2）+ 具体修复 PR 级别改动。

默认命令建议：
- `npm run lint` / `pnpm lint`
- `npm run build` / `pnpm build`