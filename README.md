# glyahh-ai-generate-code

面向应用（App）维度的 **对话式 AI 应用合成与制品交付平台**：以 SSE 流式管线将模型输出收敛为可版本化、可发布的端侧运行制品（单文件页面、多模块资源树、工程级脚手架）；会话记忆与持久化分层，在长对话下对上下文做窗口化与摘要压缩，控制 token 曲线与调用成本。

## 项目定位

- **生成单元**：每个应用维护 `initPrompt`、合成目标类型（单页 HTML、多文件资源集、Vue 3 工程骨架）、发布标识与运营维度（如精选优先级）。
- **交付链路**：SSE 流式输出 → Parser 结构化解析 → Saver 落盘至 `temp/code_output` → 发布阶段同步至 `temp/code_deploy/{deployKey}`，对外按 `deployKey` 提供制品的文件级访问与 MIME 感知响应。
- **会话治理**：MySQL 承载对话审计与业务态；Redis 承载 LangChain4j Chat Memory Store、窗口裁剪与超轮次模型摘要（例如超过 20 轮时对最早两轮压缩合并，摘要回写 Redis，不直接改写历史库表）。

## 已实现能力

| 域 | 说明 |
|----|------|
| 流式生成 | `GET /api/chat/gen/code`（`text/event-stream`），Reactor `Flux<ServerSentEvent>`，配合会话侧限流（如每用户 60s 内 5 次，以实际配置为准）。 |
| 解析 / 落盘 | `core/parser` + `core/saver` 模板化管线，`CodeGenTypeEnum` 执行器分发，便于横向扩展新的制品形态。 |
| AI 运行时 | LangChain4j 栈（OpenAI-Compatible 协议，可对接 DashScope 等）；多模型路由（对话、流式 completion、推理、轻量分类）；Tool 层覆盖文件读写改删、目录枚举、Vue SFC 语法校验等。 |
| 在线发布与制品托管 | 生成唯一 `deployKey`；`DeployResourceController` 等按内容类型返回制品字节流，支撑预览与外链分发场景。 |
| 权限与运营 | `@MyRole` + AOP 鉴权；用户 / 管理员角色；应用申请与审核闭环；精选应用（如 `priority >= 99`）。 |
| 对话历史 | 游标分页、按应用导出、轮次统计；管理员侧审计查询。 |

设计说明见 `docs/ARCHITECTURE-Parser-Saver-重构模式总结.md`；接口清单见 `docs/后端接口文档.md`。OpenAPI / Knife4j：`http://<host>:8124/api/doc.html`（上下文路径 `/api`）。

## 技术栈

### 运行时与工程

| 类别 | 技术选型（版本以 `pom.xml` 为准） |
|------|----------------------------------|
| 语言与框架 | Java **21**，Spring Boot **3.5.10**（Spring Web、Spring AOP） |
| 数据访问 | MyBatis-Flex **1.11.1**（含 `mybatis-flex-codegen`），MySQL（`mysql-connector-j`），连接池 **HikariCP 4.0.3** |
| 缓存与会话 | **Caffeine**（本地热点缓存），**Redis**（Lettuce 等，随 Spring Boot 管理），**Spring Session Data Redis**（分布式会话） |
| 分布式协调与锁 | **Redisson 3.50.0** |
| 工具与规范 | **Lombok 1.18.36**，**Hutool 5.8.40** |
| API 契约 | **Knife4j 4.4.0**（OpenAPI 3 Jakarta） |

### AI 与响应式

| 类别 | 技术选型 |
|------|----------|
| LLM 集成 | **LangChain4j 1.1.0**，`langchain4j-open-ai-spring-boot-starter` **1.1.0-beta7**（OpenAI-Compatible 接入） |
| 记忆存储 | `langchain4j-community-redis-spring-boot-starter` **1.1.0-beta7** |
| 流式与响应式桥接 | `langchain4j-reactor` **1.1.0-beta7**，**Project Reactor**（SSE 流） |
| 厂商 SDK | 阿里云 **DashScope SDK 2.22.9**（与兼容协议并存，按配置选用） |
| 工作流编排（依赖已引入） | **LangGraph4j 1.8.3**（`langgraph4j-core`） |

### 对象存储与自动化

| 类别 | 技术选型 |
|------|----------|
| 对象存储 | 阿里云 **OSS SDK 3.15.1** |
| 浏览器端截图 / 巡检 | **Selenium Java 4.33.0**，**WebDriverManager 6.1.0** |

## 快速开始

**服务端**（需 MySQL、Redis 及可用的大模型端点与密钥）：

```bash
./mvnw spring-boot:run
```

默认端口 **8124**，上下文路径 **`/api`**。数据源、Redis、LangChain4j 与厂商密钥等在 `src/main/resources/application.yml` 中配置。

仓库内 `ai-generate-code-frontend/` 为可选配套控制台，与核心交付链路解耦，可按需启用。

## 核心接口（节选）

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/chat/gen/code` | 流式生成 |
| POST | `/api/app/add` | 创建应用 |
| POST | `/api/app/deploy` | 发布应用制品 |
| POST | `/api/app/undeploy` | 撤回发布 |
| GET | `/api/chatHistory/app/{appId}` | 对话历史分页 |
| GET | `/api/static/{deployKey}/**` | 已发布制品的文件访问（路径名保持与实现一致） |

完整路径以前缀 `/api` 及环境 `server.port` 为准。

## 演进规划（摘录）

- **能力面扩展**：应用封面、制品打包下载、智能路由等。
- **生成上下文增强**：请求侧扩展字段与结构化语义；元数据持久化与多会话协同。
- **编排深化**：基于 LangGraph4j 的多步合成与工具编排落地。
- **系统级优化**：吞吐与时延、限流与安全策略、Prompt 治理、稳定性与成本曲线。
- **生产化与可观测**：公开环境部署与监控体系。

## 许可证

**glyahh-ai-generate-code** 以 [MIT 许可证](LICENSE) 发布。对外再分发时须保留原始版权声明与完整许可证全文。
