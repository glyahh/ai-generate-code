# glyahh-ai-generate-code 解耦落点速查

> 仅在 Step 3 需要选择包/类职责时读取；保持与 `CLAUDE.md` 一致。

## 分层与依赖方向

```
controller  →  service  →  serviceImpl  →  mapper / entity
                ↓
         AiCodeGeneratorFacade / WorkflowCodeGeneratorFacade
                ↓
    CodeParserExecutor / CodeFileSaverExecutor / StreamHandler*
                ↓
            core/parser | core/saver | core/util
```

- 依赖只允许**向下**（上层依赖下层），`core` 不依赖 `controller`。
- Facade 编排流程；**不要把长算法留在 Facade**，抽到 `support` / `handler` / 独立 `*Helper`。

## 按场景选落点

| 场景 | 建议位置 | 注意 |
|------|----------|------|
| 解析 AI 输出为结构化代码 | `core/parser/*` + 注册到 `CodeParserExecutor` | 新 `CodeGenTypeEnum` 时成对加 Saver |
| 写盘/目录布局 | `core/saver/*` + `CodeFileSaverExecutor` | 路径约定见 `AppConstant` |
| SSE/流式片段处理 | `core/handler` 或现有 `*StreamHandler` | 与 `workflowMode` 分支对齐 |
| LangGraph 单步逻辑 | `LangGraph4j/node/*` | 状态放 `state/`，工具放 `tools/` |
| 无状态工具函数 | `core/util` 或同包 `support` | 避免静态全局可变状态 |
| LangChain4j 工具/记忆 | `ai/` 子包 | 不把 HTTP/Servlet 依赖带进 `ai` |
| 配置装配 | `config/` 或 `*Factory` | 业务逻辑勿堆进 `@Configuration` |

## Facade 拆分信号

出现以下情况优先抽离（用户代码仍原样迁出）：

- 单类 >400 行且含 2+ 以上无关职责
- 同一私有方法被多处复制
- 同时操作「解析 + 保存 + 流式推送 + DB」
- 测试难以单测的中间大块逻辑

## 前端（仅当用户指定前端文件时）

- 目录语义对齐 `build-frontend`：`components/`、`composables/`、`services/`、`utils/`
- 不修改 `ai-generate-code-frontend` 除非用户明确包含前端路径

## 验证命令（默认）

```bash
./mvnw -q -DskipTests compile
```

触及测试类时：`./mvnw test -Dtest=相关类名`
