# model 脚本说明

该目录提供一个独立 Python 工具，用于管理项目 `application.yml` / `application-local.yml` 的模型配置。
当然,你也可以使用 `GUI` 启动，见 `GUI/README.md`。

## 功能

1. 列出当前「功能 -> 模型名 -> 来源文件」映射。
2. 检查模型剩余额度（API 优先，失败自动回退网页抓取）。
3. 按功能一键替换 `model-name`（仅替换该字段）。

## 配置文件（仅两个）

| 文件 | 是否提交 Git | 用途 |
|------|----------------|------|
| `quota.env.example` | **是** | 仓库样例：GUI 默认值 + `---` 下方 Config 模式 platforms YAML |
| `quota.local.env` | **否** | 本机实际配置：复制样例后改名，填 Cookie 路径等私密项 |

```bash
copy quota.env.example quota.local.env
```

**GUI 回显**：仅读取 `quota.local.env` 填入输入框；样例文件不参与启动填表。

`---` 下方的 platforms 段写在 local 中，供 Config 模式使用；无 local 时 Config 用程序内置默认规则。

运行时生成的 `.local/quota/platforms.runtime.yml`、`.local/model-usage.json`、查询快照等已在 `.gitignore`，无需手改。

模型功能说明样例见 `model-usage.example.json`（可提交）；实际数据写入 `.local/model-usage.json`。

## 目录归类

- `main.py`：核心逻辑 + CLI 入口（也被 GUI 复用）。
- `GUI/gui.py`：图形化界面入口（CustomTkinter）。
- `GUI/README.md`：GUI 专用启动与使用引导。
- `run_model_gui.bat`：Windows 双击启动 GUI。
- `quota.env.example`：唯一提交的模型工具配置文件。
- `model-usage.example.json`：模型功能说明 JSON 样例（GUI「模型功能」页）。
- `model_usage/`：模型功能目录聚合与本地 JSON 读写（供 GUI 使用）。
- `cache_cleanup/`：GUI「清理缓存」扫描与删除临时文件。
- `requirements.txt`：Python 依赖清单。

## 安装依赖

```bash
cd Platform-Utils/model
pip install -r requirements.txt
```

## GUI 启动（推荐给小白）

在 `Platform-Utils/model` 目录双击 `run_model_gui.bat`，或：

```bash
python GUI/gui.py
```

启动时仅从 `quota.local.env` 回显到各页输入框（需先 `copy quota.env.example quota.local.env`）。

## 命令示例

### 1) 列出模型映射

```bash
python main.py list-models
python main.py list-models --json
```

### 2) 查询额度

```bash
python main.py check-quota
python main.py check-quota --platform dashscope --json
```

`--config` 可省略；默认由 env 文件合并生成 platforms 配置。

### GUI 额度查询（多模式）

| 方式 | 用途 |
|------|------|
| **API Key** | DashScope 账户余额；可自定义查询 URL / JSON 路径 |
| **URL + 浏览器** | 百炼控制台 model-usage：Cookie + Playwright |
| **Config 配置** | 按 env 文件 `---` 下方 platforms 多平台 API/WEB 模板 |
| **本地文件** | 解析 `.json` / `.html` / `.har` / `.txt` |
| **剪贴板/文本** | 控制台复制整页后粘贴解析 |

Playwright（可选）：

```bash
pip install playwright
playwright install chromium
```

浏览器会话缓存：`Platform-Utils/model/.cache/`（已 gitignore）

### 3) 一键替换模型

```bash
python main.py replace-model --mapping chat-model=qwen3.5-plus --apply
```

## 常见功能名

- `chat-model`：常规对话与非流式生成主模型。
- `streaming-chat-model`：流式代码生成（HTML、MultiFile）。
- `reasoning-streaming-chat-model`：复杂推理场景。
- `routing-chat-model`：轻量路由/分类。
- `code-exam-chat-model`：工作流代码质检。
