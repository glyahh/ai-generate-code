# model 脚本说明

该目录提供一个独立 Python 工具，用于管理项目 `application.yml` / `application-local.yml` 的模型配置。
当然,你也可以使用 `GUI` 启动，见 `GUI/README.md`。

## 功能

1. 列出当前“功能 -> 模型名 -> 来源文件”映射。
2. 检查模型剩余额度（API 优先，失败自动回退网页抓取）。
3. 按功能一键替换 `model-name`（仅替换该字段）。

## 目录归类

- `main.py`：核心逻辑 + CLI 入口（也被 GUI 复用）。
- `GUI/gui.py`：图形化界面入口（CustomTkinter）。
- `GUI/README.md`：GUI 专用启动与使用引导。
- `run_model_gui.bat`：Windows 双击启动 GUI（含启动提示与依赖检查）。
- `config.example.yml`：额度查询配置示例。
- `requirements.txt`：Python 依赖清单。

## 安装依赖

```bash
cd Platform-Utils/model
pip install -r requirements.txt
```

## GUI 启动（推荐给小白）

在 `Platform-Utils/model` 目录双击：

- `run_model_gui.bat`

或手动执行：

```bash
python GUI/gui.py
```

GUI 详细说明见：`GUI/README.md`

## 命令示例

### 1) 列出模型映射

```bash
python main.py list-models
python main.py list-models --json
```

### 2) 查询额度

先复制并修改配置：

```bash
cp config.example.yml config.yml
```

然后执行：

```bash
python main.py check-quota --config config.yml
python main.py check-quota --config config.yml --platform dashscope --json
```

说明：
- `platforms[].api` 支持 API 查询（优先）。
- API 失败时会自动尝试 `platforms[].web`。
- `web` 默认优先使用 `firecrawl` CLI；若本机没有该命令，会回退到 `requests` 直连抓取。
- `{model_name}`、`{function}` 可用于 URL、headers、params、body 模板。
- `${ENV:XXX}` 用于从环境变量读取密钥。
- GUI 里支持“查询URL(可选)”直查模式：有 URL 时优先按 URL 抓取并按模型名匹配额度。
- 如页面需要登录态（例如控制台页面），建议使用可直接访问的 API URL，或在浏览器登录后使用可携带鉴权参数的 URL。
- 若控制台 URL 查不到（SPA 动态页面常见），可用“剪贴板解析”：
  1) 浏览器打开模型用量页并看到额度数据
  2) `Ctrl+A` 全选后 `Ctrl+C`
  3) 回到 GUI 点击“剪贴板解析”

### 3) 一键替换模型（仅 model-name）

先预览（不写文件）：

```bash
python main.py replace-model --mapping chat-model=qwen3.5-plus routing-chat-model=qwen3.6-flash
```

确认后写入：

```bash
python main.py replace-model --mapping chat-model=qwen3.5-plus routing-chat-model=qwen3.6-flash --apply
```

替换规则：
- 若功能在 `application-local.yml` 存在，则优先修改它。
- 否则修改 `application.yml`。
- 仅修改 `model-name`，不会改 `base-url`、`api-key`、`max-tokens` 等字段。

## 常见功能名（结合本项目实际用途）

- `chat-model`：常规对话与非流式生成主模型，覆盖日常 AI 代码生成和普通问答场景。
- `streaming-chat-model`：用于 `AiService` 和 `Workflow` 的代码流式输出，重点用于 `HTML`、`MultiFile` 格式的边生成边返回。
- `reasoning-streaming-chat-model`：用于复杂推理场景（如需要更长上下文和更稳定推理链路的任务）。
- `routing-chat-model`：用于轻量路由/分类判断（例如判断走哪条生成流程或策略分支）。
- `code-exam-chat-model`：用于工作流中的代码质检与结构化检查，偏短输出、低延迟、低成本。
