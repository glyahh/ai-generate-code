# GUI 使用指南

本目录是 `Platform-Utils/model` 的图形化入口，面向不熟悉命令行的用户。

## 快速启动（推荐）

在 `Platform-Utils/model` 目录双击：

- `run_model_gui.bat`

启动器会自动提示以下状态：
- 正在检查 Python
- 正在检查并安装依赖
- 正在启动 GUI
- 启动失败时给出下一步命令

## 手动启动

```bash
cd Platform-Utils/model
pip install -r requirements.txt
python GUI/gui.py
```

## 四个页面怎么用

1. 模型映射
- 点击“刷新映射”
- 查看 `function -> model_name -> source_file`

2. 额度查询
- 先选择查询方式（API Key / URL+浏览器 / Config / 本地文件 / 剪贴板）
- 按提示填写参数后点击「执行查询」
- 百炼逐模型额度：用 URL+浏览器（Cookie 或 Playwright）或剪贴板模式
- DashScope 账户余额：用 API Key 模式（可自动读 application-local.yml）
- 配置仅两个文件：`quota.env.example`（样例，提交 Git）与 `quota.local.env`（本机，不提交）
- GUI **只回显** `quota.local.env`；先 `copy ../quota.env.example ../quota.local.env` 再填写
- Config 规则写在 local 的 `---` 下方
- 查询结果自动保存到 `../.local/quota/latest.json`

3. 模型替换
- 左侧选择 YAML **配置位**（如 `streaming-chat-model`），右侧立即显示 **配置名**、**当前 model_name**、路径与来源
- 选中后展开 **功能说明**，内容来自 `model-usage.json`
- 输入新模型名 →「添加替换项」→ 在「待写入」卡片队列中查看，可单独「移除」
- 右侧配置详情与「待写入 / 预览结果」之间可 **上下拖动分隔条** 调整底部列表高度
- 「预览」后在「预览结果」区查看变更摘要
- 「确认写入」并输入 `YES` 后：自动刷新映射/替换/模型功能数据；仅清理额度快照里与本次变更的配置位/模型名相关的记录，其它模型额度结果保留

4. 模型功能
- 左侧自动列出当前 YAML 中去重后的 `model_name`（与「模型映射」同源）
- 选中模型后，右侧上方只读展示绑定的 `function` 键（YAML 关联）
- 在右侧编辑该模型在项目中的实际用途说明（支持 `# 1.` 列表写法）
- 点击「保存当前」或「保存全部」写入 `../.local/model-usage.json`（本机，不提交 Git）
- 样例可参考 `../model-usage.example.json`；切换模型前若有未保存修改会提示
- 修改 YAML 后点「刷新模型列表」同步；已删除模型若仍有笔记会标「已不在配置」

5. 清理缓存（侧栏独立按钮）
- 扫描并勾选要删除的临时文件：额度查询快照 `latest.json`、Playwright 浏览器会话、可选 `.cache` 其它文件等
- **不会删除** `model-usage.json`（模型功能说明）与 `quota.local.env`
- 清理额度快照后，「额度查询」页表格会清空，需重新查询

## 常见问题

- 启动失败提示缺依赖  
  执行：`python -m pip install -r requirements.txt`

- 双击 `.bat` 一闪而过  
  在终端手动运行：`python GUI/gui.py` 查看具体报错

- 在 `GUI` 目录下误执行 `python GUI/gui.py`  
  这是错误路径，会变成 `GUI/GUI/gui.py`。  
  正确写法是：
  - 在 `model` 目录执行：`python GUI/gui.py`
  - 在 `GUI` 目录执行：`python gui.py`

- 替换后没生效  
  先在“模型映射”页刷新，确认 `source_file` 与 `model_name` 已更新
