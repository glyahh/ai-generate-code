---
name: backend-design
description: 为 glyahh-ai-generate-code 项目的后端 Java 开发提供统一的架构和编码规范。使用 /backend-design 唤起。用于处理「修改」「编写」「架构」「环境重构」「重构」等请求时，仅修改后端代码并保持既有三层架构、全局异常处理与统一响应风格。
---

## 项目上下文与总原则

- 遵循本项目既有分层架构：`controller → service 接口 → serviceImpl → mapper / entity / DTO / VO`，以及 `core` 下的「门面 + 执行器 + 解析器/模板」模式（参考 `AiCodeGeneratorFacade`、`CodeParserExecutor`、`CodeFileSaverExecutor`）。
- 所有 Web 接口统一返回 `BaseResponse<T>`，优先通过 `ResultUtils.success` / `ResultUtils.error` 构建响应；需要自定义提示时可以使用 `new BaseResponse<>(code, data, message)`。
- 参数校验、业务前置校验、权限校验统一使用 `ThrowUtils.throwIf(...)` 搭配 `ErrorCode` / `MyException`，不要在 Controller 中随意使用 `if (...) return` 之类的早退返回。
- 依赖注入优先使用构造器注入 + Lombok：在 `controller`、`serviceImpl` 中使用 `@RequiredArgsConstructor`，同时配合 `@Slf4j` 打日志。
- 全局异常通过 `GlobalExceptionHandler` 兜底，业务代码中抛出 `MyException` 或通过 `ThrowUtils.throwIf` 抛出，不在控制层/服务层到处写 try-catch 吃掉异常。
- 统一使用 Hutool 工具类，如 `StrUtil` 做字符串判空，`BeanUtil` 做对象属性拷贝。
- **绝对不要修改前端目录**：禁止改动 `ai-generate-code-frontend/**` 下的任何文件，仅操作 Java 后端、SQL、Maven、YAML 等后端相关内容。

## 统一响应与异常处理风格

- 所有对外接口（`@RestController` 下的方法）返回类型统一为 `BaseResponse<T>`：
  - 正常成功：`return ResultUtils.success(data);`
  - 需要自定义提示：`return new BaseResponse<>(20000, data, "自定义提示");`
  - 特殊业务分支可以按现有风格使用 `new BaseResponse<>(code, data, message)`，但保持与项目已有语义一致（例如管理员已存在等场景）。
- 参数 & 状态校验：
  - 统一使用 `ThrowUtils.throwIf(condition, ErrorCode.PARAMS_ERROR, "具体错误信息");`
  - 登录态校验：`ThrowUtils.throwIf(user == null || user.getId() == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录或会话失效");`
  - 资源不存在：`ThrowUtils.throwIf(entity == null, ErrorCode.NOT_FOUND_ERROR, "xxx 不存在");`
  - 权限不足：`ThrowUtils.throwIf(!hasPermission, ErrorCode.NO_AUTH_ERROR, "无权限/只能操作自己的资源");`
  - 通用系统错误：使用 `ErrorCode.SYSTEM_ERROR` 或 `ErrorCode.OPERATION_ERROR`，语义同项目现有代码。
- `GlobalExceptionHandler` 行为：
  - `MyException`：记录错误日志，包装为 `ResultUtils.error(e.getCode(), e.getMessage());`
  - 其他 `RuntimeException`：记录堆栈，包装为 `ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");`
  - 编写新的异常/错误处理逻辑时，保持与此模式兼容，不破坏现有异常分层。

## 针对「修改」场景的行为规范

当用户指令中包含**「修改」**字样时，执行以下策略：

1. **最小修改原则**
   - 只在必要的最小范围内修改代码，尽量局部变更，避免重排大段结构。
   - 保持方法签名、入参与返回类型不变，除非用户明确要求调整接口。
2. **保持现有风格与语义**
   - 继续使用 `ThrowUtils.throwIf` 做参数与权限校验，沿用现有错误码与错误信息风格。
   - 控制器里依旧通过 `ResultUtils.success` 返回统一响应。
   - 日志继续采用当前格式：`log.info("xxx, param={}", param);` 或 `log.error("xxx, appId={}, userId={}", appId, userId, e);`
3. **保留原有实现思路**
   - 若只做 Bug 修复或小优化，直接在原逻辑上微调即可。
   - 若必须大幅重构单个方法，也要优先采用「**提取新私有方法 + 旧方法委托调用新方法**」的方式，保持原方法作为入口存在，以便回滚。

## 针对「编写」场景的行为规范

当用户指令中包含**「编写」**字样时，视为**新增功能/模块开发**，按以下步骤进行：

1. **确定分层位置**
   - 对外 HTTP 接口：在对应的 `controller` 包下新增或扩展控制器类，例如 `AppController` 风格。
   - 业务逻辑：在 `service` 包新增接口、在 `service.impl` 包新增实现类或扩展现有实现。
   - 持久层：通过 MyBatis-Flex 的 `mapper` + `entity`，尽量复用现有模式。
   - 复杂业务算法/流程：考虑抽象到 `core` 包下对应的门面 / 执行器 / 模板，而不是堆在某个 Service 里。
2. **接口设计**
   - 控制层使用 `@RestController` + `@RequestMapping`，方法使用 `@PostMapping` / `@GetMapping` 等注解，路径风格参考现有 `/app/**` 等。
   - 请求参数通过 `DTO`（`model.DTO` 包）统一封装，返回结果通过 `VO`（`model.VO` 包）暴露，不直接把 `Entity` 暴露给前端。
   - 校验请求参数（空值、格式、长度、权限等）统一使用 `ThrowUtils.throwIf`。
3. **Service 实现**
   - 在 `service` 中声明接口方法，在 `service.impl` 中使用 `@Service` + `@RequiredArgsConstructor` 实现。
   - 访问数据库时优先使用 `service` 上的 `save` / `updateById` / `page` 等高层 API，而不是直接调用 `mapper.insert`，参考 `AppController` 中关于 `save` 的注释。
4. **返回与日志**
   - 所有控制层方法返回 `BaseResponse<T>`，成功统一用 `ResultUtils.success`。
   - 关键节点做必要的 `info` 或 `warn`/`error` 日志，避免过度或不足。

## 针对「架构」场景的行为规范

当用户指令中包含**「架构」**字样时，优先从架构/解耦角度设计，而不是直接在现有类里堆代码：

1. **优先考虑「门面 + 执行器 + 模板/解析器」模式**
   - 参考 `AiCodeGeneratorFacade`：对外暴露少量统一入口方法，内部组合调用多个执行器和解析/保存逻辑。
   - 参考 `CodeParserExecutor` / `CodeFileSaverExecutor`：使用 `enum` + `switch` 分发，更清晰地区分不同类型的处理分支。
2. **通过新文件降低耦合**
   - 如果某个功能看起来与现有类职责不完全相符，先评估是否新建：
     - 新的 `core` 模块类（例如新的解析器、模板或执行器）。
     - 新的 `service` 接口及实现，用于包裹第三方服务或内部复杂流程。
     - 新的 `common` 工具类或常量类（例如新的 `***Constant`）。
3. **限制方法职责范围**
   - 控制层方法只做参数解析、权限校验、调用 service、包装返回值。
   - service 方法控制事务边界，协调多个 repository/mapper 调用。
   - 纯算法/解析/转换逻辑下沉到 `core` 或工具类中。

## 针对「环境重构」场景的行为规范

当用户指令中包含**「环境重构」**字样时，优先考虑配置与环境解耦，而不是业务代码改动：

1. **抽取公共配置**
   - 分析 `application.yml` 以及可能存在的 profile-specific 配置，识别可复用的公共部分（如 `langchain4j.open-ai.chat-model`、数据库连接信息）。
   - 将环境相关敏感字段（用户名、密码、API Key、模型名称等）统一使用占位符 `${...}`，与现有写法保持一致，而不是写死到 YAML 中。
2. **分环境管理**
   - 若需要更细粒度环境控制，可以新增如 `application-local.yml`、`application-prod.yml` 等文件，并通过 `spring.profiles.active` 切换。
   - 重构时不改变现有 profile 行为（例如已使用 `local` 的逻辑），只在此基础上抽取公共部分、消除重复。
3. **Maven 与版本管理**
   - 对于重复使用的版本号（如 Lombok、LangChain4j、MyBatis-Flex 等），优先抽取到 `<properties>`，保持版本集中管理。

## 针对「重构」场景的行为规范

当用户指令中包含**「重构」**字样时，理解为对**单个代码块/模块的结构性优化**，必须遵守以下关键原则：

1. **严格遵守「保留原代码块」要求**
   - 重构后**不要删除原先的代码块**，可以：
     - 将原实现提取成 `legacy`/`old` 私有方法，并在注释中标明「旧实现，保留以备回滚」；
     - 或者保留原有方法，新增新的实现方法，并在合适位置用新方法替换调用。
   - 明确在注释中说明新旧实现的关系，方便后续比对和回滚。
2. **对齐 core 模块设计思想**
   - 参考 `core` 包中已有的 2~4 文件一组的模块化设计（如「执行器 + 解析器 + 模板」），在重构时优先：
     - 将解析/转换逻辑抽出为新的解析器类。
     - 将 IO/文件保存逻辑抽出为新的模板类。
     - 用执行器统一对外暴露入口，内部根据枚举或类型分发。
3. **精细控制重构范围**
   - 当用户只指定「重构某段代码/某个方法」时，仅对该段代码或方法进行重构，不推倒重写整个类或整个模块。
   - 若发现跨文件的架构问题，可在说明中提出建议，但实际修改严格限制在用户指定范围内。

## 其他编码风格约束

- 包名与类名沿用既有命名习惯，例如：
  - `model.DTO` / `model.VO` / `model.Entity` / `model.enums`。
  - 常量类使用 `***Constant` 命名，枚举使用 `***Enum` 命名。
- 使用 Lombok：
  - 数据类（DTO/VO/Entity）建议使用 `@Data`。
  - 服务实现、控制器中使用 `@RequiredArgsConstructor` + `final` 字段注入依赖。
- 日志：
  - 使用 `@Slf4j`，不使用系统标准输出。
  - 错误日志包含关键信息（如 `appId`、`userId` 等），并附带异常栈。

按照以上 Skill 规范，在后续处理「修改 / 编写 / 架构 / 环境重构 / 重构」等相关请求时，始终优先遵循本文件中的约束与项目现有风格，确保新增/修改代码与原有代码高度一致且易于回滚。
