#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from tkinter import Canvas, messagebox, simpledialog, ttk

import customtkinter as ctk

MODEL_DIR = Path(__file__).resolve().parents[1]
if str(MODEL_DIR) not in sys.path:
    sys.path.insert(0, str(MODEL_DIR))

import main as core  # noqa: E402


class ModelGuiApp(ctk.CTk):
    def __init__(self) -> None:
        super().__init__()
        self.title("模型配置管理 GUI")
        self.geometry("1200x760")
        self.minsize(1080, 680)
        ctk.set_appearance_mode("System")
        ctk.set_default_color_theme("blue")
        self._configure_treeview_style()

        self.app_yml = ctk.StringVar(value=str(core.APP_YML))
        self.local_yml = ctk.StringVar(value=str(core.LOCAL_YML))
        self.config_yml = ctk.StringVar(value=str(MODEL_DIR / "config.example.yml"))
        self.platform_name = ctk.StringVar(value="")
        self.query_url = ctk.StringVar(value="")
        self.query_api_key = ctk.StringVar(value="")

        self.replace_rows: list[dict[str, str]] = []

        self._build_layout()
        self.show_page("map")
        self.refresh_map()

    @staticmethod
    def _configure_treeview_style() -> None:
        style = ttk.Style()
        style.configure("Treeview", font=("Microsoft YaHei UI", 12), rowheight=28)
        style.configure("Treeview.Heading", font=("Microsoft YaHei UI", 12, "bold"))

    def _build_layout(self) -> None:
        self.grid_columnconfigure(1, weight=1)
        self.grid_rowconfigure(0, weight=1)
        self.grid_rowconfigure(1, weight=0)

        nav = ctk.CTkFrame(self, width=220)
        nav.grid(row=0, column=0, sticky="nsew", padx=(10, 6), pady=10)
        nav.grid_propagate(False)

        ctk.CTkLabel(nav, text="模型工具", font=ctk.CTkFont(size=20, weight="bold")).pack(
            pady=(14, 20)
        )

        ctk.CTkButton(nav, text="模型映射", command=lambda: self.show_page("map")).pack(
            fill="x", padx=14, pady=6
        )
        ctk.CTkButton(nav, text="额度查询", command=lambda: self.show_page("quota")).pack(
            fill="x", padx=14, pady=6
        )
        ctk.CTkButton(nav, text="模型替换", command=lambda: self.show_page("replace")).pack(
            fill="x", padx=14, pady=6
        )

        self.page_host = ctk.CTkFrame(self)
        self.page_host.grid(row=0, column=1, sticky="nsew", padx=(6, 10), pady=10)
        self.page_host.grid_columnconfigure(0, weight=1)
        self.page_host.grid_rowconfigure(0, weight=1)

        self.map_page = self._build_map_page(self.page_host)
        self.quota_page = self._build_quota_page(self.page_host)
        self.replace_page = self._build_replace_page(self.page_host)

        log_frame = ctk.CTkFrame(self)
        log_frame.grid(row=1, column=0, columnspan=2, sticky="ew", padx=10, pady=(0, 10))
        log_frame.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(log_frame, text="运行日志").grid(row=0, column=0, sticky="w", padx=10, pady=(8, 0))
        self.log_box = ctk.CTkTextbox(log_frame, height=120)
        self.log_box.grid(row=1, column=0, sticky="ew", padx=10, pady=(4, 10))
        self.log("GUI 启动完成。可直接开始操作。")

    def _build_map_page(self, parent: ctk.CTkFrame) -> ctk.CTkFrame:
        frame = ctk.CTkFrame(parent)
        frame.grid(row=0, column=0, sticky="nsew")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        toolbar = ctk.CTkFrame(frame)
        toolbar.grid(row=0, column=0, sticky="ew", padx=10, pady=10)
        for i in range(5):
            toolbar.grid_columnconfigure(i, weight=0)
        toolbar.grid_columnconfigure(1, weight=1)
        toolbar.grid_columnconfigure(3, weight=1)

        ctk.CTkLabel(toolbar, text="application.yml").grid(row=0, column=0, padx=6, pady=8)
        ctk.CTkEntry(toolbar, textvariable=self.app_yml).grid(row=0, column=1, padx=6, pady=8, sticky="ew")
        ctk.CTkLabel(toolbar, text="application-local.yml").grid(row=0, column=2, padx=6, pady=8)
        ctk.CTkEntry(toolbar, textvariable=self.local_yml).grid(row=0, column=3, padx=6, pady=8, sticky="ew")
        ctk.CTkButton(toolbar, text="刷新映射", command=self.refresh_map).grid(row=0, column=4, padx=6, pady=8)

        columns = ("function", "model_name", "source_file", "path")
        tree_wrap = ctk.CTkFrame(frame)
        tree_wrap.grid(row=1, column=0, sticky="nsew", padx=10, pady=(0, 10))
        tree_wrap.grid_columnconfigure(0, weight=1)
        tree_wrap.grid_rowconfigure(0, weight=1)
        tree = ttk.Treeview(tree_wrap, columns=columns, show="headings")
        for col, w in [("function", 220), ("model_name", 260), ("source_file", 180), ("path", 420)]:
            tree.heading(col, text=col)
            tree.column(col, width=w, anchor="w", stretch=True)
        tree.grid(row=0, column=0, sticky="nsew")
        y_scroll = ttk.Scrollbar(tree_wrap, orient="vertical", command=tree.yview)
        x_scroll = ttk.Scrollbar(tree_wrap, orient="horizontal", command=tree.xview)
        tree.configure(yscrollcommand=y_scroll.set, xscrollcommand=x_scroll.set)
        y_scroll.grid(row=0, column=1, sticky="ns")
        x_scroll.grid(row=1, column=0, sticky="ew")
        self.map_tree = tree
        return frame

    def _build_quota_page(self, parent: ctk.CTkFrame) -> ctk.CTkFrame:
        frame = ctk.CTkFrame(parent)
        frame.grid(row=0, column=0, sticky="nsew")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        toolbar = ctk.CTkFrame(frame)
        toolbar.grid(row=0, column=0, sticky="ew", padx=10, pady=10)
        for i in range(9):
            toolbar.grid_columnconfigure(i, weight=0)
        toolbar.grid_columnconfigure(1, weight=1)
        toolbar.grid_columnconfigure(3, weight=1)
        toolbar.grid_columnconfigure(5, weight=1)

        ctk.CTkLabel(toolbar, text="查询URL(可选)").grid(row=0, column=0, padx=6, pady=8)
        ctk.CTkEntry(toolbar, textvariable=self.query_url, placeholder_text="例如 https://.../model-usage...").grid(
            row=0, column=1, padx=6, pady=8, sticky="ew"
        )
        ctk.CTkLabel(toolbar, text="API Key(可选)").grid(row=0, column=2, padx=6, pady=8)
        ctk.CTkEntry(toolbar, textvariable=self.query_api_key, show="*", placeholder_text="用于需要 Bearer 认证的URL").grid(
            row=0, column=3, padx=6, pady=8, sticky="ew"
        )
        ctk.CTkLabel(toolbar, text="quota config").grid(row=1, column=0, padx=6, pady=8)
        ctk.CTkEntry(toolbar, textvariable=self.config_yml).grid(row=1, column=1, padx=6, pady=8, sticky="ew")
        ctk.CTkLabel(toolbar, text="控制台整页内容").grid(row=1, column=2, padx=6, pady=8)
        ctk.CTkEntry(toolbar, textvariable=self.platform_name, placeholder_text="可直接粘贴整页文本；留空则按 config/platform").grid(
            row=1, column=3, padx=6, pady=8, sticky="ew"
        )
        ctk.CTkButton(toolbar, text="执行查询", command=self.refresh_quota).grid(row=0, column=4, rowspan=2, padx=6, pady=8)
        ctk.CTkButton(toolbar, text="剪贴板解析", command=self.parse_quota_from_clipboard).grid(
            row=0, column=5, rowspan=2, padx=6, pady=8
        )
        ctk.CTkButton(toolbar, text="复制JSON", command=self.copy_quota_json).grid(row=0, column=6, rowspan=2, padx=6, pady=8)
        ctk.CTkButton(toolbar, text="可视化", command=self.show_quota_pie_chart).grid(row=0, column=7, rowspan=2, padx=6, pady=8)

        columns = ("function", "model_name", "platform", "quota", "method", "error")
        tree_wrap = ctk.CTkFrame(frame)
        tree_wrap.grid(row=1, column=0, sticky="nsew", padx=10, pady=(0, 10))
        tree_wrap.grid_columnconfigure(0, weight=1)
        tree_wrap.grid_rowconfigure(0, weight=1)
        tree = ttk.Treeview(tree_wrap, columns=columns, show="headings")
        widths = [180, 220, 140, 100, 90, 440]
        for col, w in zip(columns, widths):
            tree.heading(col, text=col)
            tree.column(col, width=w, anchor="w", stretch=False)
        tree.grid(row=0, column=0, sticky="nsew")
        y_scroll = ttk.Scrollbar(tree_wrap, orient="vertical", command=tree.yview)
        x_scroll = ttk.Scrollbar(tree_wrap, orient="horizontal", command=tree.xview)
        tree.configure(yscrollcommand=y_scroll.set, xscrollcommand=x_scroll.set)
        y_scroll.grid(row=0, column=1, sticky="ns")
        x_scroll.grid(row=1, column=0, sticky="ew")
        self.quota_tree = tree
        self.latest_quota_rows: list[dict[str, str]] = []
        return frame

    def _build_replace_page(self, parent: ctk.CTkFrame) -> ctk.CTkFrame:
        frame = ctk.CTkFrame(parent)
        frame.grid(row=0, column=0, sticky="nsew")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        top = ctk.CTkFrame(frame)
        top.grid(row=0, column=0, sticky="ew", padx=10, pady=10)
        for i in range(8):
            top.grid_columnconfigure(i, weight=0)
        top.grid_columnconfigure(1, weight=1)
        top.grid_columnconfigure(3, weight=1)
        ctk.CTkLabel(top, text="功能").grid(row=0, column=0, padx=6, pady=8)
        self.function_option = ctk.CTkOptionMenu(top, values=["(请先加载映射)"])
        self.function_option.grid(row=0, column=1, padx=6, pady=8, sticky="ew")
        ctk.CTkLabel(top, text="新模型名").grid(row=0, column=2, padx=6, pady=8)
        self.new_model_entry = ctk.CTkEntry(top, placeholder_text="例如 qwen3.6-plus")
        self.new_model_entry.grid(row=0, column=3, padx=6, pady=8, sticky="ew")
        ctk.CTkButton(top, text="添加替换项", command=self.add_replace_item).grid(row=0, column=4, padx=6, pady=8)
        ctk.CTkButton(top, text="清空", command=self.clear_replace_items).grid(row=0, column=5, padx=6, pady=8)
        ctk.CTkButton(top, text="预览", command=self.preview_replace).grid(row=0, column=6, padx=6, pady=8)
        ctk.CTkButton(top, text="确认写入", command=self.apply_replace).grid(row=0, column=7, padx=6, pady=8)

        columns = ("function", "from", "to", "target_file")
        tree_wrap = ctk.CTkFrame(frame)
        tree_wrap.grid(row=1, column=0, sticky="nsew", padx=10, pady=(0, 10))
        tree_wrap.grid_columnconfigure(0, weight=1)
        tree_wrap.grid_rowconfigure(0, weight=1)
        tree = ttk.Treeview(tree_wrap, columns=columns, show="headings")
        for col, w in [("function", 220), ("from", 280), ("to", 280), ("target_file", 220)]:
            tree.heading(col, text=col)
            tree.column(col, width=w, anchor="w", stretch=True)
        tree.grid(row=0, column=0, sticky="nsew")
        y_scroll = ttk.Scrollbar(tree_wrap, orient="vertical", command=tree.yview)
        x_scroll = ttk.Scrollbar(tree_wrap, orient="horizontal", command=tree.xview)
        tree.configure(yscrollcommand=y_scroll.set, xscrollcommand=x_scroll.set)
        y_scroll.grid(row=0, column=1, sticky="ns")
        x_scroll.grid(row=1, column=0, sticky="ew")
        self.replace_tree = tree
        return frame

    def show_page(self, page: str) -> None:
        for p in (self.map_page, self.quota_page, self.replace_page):
            p.grid_remove()
        if page == "map":
            self.map_page.grid()
        elif page == "quota":
            self.quota_page.grid()
        else:
            self.replace_page.grid()
            self.refresh_replace_functions()

    def log(self, msg: str) -> None:
        self.log_box.insert("end", f"{msg}\n")
        self.log_box.see("end")

    @staticmethod
    def clear_tree(tree: ttk.Treeview) -> None:
        for row_id in tree.get_children():
            tree.delete(row_id)

    def refresh_map(self) -> None:
        try:
            rows = core.list_models_data(self.app_yml.get(), self.local_yml.get())
            self.clear_tree(self.map_tree)
            for row in rows:
                self.map_tree.insert("", "end", values=(row["function"], row["model_name"], row["source_file"], row["path"]))
            self.log(f"模型映射加载成功，共 {len(rows)} 项。")
            self.refresh_replace_functions(rows)
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("映射读取失败", str(e))
            self.log(f"模型映射读取失败: {e}")

    def refresh_quota(self) -> None:
        try:
            query_url = self.query_url.get().strip()
            if query_url:
                rows = core.check_quota_by_url_data(
                    query_url=query_url,
                    app_yml=self.app_yml.get(),
                    local_yml=self.local_yml.get(),
                    timeout=20,
                    api_key=self.query_api_key.get().strip(),
                )
                self.log("已使用 URL 直查模式。")
            else:
                rows = core.check_quota_data(
                    config=self.config_yml.get(),
                    app_yml=self.app_yml.get(),
                    local_yml=self.local_yml.get(),
                    platform=self.platform_name.get().strip(),
                    timeout=20,
                )
                self.log("已使用 config 模式。")
            self.latest_quota_rows = rows
            self.clear_tree(self.quota_tree)
            for row in rows:
                self.quota_tree.insert(
                    "",
                    "end",
                    values=(row["function"], row["model_name"], row["platform"], row["quota"], row["method"], row["error"]),
                )
            self.log(f"额度查询完成，共 {len(rows)} 条。")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("额度查询失败", str(e))
            self.log(f"额度查询失败: {e}")

    def parse_quota_from_clipboard(self) -> None:
        typed_text = self.platform_name.get().strip()
        if typed_text:
            page_text = typed_text
        else:
            try:
                page_text = self.clipboard_get()
            except Exception as e:  # noqa: BLE001
                messagebox.showerror("剪贴板读取失败", str(e))
                self.log(f"剪贴板读取失败: {e}")
                return

        if not page_text or len(page_text.strip()) < 20:
            messagebox.showwarning("提示", "内容太短，请先粘贴或复制“模型用量”页面整页文本。")
            return

        rows = core.check_quota_from_text_data(
            page_text=page_text,
            app_yml=self.app_yml.get(),
            local_yml=self.local_yml.get(),
        )
        self.latest_quota_rows = rows
        self.clear_tree(self.quota_tree)
        for row in rows:
            self.quota_tree.insert(
                "",
                "end",
                values=(row["function"], row["model_name"], row["platform"], row["quota"], row["method"], row["error"]),
            )
        self.log("已使用剪贴板/整页文本解析模式。")

    @staticmethod
    def _parse_quota_numbers(quota_text: str) -> tuple[float, float] | None:
        if not quota_text:
            return None
        cleaned = quota_text.replace("剩", "").replace("余", "").replace("共", "").replace("+", "")
        m = re.search(r"([\d,]+(?:\.\d+)?)\s*/\s*([\d,]+(?:\.\d+)?)", cleaned)
        if not m:
            return None
        remain = float(m.group(1).replace(",", ""))
        total = float(m.group(2).replace(",", ""))
        if total <= 0:
            return None
        used = max(total - remain, 0.0)
        return used, total

    def show_quota_pie_chart(self) -> None:
        if not self.latest_quota_rows:
            messagebox.showwarning("提示", "当前没有可视化数据，请先执行查询或解析。")
            return

        model_stats: list[dict[str, float | str]] = []
        for row in self.latest_quota_rows:
            parsed = self._parse_quota_numbers(str(row.get("quota", "")))
            if not parsed:
                continue
            used, total = parsed
            model = str(row.get("model_name", "unknown"))
            remain = max(total - used, 0.0)
            used_pct = (used / total * 100.0) if total > 0 else 0.0
            model_stats.append(
                {
                    "model": model,
                    "used": used,
                    "remain": remain,
                    "total": total,
                    "used_pct": used_pct,
                }
            )

        if not model_stats:
            messagebox.showwarning("提示", "未解析到可用额度数据，无法生成饼图。")
            return

        win = ctk.CTkToplevel(self)
        win.title("模型用量可视化")
        win.geometry("1180x860")
        win.lift()

        ctk.CTkLabel(win, text="模型用量可视化", font=ctk.CTkFont(size=20, weight="bold")).pack(pady=(12, 4))

        hover_tip = ctk.CTkLabel(
            win,
            text="悬停任意饼图查看模型信息",
            font=ctk.CTkFont(size=13),
        )
        hover_tip.pack(pady=(0, 8))

        container = ctk.CTkFrame(win)
        container.pack(fill="both", expand=True, padx=12, pady=(0, 12))
        container.grid_columnconfigure(0, weight=1)
        container.grid_rowconfigure(0, weight=1)

        canvas = Canvas(container, bg="#1f1f1f", highlightthickness=0)
        canvas.grid(row=0, column=0, sticky="nsew")
        y_scroll = ttk.Scrollbar(container, orient="vertical", command=canvas.yview)
        y_scroll.grid(row=0, column=1, sticky="ns")
        canvas.configure(yscrollcommand=y_scroll.set)

        card_w = 520
        card_h = 430
        cols = 2
        pie_r = 170
        inner_r = 96
        used_color = "#3B82F6"
        remain_color = "#10B981"

        for i, stat in enumerate(model_stats):
            row_i = i // cols
            col_i = i % cols
            ox = col_i * card_w + 20
            oy = row_i * card_h + 20
            cx = ox + 220
            cy = oy + 180

            model = str(stat["model"])
            used = float(stat["used"])
            remain = float(stat["remain"])
            total = float(stat["total"])
            used_pct = float(stat["used_pct"])

            used_extent = 360.0 * (used / total) if total > 0 else 0.0
            tag = f"model_{i}"

            # 外圈边框
            canvas.create_oval(cx - pie_r - 2, cy - pie_r - 2, cx + pie_r + 2, cy + pie_r + 2, outline="#444", width=1)
            # 已用/剩余两色扇区（0 用量时整圆绿色）
            if used <= 0:
                canvas.create_oval(
                    cx - pie_r,
                    cy - pie_r,
                    cx + pie_r,
                    cy + pie_r,
                    fill=remain_color,
                    outline="#1f1f1f",
                    width=2,
                    tags=(tag, "pie"),
                )
            elif remain <= 0:
                canvas.create_oval(
                    cx - pie_r,
                    cy - pie_r,
                    cx + pie_r,
                    cy + pie_r,
                    fill=used_color,
                    outline="#1f1f1f",
                    width=2,
                    tags=(tag, "pie"),
                )
            else:
                canvas.create_arc(
                    cx - pie_r,
                    cy - pie_r,
                    cx + pie_r,
                    cy + pie_r,
                    start=90,
                    extent=-used_extent,
                    fill=used_color,
                    outline="#1f1f1f",
                    width=2,
                    tags=(tag, "pie"),
                )
                canvas.create_arc(
                    cx - pie_r,
                    cy - pie_r,
                    cx + pie_r,
                    cy + pie_r,
                    start=90 - used_extent,
                    extent=-(360.0 - used_extent),
                    fill=remain_color,
                    outline="#1f1f1f",
                    width=2,
                    tags=(tag, "pie"),
                )
            # 中心镂空
            canvas.create_oval(cx - inner_r, cy - inner_r, cx + inner_r, cy + inner_r, fill="#1f1f1f", outline="#1f1f1f", tags=(tag,))
            canvas.create_text(cx, cy - 2, text=f"{used_pct:.1f}%", fill="#f5f5f5", font=("Microsoft YaHei UI", 22, "bold"), tags=(tag,))

            # 文本说明
            canvas.create_text(
                cx,
                oy + 370,
                text=model,
                fill="#f5f5f5",
                font=("Microsoft YaHei UI", 16, "bold"),
                width=420,
                tags=(tag,),
            )
            canvas.create_text(
                cx,
                oy + 398,
                text=f"已用 {used:,.0f}  |  剩余 {remain:,.0f}",
                fill="#c9c9c9",
                font=("Microsoft YaHei UI", 14),
                tags=(tag,),
            )

            def _on_enter(_event, m=model, u=used, r=remain, p=used_pct) -> None:
                hover_tip.configure(text=f"模型: {m} | 已用: {u:,.0f} | 剩余: {r:,.0f} | 已用占比: {p:.2f}%")

            def _on_leave(_event) -> None:
                hover_tip.configure(text="悬停任意饼图查看模型信息")

            canvas.tag_bind(tag, "<Enter>", _on_enter)
            canvas.tag_bind(tag, "<Leave>", _on_leave)

        rows_count = (len(model_stats) + cols - 1) // cols
        total_h = max(rows_count * card_h + 20, 760)
        total_w = cols * card_w + 10
        canvas.configure(scrollregion=(0, 0, total_w, total_h))

    def copy_quota_json(self) -> None:
        payload = json.dumps(self.latest_quota_rows, ensure_ascii=False, indent=2)
        self.clipboard_clear()
        self.clipboard_append(payload)
        self.log("额度结果 JSON 已复制到剪贴板。")

    def refresh_replace_functions(self, rows: list[dict[str, str]] | None = None) -> None:
        if rows is None:
            rows = core.list_models_data(self.app_yml.get(), self.local_yml.get())
        functions = [r["function"] for r in rows]
        if not functions:
            functions = ["(无功能可选)"]
        self.function_option.configure(values=functions)
        self.function_option.set(functions[0])

    def add_replace_item(self) -> None:
        function_key = self.function_option.get().strip()
        new_model = self.new_model_entry.get().strip()
        if function_key.startswith("("):
            messagebox.showwarning("提示", "当前没有可替换功能，请先检查映射。")
            return
        if not new_model:
            messagebox.showwarning("提示", "请输入新模型名。")
            return
        for item in self.replace_rows:
            if item["function"] == function_key:
                item["to"] = new_model
                self.log(f"替换项已更新: {function_key} -> {new_model}")
                self.new_model_entry.delete(0, "end")
                return
        self.replace_rows.append({"function": function_key, "to": new_model})
        self.new_model_entry.delete(0, "end")
        self.log(f"替换项已添加: {function_key} -> {new_model}")

    def clear_replace_items(self) -> None:
        self.replace_rows = []
        self.clear_tree(self.replace_tree)
        self.log("已清空替换项和预览结果。")

    def _build_mapping_dict(self) -> dict[str, str]:
        return {x["function"]: x["to"] for x in self.replace_rows}

    def preview_replace(self) -> None:
        if not self.replace_rows:
            messagebox.showwarning("提示", "请先添加替换项。")
            return
        try:
            changes = core.preview_replace_data(
                mapping=self._build_mapping_dict(),
                app_yml=self.app_yml.get(),
                local_yml=self.local_yml.get(),
            )
            self.clear_tree(self.replace_tree)
            for row in changes:
                self.replace_tree.insert("", "end", values=(row["function"], row["from"], row["to"], row["target_file"]))
            self.log(f"预览完成，预计变更 {len(changes)} 项。")
            if not changes:
                messagebox.showinfo("提示", "无变更，目标模型与当前一致。")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("预览失败", str(e))
            self.log(f"预览失败: {e}")

    def apply_replace(self) -> None:
        if not self.replace_rows:
            messagebox.showwarning("提示", "请先添加替换项。")
            return
        yes_text = simpledialog.askstring("二次确认", "将写入配置文件。请输入 YES 确认：")
        if yes_text != "YES":
            self.log("用户取消写入（未输入 YES）。")
            return
        try:
            changes = core.apply_replace_data(
                mapping=self._build_mapping_dict(),
                app_yml=self.app_yml.get(),
                local_yml=self.local_yml.get(),
            )
            self.clear_tree(self.replace_tree)
            for row in changes:
                self.replace_tree.insert("", "end", values=(row["function"], row["from"], row["to"], row["target_file"]))
            self.log(f"写入完成，实际变更 {len(changes)} 项。")
            messagebox.showinfo("完成", "模型配置已写入。")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("写入失败", str(e))
            self.log(f"写入失败: {e}")


def main() -> int:
    app = ModelGuiApp()
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
