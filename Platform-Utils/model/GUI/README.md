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

## 三个页面怎么用

1. 模型映射
- 点击“刷新映射”
- 查看 `function -> model_name -> source_file`

2. 额度查询
- 配置 `config.yml` 路径（可先用 `config.example.yml`）
- 可选填写平台名
- 点击“执行查询”

3. 模型替换
- 选择功能名
- 输入新模型名并“添加替换项”
- 点击“预览”
- 点击“确认写入”，并在弹窗输入 `YES`

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
