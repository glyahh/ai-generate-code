#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
import threading
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk

import customtkinter as ctk

MODEL_DIR = Path(__file__).resolve().parents[1]
if str(MODEL_DIR) not in sys.path:
    sys.path.insert(0, str(MODEL_DIR))

import main as core  # noqa: E402
from cache_cleanup import (  # noqa: E402
    PROTECTED_ITEMS,
    CacheItem,
    clear_cache_entries,
    format_size,
    prune_quota_cache_for_replace_changes,
    scan_cache_items,
)
from model_usage import (  # noqa: E402
    MODEL_USAGE_FILE,
    ModelUsageEntry,
    build_function_replace_context,
    build_model_catalog,
    catalog_to_descriptions,
    detect_v1_orphan_models,
    is_placeholder_model,
    load_model_usage,
    migrate_model_usage_for_replace,
    normalize_model_name,
    prune_orphan_model_usage,
    save_model_usage,
)
from quota.engine import default_browser_profile_dir, run_quota_query  # noqa: E402
from quota.sync_rows import align_quota_rows_with_mappings  # noqa: E402
from GUI.quota_config_dialog import open_quota_config_dialog  # noqa: E402
from GUI.quota_setup_panel import QuotaUrlBrowserGuidePanel  # noqa: E402
from GUI.quota_visualization import open_quota_visualization  # noqa: E402
from quota.persist import (  # noqa: E402
    QUOTA_ENV_EXAMPLE,
    QUOTA_ENV_FILE,
    apply_env_to_gui,
    load_gui_env_local,
    load_quota_snapshot,
    materialize_platforms_config,
    firecrawl_api_key_from_env,
    resolve_config_yml_path,
    save_quota_snapshot,
    snapshot_path_display,
    start_page_from_env,
)
from quota.providers.dashscope import CREDIT_JSON_PATH, DASHSCOPE_CREDIT_URL  # noqa: E402
from quota.types import (  # noqa: E402
    PlaywrightSearchMode,
    QuotaQueryMode,
    QuotaQueryRequest,
    QuotaQueryResult,
)

QUOTA_MODE_LABELS: dict[str, str] = {
    "config": "Config 配置",
    "api_key": "API Key",
    "url_browser": "URL + 浏览器",
    "local_file": "本地文件",
    "clipboard": "剪贴板/文本",
}
QUOTA_MODE_KEY_BY_LABEL: dict[str, str] = {v: k for k, v in QUOTA_MODE_LABELS.items()}

MAP_TREE_COLUMNS = ("function", "model_name", "source_file", "path")
MAP_TREE_HEADINGS = {
    "function": "配置位",
    "model_name": "模型名",
    "source_file": "来源",
    "path": "YAML 路径",
}
QUOTA_TREE_COLUMNS = ("function", "model_name", "platform", "quota", "method", "error")
QUOTA_TREE_HEADINGS = {
    "function": "配置位",
    "model_name": "模型名",
    "platform": "平台",
    "quota": "额度",
    "method": "方式",
    "error": "说明",
}


class ModelGuiApp(ctk.CTk):
    def __init__(self) -> None:
        super().__init__()
        self.title("模型工具 · 配置管理")
        self._quota_viz_window = None
        self._quota_config_toplevel = None
        self.geometry("1200x760")
        self.minsize(1080, 680)
        ctk.set_appearance_mode("System")
        ctk.set_default_color_theme("blue")
        self._configure_treeview_style()

        self.app_yml = ctk.StringVar(value=str(core.APP_YML))
        self.local_yml = ctk.StringVar(value=str(core.LOCAL_YML))
        self.config_yml = ctk.StringVar(value=str(materialize_platforms_config()))
        self.platform_name = ctk.StringVar(value="")
        self.query_url = ctk.StringVar(value="")
        self.query_api_key = ctk.StringVar(value="")
        self.quota_mode = ctk.StringVar(value=QUOTA_MODE_LABELS["url_browser"])
        self.api_key_platform = ctk.StringVar(value="dashscope")
        self.api_key_endpoint = ctk.StringVar(value="")
        self.api_key_json_path = ctk.StringVar(value=CREDIT_JSON_PATH)
        self.read_key_from_yml = ctk.BooleanVar(value=True)
        self.cookie_file = ctk.StringVar(value="")
        self.use_playwright = ctk.BooleanVar(value=False)
        self.use_firecrawl_browser = ctk.BooleanVar(value=False)
        self.local_quota_file = ctk.StringVar(value="")
        self.browser_profile_dir = ctk.StringVar(value=default_browser_profile_dir())
        self._quota_mode_widgets: list[ctk.CTkBaseClass] = []
        self.quota_clipboard_box: ctk.CTkTextbox | None = None
        self._quota_busy = False
        self.quota_execute_btn: ctk.CTkButton | None = None
        self.quota_locate_btn: ctk.CTkButton | None = None
        self.quota_status_label: ctk.CTkLabel | None = None
        self.quota_config_btn: ctk.CTkButton | None = None
        self.quota_guide_panel: QuotaUrlBrowserGuidePanel | None = None

        self.replace_rows: list[dict[str, str]] = []
        self._replace_map_rows: list[dict[str, str]] = []
        self._replace_context: dict[str, dict[str, str]] = {}
        self._replace_selected: str | None = None
        self._replace_card_buttons: dict[str, ctk.CTkButton] = {}
        self._replace_preview_rows: list[dict[str, str]] = []
        self.latest_quota_rows: list[dict[str, str]] = []
        self._gui_env_source = ""

        self._usage_catalog: list[ModelUsageEntry] = []
        self._usage_selected: str | None = None
        self._usage_dirty = False
        self._usage_card_buttons: dict[str, ctk.CTkButton] = {}
        self._usage_nav_buttons: dict[str, ctk.CTkButton] = {}
        self._usage_saved: dict[str, dict[str, str]] = {}

        self._cleanup_items: list[CacheItem] = []
        self._cleanup_check_vars: dict[str, ctk.BooleanVar] = {}
        self._log_lines: list[str] = []
        self.log_full_box: ctk.CTkTextbox | None = None

        self._build_layout()
        self._apply_gui_env_from_files()
        start_page = start_page_from_env()
        self.show_page(start_page)
        if start_page == "map":
            self.refresh_map()
        elif start_page == "replace":
            self.refresh_replace_page()
        elif start_page == "usage":
            self.refresh_usage(silent=True)
        self._restore_quota_snapshot()

    @staticmethod
    def _configure_treeview_style() -> None:
        style = ttk.Style()
        style.theme_use("clam")
        body_font = ("Microsoft YaHei UI", 13)
        style.configure(
            "Premium.Treeview",
            font=body_font,
            rowheight=42,
            background="#1e293b",
            foreground="#e2e8f0",
            fieldbackground="#1e293b",
            borderwidth=0,
            relief="flat",
        )
        style.configure(
            "Premium.Treeview.Heading",
            font=("Microsoft YaHei UI", 12, "bold"),
            background="#0f172a",
            foreground="#cbd5e1",
            borderwidth=0,
            relief="flat",
            padding=(12, 10),
        )
        style.map(
            "Premium.Treeview",
            background=[("selected", "#2563eb")],
            foreground=[("selected", "#ffffff")],
        )
        style.configure(
            "Premium.Vertical.TScrollbar",
            background="#475569",
            troughcolor="#0f172a",
            borderwidth=0,
            arrowsize=13,
        )
        style.configure(
            "Premium.Horizontal.TScrollbar",
            background="#475569",
            troughcolor="#0f172a",
            borderwidth=0,
            arrowsize=13,
        )
        style.map(
            "Premium.Vertical.TScrollbar",
            background=[("active", "#64748b"), ("pressed", "#94a3b8")],
        )
        style.map(
            "Premium.Horizontal.TScrollbar",
            background=[("active", "#64748b"), ("pressed", "#94a3b8")],
        )
        style.layout("Premium.Treeview", style.layout("Treeview"))
        style.layout("Premium.Treeview.Heading", style.layout("Treeview.Heading"))

        large_body = ("Microsoft YaHei UI", 15)
        style.configure(
            "Premium.Large.Treeview",
            font=large_body,
            rowheight=52,
            background="#1e293b",
            foreground="#e2e8f0",
            fieldbackground="#1e293b",
            borderwidth=0,
            relief="flat",
        )
        style.configure(
            "Premium.Large.Treeview.Heading",
            font=("Microsoft YaHei UI", 14, "bold"),
            background="#0f172a",
            foreground="#cbd5e1",
            borderwidth=0,
            relief="flat",
            padding=(12, 12),
        )
        style.map(
            "Premium.Large.Treeview",
            background=[("selected", "#2563eb")],
            foreground=[("selected", "#ffffff")],
        )
        style.layout("Premium.Large.Treeview", style.layout("Treeview"))
        style.layout("Premium.Large.Treeview.Heading", style.layout("Treeview.Heading"))

    @staticmethod
    def _replace_panel_fg() -> tuple[str, str]:
        return ("#ffffff", "#1e293b")

    def _replace_paned_bg(self) -> str:
        light, dark = self._replace_panel_fg()
        mode = ctk.get_appearance_mode()
        if mode == "Light":
            return light
        return dark

    @staticmethod
    def _ui_card(parent: ctk.CTkBaseClass, **kwargs) -> ctk.CTkFrame:
        options = {
            "corner_radius": 12,
            "border_width": 1,
            "border_color": ("#e2e8f0", "#334155"),
            "fg_color": ("#ffffff", "#1e293b"),
        }
        options.update(kwargs)
        return ctk.CTkFrame(parent, **options)

    def _ui_page_header(
        self,
        parent: ctk.CTkFrame,
        *,
        title: str,
        subtitle: str,
        action_builder=None,
    ) -> ctk.CTkFrame:
        header = ctk.CTkFrame(parent, fg_color="transparent")
        header.grid_columnconfigure(0, weight=1)
        text_col = ctk.CTkFrame(header, fg_color="transparent")
        text_col.grid(row=0, column=0, sticky="w")
        ctk.CTkLabel(
            text_col,
            text=title,
            font=ctk.CTkFont(size=22, weight="bold"),
            anchor="w",
        ).pack(anchor="w")
        if subtitle.strip():
            ctk.CTkLabel(
                text_col,
                text=subtitle,
                font=ctk.CTkFont(size=13),
                text_color=("#64748b", "#94a3b8"),
                anchor="w",
                justify="left",
                wraplength=640,
            ).pack(anchor="w", pady=(4, 0))
        if action_builder is not None:
            actions = ctk.CTkFrame(header, fg_color="transparent")
            actions.grid(row=0, column=1, sticky="e", padx=(12, 0))
            action_builder(actions)
        return header

    @staticmethod
    def _ui_section_title(
        parent: ctk.CTkFrame,
        text: str,
        *,
        trailing: ctk.CTkBaseClass | None = None,
        font_size: int = 13,
    ) -> None:
        row = ctk.CTkFrame(parent, fg_color="transparent")
        row.pack(fill="x", padx=16, pady=(14, 8))
        row.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(
            row,
            text=text,
            font=ctk.CTkFont(size=font_size, weight="bold"),
            anchor="w",
        ).grid(row=0, column=0, sticky="w")
        if trailing is not None:
            trailing.grid(row=0, column=1, sticky="e")

    def _ui_primary_button(self, parent: ctk.CTkFrame, text: str, command, **kwargs) -> ctk.CTkButton:
        options = {
            "height": 36,
            "corner_radius": 8,
            "fg_color": ("#2563eb", "#1d4ed8"),
            "hover_color": ("#1d4ed8", "#1e40af"),
            "font": ctk.CTkFont(size=13, weight="bold"),
        }
        options.update(kwargs)
        return ctk.CTkButton(parent, text=text, command=command, **options)

    def _ui_secondary_button(self, parent: ctk.CTkFrame, text: str, command, **kwargs) -> ctk.CTkButton:
        options = {
            "height": 36,
            "corner_radius": 8,
            "fg_color": ("#e2e8f0", "#334155"),
            "hover_color": ("#cbd5e1", "#475569"),
            "text_color": ("#0f172a", "#f1f5f9"),
            "font": ctk.CTkFont(size=13),
        }
        options.update(kwargs)
        return ctk.CTkButton(parent, text=text, command=command, **options)

    def _ui_ghost_button(self, parent: ctk.CTkFrame, text: str, command, **kwargs) -> ctk.CTkButton:
        options = {
            "height": 36,
            "corner_radius": 8,
            "fg_color": "transparent",
            "hover_color": ("#f1f5f9", "#334155"),
            "border_width": 1,
            "border_color": ("#cbd5e1", "#475569"),
            "text_color": ("#334155", "#e2e8f0"),
            "font": ctk.CTkFont(size=13),
        }
        options.update(kwargs)
        return ctk.CTkButton(parent, text=text, command=command, **options)

    @staticmethod
    def _mono_tree_font(*, size: int = 12) -> tuple[str, int]:
        """ttk tag font 仅支持 (family, size)，不能写多字体回退。"""
        import tkinter.font as tkfont

        for family in ("Cascadia Mono", "Consolas", "Courier New"):
            if family.lower() in {name.lower() for name in tkfont.families()}:
                return (family, size)
        return ("Courier New", size)

    @staticmethod
    def _init_tree_row_tags(tree: ttk.Treeview, *, mono_size: int = 12) -> None:
        tree.tag_configure("odd", background="#1e293b", foreground="#e2e8f0")
        tree.tag_configure("even", background="#273549", foreground="#f1f5f9")
        tree.tag_configure("mono", font=ModelGuiApp._mono_tree_font(size=mono_size))
        tree.tag_configure("warn", background="#422006", foreground="#fde68a")
        tree.tag_configure("err", background="#450a0a", foreground="#fecaca")

    @staticmethod
    def _populate_tree_rows(tree: ttk.Treeview, rows: list[tuple], *, tag_fn=None) -> None:
        ModelGuiApp.clear_tree(tree)
        for index, values in enumerate(rows):
            if tag_fn is not None:
                tags = tag_fn(index, values)
            else:
                tags = ("odd",) if index % 2 == 0 else ("even",)
            tree.insert("", "end", values=values, tags=tags)

    def _build_treeview(
        self,
        parent: ctk.CTkFrame,
        columns: tuple[str, ...],
        headings: dict[str, str],
        widths: list[int],
        *,
        stretch_cols: frozenset[str] | None = None,
        style_variant: str = "default",
    ) -> ttk.Treeview:
        stretch = stretch_cols or frozenset({"path", "error"})
        if style_variant == "large":
            tree_style = "Premium.Large.Treeview"
            mono_size = 14
        else:
            tree_style = "Premium.Treeview"
            mono_size = 12
        wrap = ctk.CTkFrame(parent, fg_color="transparent")
        wrap.pack(fill="both", expand=True, padx=14, pady=(0, 14))
        wrap.grid_columnconfigure(0, weight=1)
        wrap.grid_rowconfigure(0, weight=1)
        tree = ttk.Treeview(wrap, columns=columns, show="headings", style=tree_style)
        for col, width in zip(columns, widths):
            tree.heading(col, text=headings.get(col, col))
            tree.column(
                col,
                width=width,
                minwidth=max(72, width // 2),
                anchor="w",
                stretch=col in stretch,
            )
        tree.grid(row=0, column=0, sticky="nsew", padx=(2, 0), pady=2)
        y_scroll = ttk.Scrollbar(
            wrap, orient="vertical", command=tree.yview, style="Premium.Vertical.TScrollbar"
        )
        x_scroll = ttk.Scrollbar(
            wrap, orient="horizontal", command=tree.xview, style="Premium.Horizontal.TScrollbar"
        )
        tree.configure(yscrollcommand=y_scroll.set, xscrollcommand=x_scroll.set)
        y_scroll.grid(row=0, column=1, sticky="ns")
        x_scroll.grid(row=1, column=0, sticky="ew")
        self._init_tree_row_tags(tree, mono_size=mono_size)
        return tree

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

        nav_pages = [
            ("map", "模型映射"),
            ("quota", "额度查询"),
            ("replace", "模型替换"),
            ("usage", "模型功能"),
        ]
        for page_key, label in nav_pages:
            btn = ctk.CTkButton(nav, text=label, command=lambda p=page_key: self.show_page(p))
            btn.pack(fill="x", padx=14, pady=6)
            self._usage_nav_buttons[page_key] = btn

        cleanup_btn = ctk.CTkButton(
            nav,
            text="清理缓存",
            command=lambda: self.show_page("cleanup"),
            fg_color=("#b45309", "#92400e"),
            hover_color=("#d97706", "#b45309"),
        )
        cleanup_btn.pack(fill="x", padx=14, pady=(12, 6))
        self._usage_nav_buttons["cleanup"] = cleanup_btn

        logs_btn = ctk.CTkButton(
            nav,
            text="运行日志",
            command=lambda: self.show_page("logs"),
        )
        logs_btn.pack(fill="x", padx=14, pady=(0, 6))
        self._usage_nav_buttons["logs"] = logs_btn

        self.page_host = ctk.CTkFrame(self)
        self.page_host.grid(row=0, column=1, sticky="nsew", padx=(6, 10), pady=10)
        self.page_host.grid_columnconfigure(0, weight=1)
        self.page_host.grid_rowconfigure(0, weight=1)

        self.map_page = self._build_map_page(self.page_host)
        self.quota_page = self._build_quota_page(self.page_host)
        self.replace_page = self._build_replace_page(self.page_host)
        self.usage_page = self._build_usage_page(self.page_host)
        self.cleanup_page = self._build_cleanup_page(self.page_host)

        self.log_frame = ctk.CTkFrame(self)
        log_frame = self.log_frame
        log_frame.grid(row=1, column=0, columnspan=2, sticky="ew", padx=10, pady=(0, 10))
        log_frame.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(log_frame, text="运行日志").grid(row=0, column=0, sticky="w", padx=10, pady=(8, 0))
        self.log_box = ctk.CTkTextbox(
            log_frame,
            height=88,
            font=ctk.CTkFont(family="Microsoft YaHei UI", size=13),
        )
        self.log_box.grid(row=1, column=0, sticky="ew", padx=10, pady=(4, 10))

        self.logs_page = self._build_logs_page(self.page_host)
        self.log("GUI 启动完成。可直接开始操作。")

    def _build_map_page(self, parent: ctk.CTkFrame) -> ctk.CTkFrame:
        frame = ctk.CTkFrame(parent, fg_color="transparent")
        frame.grid(row=0, column=0, sticky="nsew")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(2, weight=1)

        header = self._ui_page_header(
            frame,
            title="模型映射",
            subtitle="",
            action_builder=lambda actions: self._ui_primary_button(
                actions, "刷新映射", self.refresh_map, width=108
            ).pack(side="right"),
        )
        header.grid(row=0, column=0, sticky="ew", padx=12, pady=(12, 8))

        path_card = self._ui_card(frame)
        path_card.grid(row=1, column=0, sticky="ew", padx=12, pady=(0, 8))
        path_card.grid_columnconfigure(1, weight=1)
        path_card.grid_columnconfigure(3, weight=1)
        self._ui_section_title(path_card, "配置文件")
        form = ctk.CTkFrame(path_card, fg_color="transparent")
        form.pack(fill="x", padx=4, pady=(0, 12))
        form.grid_columnconfigure(1, weight=1)
        form.grid_columnconfigure(3, weight=1)
        ctk.CTkLabel(form, text="application.yml", font=ctk.CTkFont(size=13)).grid(
            row=0, column=0, padx=(12, 8), pady=10, sticky="w"
        )
        ctk.CTkEntry(form, textvariable=self.app_yml, height=36, font=ctk.CTkFont(size=13)).grid(
            row=0, column=1, padx=(0, 16), pady=10, sticky="ew"
        )
        ctk.CTkLabel(form, text="application-local.yml", font=ctk.CTkFont(size=13)).grid(
            row=0, column=2, padx=(0, 8), pady=10, sticky="w"
        )
        ctk.CTkEntry(form, textvariable=self.local_yml, height=36, font=ctk.CTkFont(size=13)).grid(
            row=0, column=3, padx=(0, 12), pady=10, sticky="ew"
        )

        table_card = self._ui_card(frame)
        table_card.grid(row=2, column=0, sticky="nsew", padx=12, pady=(0, 12))
        table_card.grid_columnconfigure(0, weight=1)
        table_card.grid_rowconfigure(1, weight=1)
        title_row = ctk.CTkFrame(table_card, fg_color="transparent")
        title_row.grid(row=0, column=0, sticky="ew")
        self._ui_section_title(title_row, "映射一览")
        self.map_count_label = ctk.CTkLabel(
            title_row,
            text="0 项",
            font=ctk.CTkFont(size=12),
            text_color=("#64748b", "#94a3b8"),
        )
        self.map_count_label.pack(side="right", padx=16, pady=(14, 0))
        tree_host = ctk.CTkFrame(table_card, fg_color="transparent")
        tree_host.grid(row=1, column=0, sticky="nsew")
        tree_host.grid_columnconfigure(0, weight=1)
        tree_host.grid_rowconfigure(0, weight=1)
        self.map_tree = self._build_treeview(
            tree_host,
            MAP_TREE_COLUMNS,
            MAP_TREE_HEADINGS,
            [200, 300, 150, 420],
            stretch_cols=frozenset({"path"}),
        )
        return frame

    def _build_quota_page(self, parent: ctk.CTkFrame) -> ctk.CTkFrame:
        frame = ctk.CTkFrame(parent)
        frame.grid(row=0, column=0, sticky="nsew")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(2, weight=1)

        mode_bar = ctk.CTkFrame(frame)
        mode_bar.grid(row=0, column=0, sticky="ew", padx=10, pady=(10, 4))
        mode_bar.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(mode_bar, text="查询方式").grid(row=0, column=0, padx=6, pady=8)
        self.quota_mode_menu = ctk.CTkOptionMenu(
            mode_bar,
            variable=self.quota_mode,
            values=list(QUOTA_MODE_LABELS.values()),
            command=self._on_quota_mode_change,
        )
        self.quota_mode_menu.grid(row=0, column=1, padx=6, pady=8, sticky="w")
        self.quota_mode_display = ctk.CTkLabel(mode_bar, text=QUOTA_MODE_LABELS["api_key"])
        self.quota_mode_display.grid(row=0, column=2, padx=6, pady=8, sticky="w")

        self.quota_status_label = ctk.CTkLabel(
            mode_bar,
            text="",
            text_color=("#d97706", "#fbbf24"),
            anchor="w",
        )
        self.quota_status_label.grid(row=1, column=0, columnspan=4, sticky="ew", padx=8, pady=(0, 4))

        action_column = ctk.CTkFrame(mode_bar, fg_color="transparent")
        action_column.grid(row=0, column=3, padx=6, pady=4, sticky="e")
        self.quota_execute_btn = ctk.CTkButton(
            action_column, text="执行查询", command=self.refresh_quota, width=96
        )
        self.quota_execute_btn.grid(row=0, column=0, padx=(0, 4))
        self.quota_locate_btn = ctk.CTkButton(
            action_column,
            text="定位搜索",
            command=self.refresh_quota_locate,
            width=96,
            fg_color=("#2563eb", "#1d4ed8"),
            hover_color=("#1d4ed8", "#1e40af"),
        )
        self.quota_locate_btn.grid(row=0, column=1, padx=(0, 8))
        action_aux = ctk.CTkFrame(action_column, fg_color="transparent")
        action_aux.grid(row=0, column=2, sticky="e")
        ctk.CTkButton(action_aux, text="复制JSON", command=self.copy_quota_json, width=88).pack(
            side="left", padx=4
        )
        ctk.CTkButton(action_aux, text="可视化", command=self.show_quota_pie_chart, width=72).pack(
            side="left", padx=4
        )

        self._update_quota_browser_actions()
        self._update_quota_primary_action()

        config_row = ctk.CTkFrame(frame, fg_color="transparent")
        config_row.grid(row=1, column=0, sticky="nsew", padx=10, pady=4)
        config_row.grid_columnconfigure(0, weight=1)

        self.quota_input_host = ctk.CTkFrame(config_row, fg_color="transparent")
        self.quota_input_host.grid(row=0, column=0, sticky="ew")
        self.quota_input_host.grid_columnconfigure(1, weight=1)

        self.use_playwright.trace_add("write", self._on_quota_capture_option_change)
        self.use_firecrawl_browser.trace_add("write", self._on_quota_capture_option_change)

        self._rebuild_quota_input_panel()
        self._update_quota_config_button_visibility()

        table_card = self._ui_card(frame)
        table_card.grid(row=2, column=0, sticky="nsew", padx=12, pady=(8, 12))
        table_card.grid_columnconfigure(0, weight=1)
        table_card.grid_rowconfigure(1, weight=1)
        quota_title_row = ctk.CTkFrame(table_card, fg_color="transparent")
        quota_title_row.grid(row=0, column=0, sticky="ew")
        self._ui_section_title(quota_title_row, "额度结果", font_size=16)
        tree_host = ctk.CTkFrame(table_card, fg_color="transparent")
        tree_host.grid(row=1, column=0, sticky="nsew")
        tree_host.grid_columnconfigure(0, weight=1)
        tree_host.grid_rowconfigure(0, weight=1)
        self.quota_tree = self._build_treeview(
            tree_host,
            QUOTA_TREE_COLUMNS,
            QUOTA_TREE_HEADINGS,
            [200, 280, 110, 220, 100, 360],
            stretch_cols=frozenset({"error"}),
            style_variant="large",
        )
        return frame

    def _build_replace_page(self, parent: ctk.CTkFrame) -> ctk.CTkFrame:
        frame = ctk.CTkFrame(parent)
        frame.grid(row=0, column=0, sticky="nsew")
        frame.grid_columnconfigure(1, weight=1)
        frame.grid_rowconfigure(1, weight=1)
        frame.grid_rowconfigure(2, weight=0, minsize=58)

        toolbar = ctk.CTkFrame(frame, fg_color="transparent")
        toolbar.grid(row=0, column=0, columnspan=2, sticky="ew", padx=10, pady=(10, 6))
        ctk.CTkLabel(
            toolbar,
            text="模型替换",
            font=ctk.CTkFont(size=18, weight="bold"),
            anchor="w",
        ).pack(side="left", padx=(4, 12))
        ctk.CTkButton(toolbar, text="刷新映射", width=96, command=self.refresh_replace_page).pack(
            side="left", padx=4
        )

        left_panel = ctk.CTkFrame(frame)
        left_panel.grid(row=1, column=0, sticky="nsew", padx=(10, 6), pady=(0, 6))
        left_panel.grid_rowconfigure(1, weight=1)
        left_panel.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(
            left_panel,
            text="配置位",
            font=ctk.CTkFont(size=14, weight="bold"),
        ).grid(row=0, column=0, sticky="w", padx=12, pady=(12, 6))
        self.replace_list_frame = ctk.CTkScrollableFrame(left_panel, width=248)
        self.replace_list_frame.grid(row=1, column=0, sticky="nsew", padx=8, pady=(0, 8))

        right_panel = ctk.CTkFrame(frame, fg_color="transparent")
        right_panel.grid(row=1, column=1, sticky="nsew", padx=(6, 10), pady=(0, 6))
        right_panel.grid_columnconfigure(0, weight=1)
        right_panel.grid_rowconfigure(0, weight=1)

        panel_fg = self._replace_panel_fg()
        paned_bg = self._replace_paned_bg()

        paned_host = ctk.CTkFrame(right_panel, fg_color="transparent")
        paned_host.grid(row=0, column=0, sticky="nsew")
        paned_host.grid_columnconfigure(0, weight=1)
        paned_host.grid_rowconfigure(0, weight=1)

        self._replace_paned = tk.PanedWindow(
            paned_host,
            orient=tk.VERTICAL,
            sashwidth=6,
            sashrelief=tk.RAISED,
            opaqueresize=False,
            showhandle=False,
            bg=paned_bg,
            bd=0,
        )
        self._replace_paned.grid(row=0, column=0, sticky="nsew")

        self.replace_detail_panel = ctk.CTkFrame(self._replace_paned, fg_color=panel_fg)
        self._replace_paned.add(self.replace_detail_panel, minsize=180, stretch="always")
        self.replace_detail_panel.grid_columnconfigure(0, weight=1)
        self.replace_detail_panel.grid_rowconfigure(1, weight=1)

        self.replace_info_frame = ctk.CTkFrame(self.replace_detail_panel)
        self.replace_info_frame.grid(row=0, column=0, sticky="ew", padx=4, pady=(4, 6))
        self.replace_info_frame.grid_columnconfigure(1, weight=1)

        ctk.CTkLabel(
            self.replace_info_frame,
            text="配置名",
            font=ctk.CTkFont(size=12, weight="bold"),
            anchor="w",
        ).grid(row=0, column=0, sticky="nw", padx=(12, 8), pady=(12, 4))
        self.replace_fn_label = ctk.CTkLabel(
            self.replace_info_frame,
            text="—",
            font=ctk.CTkFont(size=14, weight="bold"),
            anchor="w",
        )
        self.replace_fn_label.grid(row=0, column=1, sticky="ew", padx=(0, 12), pady=(12, 4))

        ctk.CTkLabel(
            self.replace_info_frame,
            text="当前模型",
            font=ctk.CTkFont(size=12, weight="bold"),
            anchor="w",
        ).grid(row=1, column=0, sticky="nw", padx=(12, 8), pady=4)
        self.replace_model_label = ctk.CTkLabel(
            self.replace_info_frame,
            text="—",
            font=ctk.CTkFont(size=15, weight="bold"),
            text_color=("#2563eb", "#60a5fa"),
            anchor="w",
        )
        self.replace_model_label.grid(row=1, column=1, sticky="ew", padx=(0, 12), pady=4)

        self.replace_meta_label = ctk.CTkLabel(
            self.replace_info_frame,
            text="",
            text_color=("#64748b", "#94a3b8"),
            anchor="w",
            justify="left",
            wraplength=480,
        )
        self.replace_meta_label.grid(row=2, column=0, columnspan=2, sticky="ew", padx=12, pady=(0, 12))

        self.replace_usage_wrap = ctk.CTkFrame(self.replace_detail_panel, fg_color="transparent")
        self.replace_usage_wrap.grid(row=1, column=0, sticky="nsew", padx=10, pady=(0, 8))
        self.replace_usage_wrap.grid_columnconfigure(0, weight=1)
        self.replace_usage_wrap.grid_rowconfigure(0, weight=1)
        self.replace_usage_box = ctk.CTkTextbox(
            self.replace_usage_wrap,
            font=ctk.CTkFont(size=13),
        )
        self.replace_usage_box.grid(row=0, column=0, sticky="nsew")
        self.replace_usage_box.configure(state="disabled")

        ops_row = ctk.CTkFrame(self._replace_paned, fg_color=panel_fg)
        self._replace_paned.add(ops_row, minsize=140, stretch="always")
        ops_row.grid_columnconfigure(0, weight=1)
        ops_row.grid_columnconfigure(1, weight=1)
        ops_row.grid_rowconfigure(1, weight=1)

        input_row = ctk.CTkFrame(ops_row, fg_color="transparent")
        input_row.grid(row=0, column=0, columnspan=2, sticky="ew", padx=8, pady=(8, 4))
        input_row.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(input_row, text="新模型名", anchor="w").grid(row=0, column=0, padx=(0, 8), pady=4)
        self.new_model_entry = ctk.CTkEntry(input_row, placeholder_text="例如 qwen3.6-plus")
        self.new_model_entry.grid(row=0, column=1, sticky="ew", pady=4)
        ctk.CTkButton(input_row, text="添加替换项", width=110, command=self.add_replace_item).grid(
            row=0, column=2, padx=(8, 0), pady=4
        )

        lists_row = ctk.CTkFrame(ops_row, fg_color="transparent")
        lists_row.grid(row=1, column=0, columnspan=2, sticky="nsew", padx=4, pady=(0, 4))
        lists_row.grid_columnconfigure(0, weight=1)
        lists_row.grid_columnconfigure(1, weight=1)
        lists_row.grid_rowconfigure(0, weight=1)

        scroll_kw = {
            "fg_color": panel_fg,
            "scrollbar_fg_color": panel_fg,
            "scrollbar_button_color": ("#cbd5e1", "#334155"),
            "scrollbar_button_hover_color": ("#94a3b8", "#475569"),
        }

        queue_section = ctk.CTkFrame(lists_row, fg_color="transparent")
        queue_section.grid(row=0, column=0, sticky="nsew", padx=(0, 4), pady=0)
        queue_section.grid_columnconfigure(0, weight=1)
        queue_section.grid_rowconfigure(1, weight=1)
        ctk.CTkLabel(
            queue_section,
            text="待写入",
            font=ctk.CTkFont(size=12, weight="bold"),
            anchor="w",
        ).grid(row=0, column=0, sticky="w", padx=4, pady=(0, 2))
        self.replace_queue_scroll = ctk.CTkScrollableFrame(queue_section, **scroll_kw)
        self.replace_queue_scroll.grid(row=1, column=0, sticky="nsew", padx=2, pady=(0, 2))
        self.replace_queue_frame = ctk.CTkFrame(self.replace_queue_scroll, fg_color="transparent")
        self.replace_queue_frame.pack(fill="x")

        preview_section = ctk.CTkFrame(lists_row, fg_color="transparent")
        preview_section.grid(row=0, column=1, sticky="nsew", padx=(4, 0), pady=0)
        preview_section.grid_columnconfigure(0, weight=1)
        preview_section.grid_rowconfigure(1, weight=1)
        ctk.CTkLabel(
            preview_section,
            text="预览结果",
            font=ctk.CTkFont(size=12, weight="bold"),
            anchor="w",
        ).grid(row=0, column=0, sticky="w", padx=4, pady=(0, 2))
        self.replace_preview_scroll = ctk.CTkScrollableFrame(preview_section, **scroll_kw)
        self.replace_preview_scroll.grid(row=1, column=0, sticky="nsew", padx=2, pady=(0, 2))
        self.replace_preview_frame = ctk.CTkFrame(self.replace_preview_scroll, fg_color="transparent")
        self.replace_preview_frame.pack(fill="x")

        self._replace_paned_sash_placed = False
        paned_host.bind("<Configure>", self._on_replace_paned_configure)

        footer = ctk.CTkFrame(frame, border_width=1, border_color=("#cbd5e1", "#334155"))
        footer.grid(row=2, column=0, columnspan=2, sticky="ew", padx=10, pady=(0, 10))
        footer.grid_columnconfigure(4, weight=1)

        ctk.CTkButton(footer, text="清空", width=88, height=36, command=self.clear_replace_items).grid(
            row=0, column=0, padx=(12, 8), pady=10
        )
        ctk.CTkButton(footer, text="预览", width=88, height=36, command=self.preview_replace).grid(
            row=0, column=1, padx=(0, 8), pady=10
        )
        ctk.CTkButton(
            footer,
            text="确认写入",
            width=108,
            height=36,
            fg_color=("#2563eb", "#1d4ed8"),
            hover_color=("#1d4ed8", "#1e40af"),
            command=self.apply_replace,
        ).grid(row=0, column=2, padx=(0, 12), pady=10)

        self._hide_replace_detail()
        return frame

    def _build_usage_page(self, parent: ctk.CTkFrame) -> ctk.CTkFrame:
        frame = ctk.CTkFrame(parent)
        frame.grid(row=0, column=0, sticky="nsew")
        frame.grid_columnconfigure(0, weight=0, minsize=268)
        frame.grid_columnconfigure(1, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        toolbar = ctk.CTkFrame(frame)
        toolbar.grid(row=0, column=0, columnspan=2, sticky="ew", padx=10, pady=10)
        for i in range(5):
            toolbar.grid_columnconfigure(i, weight=0)
        toolbar.grid_columnconfigure(1, weight=1)
        toolbar.grid_columnconfigure(3, weight=1)

        ctk.CTkLabel(toolbar, text="application.yml").grid(row=0, column=0, padx=6, pady=8)
        ctk.CTkEntry(toolbar, textvariable=self.app_yml).grid(row=0, column=1, padx=6, pady=8, sticky="ew")
        ctk.CTkLabel(toolbar, text="application-local.yml").grid(row=0, column=2, padx=6, pady=8)
        ctk.CTkEntry(toolbar, textvariable=self.local_yml).grid(row=0, column=3, padx=6, pady=8, sticky="ew")
        ctk.CTkButton(toolbar, text="刷新模型列表", command=self.refresh_usage).grid(
            row=0, column=4, padx=6, pady=8
        )

        left_panel = ctk.CTkFrame(frame)
        left_panel.grid(row=1, column=0, sticky="nsew", padx=(10, 6), pady=(0, 10))
        left_panel.grid_rowconfigure(1, weight=1)
        left_panel.grid_columnconfigure(0, weight=1)

        ctk.CTkLabel(
            left_panel,
            text="配置位",
            font=ctk.CTkFont(size=14, weight="bold"),
        ).grid(row=0, column=0, sticky="w", padx=12, pady=(12, 6))

        self.usage_list_frame = ctk.CTkScrollableFrame(left_panel, width=248)
        self.usage_list_frame.grid(row=1, column=0, sticky="nsew", padx=8, pady=(0, 8))

        right_panel = ctk.CTkFrame(frame)
        right_panel.grid(row=1, column=1, sticky="nsew", padx=(6, 10), pady=(0, 10))
        right_panel.grid_columnconfigure(0, weight=1)
        right_panel.grid_rowconfigure(3, weight=1)

        self.usage_title_label = ctk.CTkLabel(
            right_panel,
            text="请选择左侧配置位",
            font=ctk.CTkFont(size=18, weight="bold"),
            anchor="w",
        )
        self.usage_title_label.grid(row=0, column=0, sticky="ew", padx=14, pady=(14, 4))

        self.usage_hint_label = ctk.CTkLabel(
            right_panel,
            text="",
            text_color=("#64748b", "#94a3b8"),
            anchor="w",
        )
        self.usage_hint_label.grid(row=1, column=0, sticky="ew", padx=14, pady=(0, 6))

        assoc_wrap = ctk.CTkFrame(right_panel, fg_color="transparent")
        assoc_wrap.grid(row=2, column=0, sticky="ew", padx=14, pady=(0, 8))
        assoc_wrap.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(
            assoc_wrap,
            text="当前模型",
            font=ctk.CTkFont(size=12, weight="bold"),
            anchor="w",
        ).grid(row=0, column=0, sticky="w", pady=(0, 4))
        self.usage_model_frame = ctk.CTkFrame(assoc_wrap)
        self.usage_model_frame.grid(row=1, column=0, sticky="ew")

        editor_wrap = ctk.CTkFrame(right_panel, fg_color="transparent")
        editor_wrap.grid(row=3, column=0, sticky="nsew", padx=14, pady=(0, 8))
        editor_wrap.grid_columnconfigure(0, weight=1)
        editor_wrap.grid_rowconfigure(1, weight=1)
        ctk.CTkLabel(
            editor_wrap,
            text="功能说明",
            font=ctk.CTkFont(size=12, weight="bold"),
            anchor="w",
        ).grid(row=0, column=0, sticky="w", pady=(0, 6))
        self.usage_desc_box = ctk.CTkTextbox(editor_wrap, font=ctk.CTkFont(size=13))
        self.usage_desc_box.grid(row=1, column=0, sticky="nsew")
        self.usage_desc_box.bind("<KeyRelease>", self._on_usage_desc_change)

        action_bar = ctk.CTkFrame(right_panel, fg_color="transparent")
        action_bar.grid(row=4, column=0, sticky="ew", padx=14, pady=(0, 12))
        action_bar.grid_columnconfigure(2, weight=1)
        ctk.CTkButton(action_bar, text="保存当前", width=100, command=self.save_usage_current).grid(
            row=0, column=0, padx=(0, 8)
        )
        ctk.CTkButton(action_bar, text="保存全部", width=100, command=self.save_usage_all).grid(
            row=0, column=1, padx=(0, 8)
        )
        self.usage_status_label = ctk.CTkLabel(
            action_bar,
            text="",
            text_color=("#64748b", "#94a3b8"),
            anchor="e",
        )
        self.usage_status_label.grid(row=0, column=2, sticky="e")

        return frame

    def _build_cleanup_page(self, parent: ctk.CTkFrame) -> ctk.CTkFrame:
        frame = ctk.CTkFrame(parent)
        frame.grid(row=0, column=0, sticky="nsew")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(2, weight=1)

        header = ctk.CTkFrame(frame, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", padx=14, pady=(14, 8))
        header.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(
            header,
            text="清理缓存",
            font=ctk.CTkFont(size=20, weight="bold"),
            anchor="w",
        ).grid(row=0, column=0, sticky="w")

        protected = ctk.CTkFrame(frame)
        protected.grid(row=1, column=0, sticky="ew", padx=14, pady=(0, 8))
        protected.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(
            protected,
            text="始终保留",
            font=ctk.CTkFont(size=12, weight="bold"),
            anchor="w",
        ).grid(row=0, column=0, sticky="w", padx=12, pady=(10, 4))
        prot_row = ctk.CTkFrame(protected, fg_color="transparent")
        prot_row.grid(row=1, column=0, sticky="ew", padx=8, pady=(0, 10))
        for _pid, path, desc in PROTECTED_ITEMS:
            ctk.CTkLabel(
                prot_row,
                text=desc,
                fg_color=("#ecfdf5", "#14532d"),
                text_color=("#065f46", "#bbf7d0"),
                corner_radius=6,
                padx=10,
                pady=6,
            ).pack(side="left", padx=(0, 8), pady=4)

        body = ctk.CTkFrame(frame)
        body.grid(row=2, column=0, sticky="nsew", padx=14, pady=(0, 8))
        body.grid_columnconfigure(0, weight=1)
        body.grid_rowconfigure(1, weight=1)
        self._cleanup_body = body
        self._cleanup_list_height = 0

        action_bar = ctk.CTkFrame(body, fg_color="transparent")
        action_bar.grid(row=0, column=0, sticky="ew", pady=(10, 8))
        self._cleanup_action_bar = action_bar
        action_bar.grid_columnconfigure(4, weight=1)
        ctk.CTkButton(action_bar, text="重新扫描", width=96, command=self.refresh_cleanup_scan).grid(
            row=0, column=0, padx=(0, 8)
        )
        ctk.CTkButton(action_bar, text="全选", width=72, command=self._cleanup_select_all).grid(
            row=0, column=1, padx=(0, 8)
        )
        ctk.CTkButton(action_bar, text="全不选", width=72, command=self._cleanup_select_none).grid(
            row=0, column=2, padx=(0, 8)
        )
        ctk.CTkButton(
            action_bar,
            text="清理选中项",
            width=120,
            fg_color=("#dc2626", "#b91c1c"),
            hover_color=("#ef4444", "#dc2626"),
            command=self.execute_cleanup,
        ).grid(row=0, column=3, padx=(0, 8))
        self.cleanup_summary_label = ctk.CTkLabel(
            action_bar,
            text="",
            anchor="e",
            text_color=("#64748b", "#94a3b8"),
        )
        self.cleanup_summary_label.grid(row=0, column=4, sticky="e")

        self.cleanup_list_frame = ctk.CTkScrollableFrame(body)
        self.cleanup_list_frame.grid(row=1, column=0, sticky="nsew", padx=4, pady=(0, 10))

        self.cleanup_empty_label = ctk.CTkLabel(
            self.cleanup_list_frame,
            text="",
            text_color=("#64748b", "#94a3b8"),
        )

        body.bind("<Configure>", self._on_cleanup_body_configure, add="+")

        return frame

    def _build_logs_page(self, parent: ctk.CTkFrame) -> ctk.CTkFrame:
        frame = ctk.CTkFrame(parent, fg_color="transparent")
        frame.grid(row=0, column=0, sticky="nsew")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        def _logs_header_actions(actions: ctk.CTkFrame) -> None:
            self._ui_secondary_button(actions, "复制全部", self.copy_logs, width=96).pack(side="right")
            self._ui_secondary_button(actions, "清空", self.clear_logs, width=72).pack(
                side="right", padx=(8, 0)
            )

        header = self._ui_page_header(
            frame,
            title="运行日志",
            subtitle="查看 GUI 操作与任务执行的完整输出。",
            action_builder=_logs_header_actions,
        )
        header.grid(row=0, column=0, sticky="ew", padx=12, pady=(12, 8))

        log_card = self._ui_card(frame)
        log_card.grid(row=1, column=0, sticky="nsew", padx=12, pady=(0, 12))
        log_card.grid_columnconfigure(0, weight=1)
        log_card.grid_rowconfigure(0, weight=1)

        self.log_full_box = ctk.CTkTextbox(
            log_card,
            font=ctk.CTkFont(family="Consolas", size=13),
            wrap="none",
        )
        self.log_full_box.grid(row=0, column=0, sticky="nsew", padx=12, pady=12)

        return frame

    def show_page(self, page: str) -> None:
        for p in (
            self.map_page,
            self.quota_page,
            self.replace_page,
            self.usage_page,
            self.cleanup_page,
            self.logs_page,
        ):
            p.grid_remove()
        self._highlight_nav_button(page)
        if hasattr(self, "log_frame"):
            if page == "logs":
                self.log_frame.grid_remove()
            else:
                self.log_frame.grid()
        if page == "map":
            self.map_page.grid()
        elif page == "quota":
            self.quota_page.grid()
            self._on_quota_mode_change()
            if not self.quota_tree.get_children() and self.latest_quota_rows:
                self._render_quota_rows(self.latest_quota_rows)
        elif page == "usage":
            self.usage_page.grid()
            self.refresh_usage(silent=True)
        elif page == "cleanup":
            self.cleanup_page.grid()
            self.refresh_cleanup_scan()
            self.after_idle(self._sync_cleanup_list_height)
        elif page == "replace":
            self.replace_page.grid()
            self.refresh_replace_page()
            self.after_idle(self._on_replace_paned_configure)
        elif page == "logs":
            self.logs_page.grid()
            self._sync_log_views()

    def _highlight_nav_button(self, page: str) -> None:
        cleanup_fg = ("#b45309", "#92400e")
        cleanup_hover = ("#d97706", "#b45309")
        for key, btn in self._usage_nav_buttons.items():
            if key == page:
                btn.configure(fg_color=("#2563eb", "#1d4ed8"), hover_color=("#1d4ed8", "#1e40af"))
            elif key == "cleanup":
                btn.configure(fg_color=cleanup_fg, hover_color=cleanup_hover)
            else:
                btn.configure(fg_color=ctk.ThemeManager.theme["CTkButton"]["fg_color"])
                btn.configure(hover_color=ctk.ThemeManager.theme["CTkButton"]["hover_color"])

    def _log_textboxes(self) -> list[ctk.CTkTextbox]:
        boxes: list[ctk.CTkTextbox] = []
        for attr in ("log_box", "log_full_box"):
            box = getattr(self, attr, None)
            if box is not None:
                boxes.append(box)
        return boxes

    def _sync_log_views(self) -> None:
        text = "\n".join(self._log_lines)
        if text:
            text += "\n"
        for box in self._log_textboxes():
            box.configure(state="normal")
            box.delete("1.0", "end")
            if text:
                box.insert("1.0", text)
            box.see("end")
            box.configure(state="disabled")

    def log(self, msg: str) -> None:
        self._log_lines.append(msg)
        for box in self._log_textboxes():
            box.configure(state="normal")
            box.insert("end", f"{msg}\n")
            box.see("end")
            box.configure(state="disabled")

    def clear_logs(self) -> None:
        self._log_lines.clear()
        self._sync_log_views()
        self.log("日志已清空。")

    def copy_logs(self) -> None:
        text = "\n".join(self._log_lines)
        if not text.strip():
            messagebox.showinfo("复制日志", "当前没有可复制的日志。")
            return
        self.clipboard_clear()
        self.clipboard_append(text)
        self.log("日志已复制到剪贴板。")

    @staticmethod
    def clear_tree(tree: ttk.Treeview) -> None:
        for row_id in tree.get_children():
            tree.delete(row_id)

    def refresh_map(self) -> None:
        try:
            rows = core.list_models_data(self.app_yml.get(), self.local_yml.get())

            def _map_tag(index: int, values: tuple) -> tuple[str, ...]:
                base = ("odd",) if index % 2 == 0 else ("even",)
                return base + ("mono",)

            self._populate_tree_rows(
                self.map_tree,
                [
                    (row["function"], row["model_name"], row["source_file"], row["path"])
                    for row in rows
                ],
                tag_fn=_map_tag,
            )
            if hasattr(self, "map_count_label"):
                self.map_count_label.configure(text=f"{len(rows)} 项")
            self.log(f"模型映射加载成功，共 {len(rows)} 项。")
            self.refresh_replace_page(rows, silent=True)
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("映射读取失败", str(e))
            self.log(f"模型映射读取失败: {e}")

    def _quota_mode_key(self) -> str:
        label = self.quota_mode.get()
        return QUOTA_MODE_KEY_BY_LABEL.get(label, label)

    def _on_quota_mode_change(self, _value: str = "") -> None:
        self.quota_mode_display.configure(text=self.quota_mode.get())
        self._update_quota_browser_actions()
        self._update_quota_primary_action()
        self._rebuild_quota_input_panel()
        self._update_quota_config_button_visibility()

    def _on_quota_capture_option_change(self, *_args: object) -> None:
        if self.quota_guide_panel is not None:
            self.quota_guide_panel.refresh_status()

    def _open_quota_config_dialog(self) -> None:
        open_quota_config_dialog(self, self, log=self.log)

    def _update_quota_config_button_visibility(self) -> None:
        btn = self.quota_config_btn
        if btn is None:
            return
        if self._quota_mode_key() == "url_browser":
            btn.grid(row=3, column=2, padx=6, pady=4, sticky="w")
        else:
            btn.grid_remove()

    def _update_quota_browser_actions(self) -> None:
        if self.quota_locate_btn is None:
            return
        if self._quota_mode_key() == "url_browser":
            self.quota_locate_btn.grid(row=0, column=1, padx=(0, 8))
        else:
            self.quota_locate_btn.grid_remove()

    def _update_quota_primary_action(self) -> None:
        if self.quota_execute_btn is None:
            return
        if self._quota_mode_key() == "clipboard":
            self.quota_execute_btn.configure(text="执行查询", command=self._paste_and_query_clipboard)
        else:
            self.quota_execute_btn.configure(text="执行查询", command=self.refresh_quota)

    def _clear_quota_input_host(self) -> None:
        for child in self.quota_input_host.winfo_children():
            child.destroy()
        self._quota_mode_widgets = []
        self.quota_clipboard_box = None
        self.quota_config_btn = None

    def _rebuild_quota_input_panel(self) -> None:
        self._clear_quota_input_host()
        mode = self._quota_mode_key()
        host = self.quota_input_host

        if mode == "config":
            ctk.CTkLabel(host, text="配置来源").grid(row=0, column=0, padx=6, pady=8)
            ctk.CTkLabel(
                host,
                text=f"{QUOTA_ENV_FILE.name} · --- 下方 platforms",
                anchor="w",
                wraplength=520,
            ).grid(row=0, column=1, padx=6, pady=8, sticky="w")
            ctk.CTkLabel(host, text="platform(可选)").grid(row=1, column=0, padx=6, pady=8)
            ctk.CTkEntry(host, textvariable=self.platform_name, placeholder_text="如 dashscope").grid(
                row=1, column=1, padx=6, pady=8, sticky="ew"
            )
        elif mode == "api_key":
            ctk.CTkLabel(host, text="平台").grid(row=0, column=0, padx=6, pady=8)
            ctk.CTkOptionMenu(
                host,
                variable=self.api_key_platform,
                values=["dashscope", "custom"],
            ).grid(row=0, column=1, padx=6, pady=8, sticky="w")
            ctk.CTkLabel(host, text="API Key").grid(row=1, column=0, padx=6, pady=8)
            ctk.CTkEntry(host, textvariable=self.query_api_key, show="*", placeholder_text="sk-...").grid(
                row=1, column=1, padx=6, pady=8, sticky="ew"
            )
            ctk.CTkLabel(host, text="查询 URL(可选)").grid(row=2, column=0, padx=6, pady=8)
            ctk.CTkEntry(
                host,
                textvariable=self.api_key_endpoint,
                placeholder_text=f"留空默认 {DASHSCOPE_CREDIT_URL}",
            ).grid(row=2, column=1, padx=6, pady=8, sticky="ew")
            ctk.CTkLabel(host, text="JSON路径(可选)").grid(row=3, column=0, padx=6, pady=8)
            ctk.CTkEntry(
                host,
                textvariable=self.api_key_json_path,
                placeholder_text=CREDIT_JSON_PATH,
            ).grid(row=3, column=1, padx=6, pady=8, sticky="ew")
            ctk.CTkCheckBox(
                host,
                text="留空时从 application-local.yml 读取 Key",
                variable=self.read_key_from_yml,
            ).grid(row=4, column=1, padx=6, pady=4, sticky="w")
            ctk.CTkLabel(host, text="custom 平台").grid(row=5, column=0, padx=6, pady=8)
            ctk.CTkLabel(
                host,
                text="规则见 env 文件 --- 下方 platforms",
                anchor="w",
            ).grid(row=5, column=1, padx=6, pady=8, sticky="w")
        elif mode == "url_browser":
            ctk.CTkLabel(host, text="查询 URL").grid(row=0, column=0, padx=6, pady=8)
            ctk.CTkEntry(host, textvariable=self.query_url).grid(row=0, column=1, padx=6, pady=8, sticky="ew")
            ctk.CTkLabel(host, text="Cookie 文件").grid(row=1, column=0, padx=6, pady=8)
            ctk.CTkEntry(host, textvariable=self.cookie_file).grid(row=1, column=1, padx=6, pady=8, sticky="ew")
            ctk.CTkButton(host, text="浏览", command=self._browse_cookie_file, width=72).grid(
                row=1, column=2, padx=6, pady=8
            )
            ctk.CTkCheckBox(
                host,
                text="Playwright 无头抓取，需 Cookie 或已登录会话",
                variable=self.use_playwright,
            ).grid(row=2, column=1, padx=6, pady=4, sticky="w")
            ctk.CTkCheckBox(
                host,
                text="使用 Firecrawl Browser",
                variable=self.use_firecrawl_browser,
            ).grid(row=3, column=1, padx=6, pady=4, sticky="w")
            self.quota_config_btn = ctk.CTkButton(
                host,
                text="配置与说明",
                width=110,
                command=self._open_quota_config_dialog,
            )
            self.quota_config_btn.grid(row=3, column=2, padx=6, pady=4, sticky="w")
            ctk.CTkLabel(host, text="会话目录").grid(row=4, column=0, padx=6, pady=8)
            ctk.CTkEntry(host, textvariable=self.browser_profile_dir).grid(
                row=4, column=1, padx=6, pady=8, sticky="ew"
            )
        elif mode == "local_file":
            ctk.CTkLabel(host, text="本地文件").grid(row=0, column=0, padx=6, pady=8)
            ctk.CTkEntry(host, textvariable=self.local_quota_file).grid(row=0, column=1, padx=6, pady=8, sticky="ew")
            ctk.CTkButton(host, text="浏览", command=self._browse_local_quota_file, width=72).grid(
                row=0, column=2, padx=6, pady=8
            )
        elif mode == "clipboard":
            ctk.CTkLabel(host, text="页面文本").grid(row=0, column=0, padx=6, pady=8, sticky="nw")
            self.quota_clipboard_box = ctk.CTkTextbox(host, height=100)
            self.quota_clipboard_box.grid(row=0, column=1, columnspan=2, padx=6, pady=8, sticky="ew")

        self._update_quota_config_button_visibility()

    def _browse_cookie_file(self) -> None:
        path = filedialog.askopenfilename(
            title="选择 Cookie 文件",
            filetypes=[("Cookie/JSON", "*.json *.txt"), ("All", "*.*")],
        )
        if path:
            self.cookie_file.set(path)

    def _browse_local_quota_file(self) -> None:
        path = filedialog.askopenfilename(
            title="选择本地额度文件",
            filetypes=[
                ("Supported", "*.json *.html *.htm *.md *.har *.txt"),
                ("All", "*.*"),
            ],
        )
        if path:
            self.local_quota_file.set(path)

    def _paste_clipboard_to_quota_box(self) -> bool:
        try:
            text = self.clipboard_get()
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("剪贴板读取失败", str(e))
            return False
        if self.quota_clipboard_box is not None:
            self.quota_clipboard_box.delete("1.0", "end")
            self.quota_clipboard_box.insert("1.0", text)
        self.log("已从剪贴板粘贴到文本框。")
        return True

    def _paste_and_query_clipboard(self) -> None:
        if self._quota_mode_key() != "clipboard":
            messagebox.showwarning("提示", "请先将查询方式设为「剪贴板/文本」。")
            return
        if not self._paste_clipboard_to_quota_box():
            return
        self._start_quota_query(PlaywrightSearchMode.FULL_PAGE)

    def _build_quota_request(
        self,
        *,
        playwright_search_mode: PlaywrightSearchMode = PlaywrightSearchMode.FULL_PAGE,
    ) -> QuotaQueryRequest:
        mode_key = self._quota_mode_key()
        mode = QuotaQueryMode(mode_key)
        page_text = ""
        if mode == QuotaQueryMode.CLIPBOARD and self.quota_clipboard_box is not None:
            page_text = self.quota_clipboard_box.get("1.0", "end").strip()

        config_path = resolve_config_yml_path()
        self.config_yml.set(config_path)

        return QuotaQueryRequest(
            mode=mode,
            app_yml=self.app_yml.get(),
            local_yml=self.local_yml.get(),
            timeout=20,
            config_path=config_path,
            platform_filter=self.platform_name.get().strip(),
            api_key=self.query_api_key.get().strip(),
            api_key_platform=self.api_key_platform.get().strip(),
            read_api_key_from_local_yml=self.read_key_from_yml.get(),
            custom_config_for_api=config_path,
            api_key_endpoint=self.api_key_endpoint.get().strip(),
            api_key_json_path=self.api_key_json_path.get().strip(),
            query_url=self.query_url.get().strip(),
            cookie_file=self.cookie_file.get().strip(),
            use_playwright=self.use_playwright.get(),
            use_firecrawl_browser=self.use_firecrawl_browser.get(),
            browser_profile_dir=self.browser_profile_dir.get().strip(),
            playwright_search_mode=playwright_search_mode,
            local_file_path=self.local_quota_file.get().strip(),
            page_text=page_text,
        )

    def _apply_gui_env_from_files(self) -> None:
        local_env, source = load_gui_env_local()
        self._gui_env_source = source
        if not QUOTA_ENV_FILE.exists():
            self.log(
                f"未找到 {QUOTA_ENV_FILE.name}，输入框使用程序默认。"
                f"请 copy {QUOTA_ENV_EXAMPLE.name} {QUOTA_ENV_FILE.name} 后填写并重启。"
            )
            return
        applied = apply_env_to_gui(self, local_env)
        key = firecrawl_api_key_from_env(local_env)
        if key:
            import os

            os.environ["FIRECRAWL_API_KEY"] = key
        panel = self.quota_guide_panel
        if panel is not None:
            try:
                if panel.winfo_exists():
                    panel.load_from_env()
                    panel.refresh_status()
            except Exception:
                self.quota_guide_panel = None
        if source:
            self.log(f"已从本地配置回显：{source}")
        if applied:
            self.log(f"已填入 {len(applied)} 项。")
        elif source:
            self.log(f"{source} 存在但未解析到有效键值，请检查格式。")

    @staticmethod
    def _quota_meta_from_request(request: QuotaQueryRequest) -> dict[str, str]:
        return {
            "query_url": request.query_url,
            "cookie_file": request.cookie_file,
            "use_playwright": str(request.use_playwright),
            "playwright_search_mode": request.playwright_search_mode.value,
            "use_firecrawl_browser": str(request.use_firecrawl_browser),
            "browser_profile_dir": request.browser_profile_dir,
            "config_path": request.config_path,
            "platform_filter": request.platform_filter,
            "api_key_platform": request.api_key_platform,
            "api_key_endpoint": request.api_key_endpoint,
            "api_key_json_path": request.api_key_json_path,
            "local_file_path": request.local_file_path,
        }

    def _render_quota_rows(self, rows: list[dict[str, str]]) -> None:
        def _quota_tag(_index: int, values: tuple) -> tuple[str, ...]:
            err = str(values[5] if len(values) > 5 else "").strip()
            quota = str(values[3] if len(values) > 3 else "")
            if err:
                return ("err", "mono")
            if quota.startswith("剩 0") or "未列出" in quota:
                return ("warn", "mono")
            base = ("odd",) if _index % 2 == 0 else ("even",)
            return base + ("mono",)

        self._populate_tree_rows(
            self.quota_tree,
            [
                (
                    row.get("function", ""),
                    row.get("model_name", ""),
                    row.get("platform", ""),
                    row.get("quota", ""),
                    row.get("method", ""),
                    row.get("error", ""),
                )
                for row in rows
            ],
            tag_fn=_quota_tag,
        )

    def _restore_quota_snapshot(self) -> None:
        data = load_quota_snapshot()
        if not data:
            return
        rows = data.get("rows", [])
        if not isinstance(rows, list) or not rows:
            return
        aligned = align_quota_rows_with_mappings(
            rows,
            core.list_models_data(self.app_yml.get(), self.local_yml.get()),
        )
        self.latest_quota_rows = aligned
        self._render_quota_rows(aligned)
        saved_at = data.get("saved_at", "")
        self.log(f"已恢复上次额度快照 {saved_at} → {snapshot_path_display()}")

    def _log_stale_page_text_hint(
        self,
        request: QuotaQueryRequest,
        rows: list[dict[str, str]],
    ) -> None:
        page_text = (request.page_text or "").strip()
        if not page_text and self.quota_clipboard_box is not None:
            page_text = self.quota_clipboard_box.get("1.0", "end").strip()
        if not page_text:
            return
        from quota.parsers.text_match import _model_listed_on_page

        for row in rows:
            yaml_name = str(row.get("model_name", "")).strip()
            err = str(row.get("error", "")).strip()
            if not yaml_name or not err:
                continue
            if _model_listed_on_page(page_text, yaml_name):
                continue
            if "glm-5" in page_text.lower() and "minimax" not in page_text.lower():
                self.log(
                    f"  提示：页面文本仍主要是 glm-5，未出现「{yaml_name}」。"
                    "请重新抓取控制台或粘贴最新页面后再查。"
                )
                break

    def _set_quota_busy_ui(self, busy: bool) -> None:
        state = "disabled" if busy else "normal"
        if self.quota_execute_btn is not None:
            self.quota_execute_btn.configure(state=state)
        if self.quota_locate_btn is not None:
            self.quota_locate_btn.configure(state=state)
        if self.quota_status_label is not None:
            self.quota_status_label.configure(text="查询中，请稍等…" if busy else "")
        self.update_idletasks()

    def _start_quota_query(self, playwright_search_mode: PlaywrightSearchMode) -> None:
        if self._quota_busy:
            return
        if playwright_search_mode == PlaywrightSearchMode.DOM_LOCATOR:
            if self._quota_mode_key() != "url_browser":
                messagebox.showwarning(
                    "定位搜索",
                    "定位搜索仅适用于「URL + 浏览器」查询方式。",
                )
                return
            if not self.use_playwright.get():
                messagebox.showwarning(
                    "定位搜索",
                    "请先勾选「Playwright 无头抓取」，并确保 Cookie 或会话目录已登录。",
                )
                return
        self._quota_busy = True
        self._set_quota_busy_ui(True)
        label = (
            "定位搜索"
            if playwright_search_mode == PlaywrightSearchMode.DOM_LOCATOR
            else "全文搜索"
        )
        self.log(f"{label}中，请稍等…")
        try:
            mappings = core.list_models_data(self.app_yml.get(), self.local_yml.get())
            self.log("本次按 application 配置匹配页面额度（非 model-usage.json 名称）：")
            for row in mappings:
                self.log(f"  {row['function']} → {row['model_name']}")
        except Exception as e:  # noqa: BLE001
            self.log(f"读取 YAML 映射失败，仍将尝试查询: {e}")
        request = self._build_quota_request(playwright_search_mode=playwright_search_mode)
        threading.Thread(target=self._quota_query_worker, args=(request,), daemon=True).start()

    def refresh_quota(self) -> None:
        self._start_quota_query(PlaywrightSearchMode.FULL_PAGE)

    def refresh_quota_locate(self) -> None:
        self._start_quota_query(PlaywrightSearchMode.DOM_LOCATOR)

    def _quota_query_worker(self, request: QuotaQueryRequest) -> None:
        try:
            result = run_quota_query(request)
            self.after(0, lambda: self._finish_quota_query(request, result, None))
        except Exception as e:  # noqa: BLE001
            self.after(0, lambda err=e: self._finish_quota_query(request, None, err))

    def _finish_quota_query(
        self,
        request: QuotaQueryRequest,
        result: object | None,
        error: Exception | None,
    ) -> None:
        try:
            if error is not None:
                messagebox.showerror("额度查询失败", str(error))
                self.log(f"额度查询失败: {error}")
                return
            if not isinstance(result, QuotaQueryResult):
                return
            rows = align_quota_rows_with_mappings(
                result.to_row_dicts(),
                core.list_models_data(self.app_yml.get(), self.local_yml.get()),
            )
            self.latest_quota_rows = rows
            self._render_quota_rows(rows)
            mode_note = request.playwright_search_mode.value
            if request.use_playwright and request.mode == QuotaQueryMode.URL_BROWSER:
                mode_note = (
                    "定位搜索"
                    if request.playwright_search_mode == PlaywrightSearchMode.DOM_LOCATOR
                    else "全文搜索"
                )
            self.log(f"额度查询完成（{self.quota_mode.get()} · {mode_note}），共 {len(rows)} 条。")
            for row in rows:
                fn = row.get("function", "")
                mn = row.get("model_name", "")
                quota = str(row.get("quota", "")).strip()
                err = str(row.get("error", "")).strip()
                if quota and not err:
                    self.log(f"  ✓ {fn}：检索「{mn}」→ {quota}")
                elif err:
                    self.log(f"  ✗ {fn}：检索「{mn}」→ {err}")
                else:
                    self.log(f"  · {fn}：检索「{mn}」→ 无结果")
            self._log_stale_page_text_hint(request, rows)
            for hint in result.log_hints:
                self.log(hint)
            path = save_quota_snapshot(
                rows,
                query_mode=request.mode.value,
                query_meta=self._quota_meta_from_request(request),
                log_hints=result.log_hints,
            )
            self.log(f"额度结果已持久化：{path}")
        finally:
            self._quota_busy = False
            self._set_quota_busy_ui(False)

    def parse_quota_from_clipboard(self) -> None:
        if self._quota_mode_key() != "clipboard":
            self.quota_mode.set(QUOTA_MODE_LABELS["clipboard"])
            self._on_quota_mode_change()
        self._paste_and_query_clipboard()

    def show_quota_pie_chart(self) -> None:
        if not self.latest_quota_rows:
            messagebox.showwarning("提示", "当前没有可视化数据，请先执行查询或解析。")
            return
        win = open_quota_visualization(self, self.latest_quota_rows)
        if win is None:
            messagebox.showwarning("提示", "未解析到可用额度数据，无法生成可视化。")

    def copy_quota_json(self) -> None:
        payload = json.dumps(self.latest_quota_rows, ensure_ascii=False, indent=2)
        self.clipboard_clear()
        self.clipboard_append(payload)
        self.log("额度结果 JSON 已复制到剪贴板。")

    def _on_replace_paned_configure(self, _event: tk.Event | None = None) -> None:
        if getattr(self, "_replace_paned_sash_placed", False):
            return
        paned = getattr(self, "_replace_paned", None)
        if paned is None:
            return
        paned.update_idletasks()
        height = paned.winfo_height()
        if height < 200:
            return
        paned.sash_place(0, 0, int(height * 0.66))
        self._replace_paned_sash_placed = True

    def _hide_replace_detail(self) -> None:
        self.replace_fn_label.configure(text="—")
        self.replace_model_label.configure(text="—")
        self.replace_meta_label.configure(text="请选择左侧配置位")
        self.replace_usage_wrap.grid_remove()

    def _show_replace_usage(self, description: str) -> None:
        self.replace_usage_wrap.grid(row=1, column=0, sticky="nsew", padx=10, pady=(0, 6))
        self.replace_usage_box.configure(state="normal")
        self.replace_usage_box.delete("1.0", "end")
        text = description.strip() if description.strip() else "暂无说明，可在「模型功能」页补充"
        self.replace_usage_box.insert("1.0", text)
        self.replace_usage_box.configure(state="disabled")

    def refresh_replace_page(
        self,
        rows: list[dict[str, str]] | None = None,
        *,
        silent: bool = False,
    ) -> None:
        try:
            if rows is None:
                rows = core.list_models_data(self.app_yml.get(), self.local_yml.get())
            saved = load_model_usage(migration_rows=rows)
            self._replace_map_rows = rows
            self._replace_context = build_function_replace_context(rows, saved)
            previous = self._replace_selected
            if previous and previous not in self._replace_context:
                previous = None
            self._rebuild_replace_list(select_name=previous)
            self._render_replace_queue()
            self._render_replace_preview()
            if not rows:
                self._hide_replace_detail()
            if not silent:
                self.log(f"模型替换映射已刷新，共 {len(rows)} 个配置位。")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("映射读取失败", str(e))
            self.log(f"模型替换映射读取失败: {e}")

    def _rebuild_replace_list(self, select_name: str | None = None) -> None:
        for child in self.replace_list_frame.winfo_children():
            child.destroy()
        self._replace_card_buttons.clear()

        if not self._replace_context:
            ctk.CTkLabel(
                self.replace_list_frame,
                text="暂无配置位，请检查 YAML",
                text_color=("#64748b", "#94a3b8"),
            ).pack(padx=8, pady=12)
            self._replace_selected = None
            self._hide_replace_detail()
            return

        target = select_name or self._replace_selected
        if target is None:
            target = sorted(self._replace_context.keys())[0]

        for function_key in sorted(self._replace_context.keys()):
            ctx = self._replace_context[function_key]
            model_name = ctx.get("model_name", "")
            is_selected = function_key == target
            btn = ctk.CTkButton(
                self.replace_list_frame,
                text=f"{function_key}\n{model_name}",
                anchor="center",
                height=52,
                fg_color=("#dbeafe", "#1e3a5f") if is_selected else ("#f1f5f9", "#1e293b"),
                text_color=("#0f172a", "#e2e8f0"),
                hover_color=("#bfdbfe", "#334155"),
                command=lambda fn=function_key: self._select_replace_function(fn),
            )
            btn.pack(fill="x", padx=4, pady=4)
            self._replace_card_buttons[function_key] = btn

        self._select_replace_function(target)

    def _select_replace_function(self, function_key: str) -> None:
        ctx = self._replace_context.get(function_key)
        if ctx is None:
            return
        self._replace_selected = function_key
        for name, btn in self._replace_card_buttons.items():
            selected = name == function_key
            btn.configure(
                fg_color=("#dbeafe", "#1e3a5f") if selected else ("#f1f5f9", "#1e293b"),
            )
        self.replace_fn_label.configure(text=function_key)
        self.replace_model_label.configure(text=ctx.get("model_name", "—") or "—")
        meta_parts = []
        if ctx.get("source_file"):
            meta_parts.append(ctx["source_file"])
        if ctx.get("path"):
            meta_parts.append(ctx["path"])
        self.replace_meta_label.configure(text=" · ".join(meta_parts) if meta_parts else " ")
        self._show_replace_usage(ctx.get("description", ""))

    def _render_replace_queue(self) -> None:
        compact_font = ctk.CTkFont(size=11)
        for child in self.replace_queue_frame.winfo_children():
            child.destroy()
        if not self.replace_rows:
            ctk.CTkLabel(
                self.replace_queue_frame,
                text="暂无待写入项",
                font=compact_font,
                text_color=("#64748b", "#94a3b8"),
            ).pack(anchor="w", padx=4, pady=4)
            return
        for item in self.replace_rows:
            fn = item["function"]
            ctx = self._replace_context.get(fn, {})
            from_model = ctx.get("model_name", "?")
            row = ctk.CTkFrame(self.replace_queue_frame)
            row.pack(fill="x", padx=2, pady=2)
            row.grid_columnconfigure(0, weight=1)
            ctk.CTkLabel(
                row,
                text=f"{fn} · {from_model} → {item['to']}",
                font=compact_font,
                anchor="w",
                justify="left",
                wraplength=200,
            ).grid(row=0, column=0, sticky="ew", padx=6, pady=4)
            ctk.CTkButton(
                row,
                text="移除",
                width=52,
                height=24,
                font=compact_font,
                fg_color=("#64748b", "#475569"),
                hover_color=("#94a3b8", "#64748b"),
                command=lambda f=fn: self._remove_replace_item(f),
            ).grid(row=0, column=1, padx=6, pady=4)

    def _render_replace_preview(self) -> None:
        compact_font = ctk.CTkFont(size=11)
        for child in self.replace_preview_frame.winfo_children():
            child.destroy()
        if not self._replace_preview_rows:
            ctk.CTkLabel(
                self.replace_preview_frame,
                text="执行预览后显示",
                font=compact_font,
                text_color=("#64748b", "#94a3b8"),
            ).pack(anchor="w", padx=4, pady=4)
            return
        for row in self._replace_preview_rows:
            card = ctk.CTkFrame(self.replace_preview_frame)
            card.pack(fill="x", padx=2, pady=2)
            ctk.CTkLabel(
                card,
                text=f"{row.get('function', '')} · {row.get('from', '')} → {row.get('to', '')}",
                font=compact_font,
                anchor="w",
                wraplength=200,
            ).pack(anchor="w", padx=6, pady=(4, 2))
            ctk.CTkLabel(
                card,
                text=str(row.get("target_file", "")),
                font=compact_font,
                text_color=("#64748b", "#94a3b8"),
                anchor="w",
                wraplength=200,
            ).pack(anchor="w", padx=6, pady=(0, 4))

    def _remove_replace_item(self, function_key: str) -> None:
        self.replace_rows = [x for x in self.replace_rows if x["function"] != function_key]
        self._render_replace_queue()
        self.log(f"已移除替换项: {function_key}")

    def add_replace_item(self) -> None:
        if not self._replace_selected:
            messagebox.showwarning("提示", "请先选择左侧配置位。")
            return
        function_key = self._replace_selected
        new_model = self.new_model_entry.get().strip()
        if not new_model:
            messagebox.showwarning("提示", "请输入新模型名。")
            return
        for item in self.replace_rows:
            if item["function"] == function_key:
                item["to"] = new_model
                self.log(f"替换项已更新: {function_key} -> {new_model}")
                self.new_model_entry.delete(0, "end")
                self._render_replace_queue()
                return
        self.replace_rows.append({"function": function_key, "to": new_model})
        self.new_model_entry.delete(0, "end")
        self.log(f"替换项已添加: {function_key} -> {new_model}，请点击「预览」后「确认写入」。")
        self._render_replace_queue()

    def clear_replace_items(self) -> None:
        self.replace_rows = []
        self._replace_preview_rows = []
        self._render_replace_queue()
        self._render_replace_preview()
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
            self._replace_preview_rows = changes
            self._render_replace_preview()
            self.log(f"预览完成，预计变更 {len(changes)} 项。")
            if not changes:
                messagebox.showinfo("提示", "无变更，目标模型与当前一致。")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("预览失败", str(e))
            self.log(f"预览失败: {e}")

    def _after_replace_write_sync(self, changes: list[dict[str, str]]) -> None:
        self.replace_rows = []
        self._replace_preview_rows = []
        self._render_replace_queue()
        self._render_replace_preview()
        try:
            self.refresh_map()
        except Exception as e:  # noqa: BLE001
            self.log(f"刷新映射表失败: {e}")
        self.refresh_replace_page(silent=True)
        try:
            usage_result = migrate_model_usage_for_replace(
                changes,
                self.app_yml.get(),
                self.local_yml.get(),
            )
            self.log(usage_result.message)
        except Exception as e:  # noqa: BLE001
            self.log(f"同步 model-usage.json 失败: {e}")
        self.refresh_usage(silent=True)
        prune_result = prune_quota_cache_for_replace_changes(
            changes,
            in_memory_rows=self.latest_quota_rows,
        )
        self.latest_quota_rows = align_quota_rows_with_mappings(
            prune_result.remaining_rows,
            core.list_models_data(self.app_yml.get(), self.local_yml.get()),
        )
        self.clear_tree(self.quota_tree)
        if self.latest_quota_rows:
            self._render_quota_rows(self.latest_quota_rows)
        self.log(prune_result.message)
        if prune_result.removed_count:
            self.log("写入后已同步各页数据；未改动模型的额度快照已保留。")
        else:
            self.log("写入后已同步各页数据。")

    def apply_replace(self) -> None:
        if not self.replace_rows:
            messagebox.showwarning("提示", "请先添加替换项。")
            return
        yes_text = simpledialog.askstring("二次确认", "将写入配置文件。请输入 YES 确认：")
        if yes_text != "YES":
            self.log("用户取消写入。")
            return
        try:
            changes = core.apply_replace_data(
                mapping=self._build_mapping_dict(),
                app_yml=self.app_yml.get(),
                local_yml=self.local_yml.get(),
            )
            self.log(f"写入完成，实际变更 {len(changes)} 项。")
            if changes:
                self._after_replace_write_sync(changes)
            messagebox.showinfo("完成", "模型配置已写入。")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("写入失败", str(e))
            self.log(f"写入失败: {e}")

    def _usage_entry_by_function(self, function_key: str) -> ModelUsageEntry | None:
        for entry in self._usage_catalog:
            if entry.function_key == function_key:
                return entry
        return None

    def _sync_usage_editor_to_catalog(self) -> None:
        if not self._usage_selected:
            return
        entry = self._usage_entry_by_function(self._usage_selected)
        if entry is None:
            return
        entry.description = self.usage_desc_box.get("1.0", "end").rstrip("\n")

    def _on_usage_desc_change(self, _event=None) -> None:
        if not self._usage_selected:
            return
        entry = self._usage_entry_by_function(self._usage_selected)
        if entry is None:
            return
        current = self.usage_desc_box.get("1.0", "end").rstrip("\n")
        saved = entry.description
        self._usage_dirty = current != saved
        self._update_usage_status_label()

    def _update_usage_status_label(self) -> None:
        if self._usage_dirty:
            self.usage_status_label.configure(text="有未保存的修改", text_color=("#d97706", "#fbbf24"))
        else:
            self.usage_status_label.configure(
                text=f"存档: {MODEL_USAGE_FILE.name}",
                text_color=("#64748b", "#94a3b8"),
            )

    def _clear_usage_model_info(self) -> None:
        for child in self.usage_model_frame.winfo_children():
            child.destroy()

    def _render_usage_model_info(self, entry: ModelUsageEntry) -> None:
        self._clear_usage_model_info()
        if not entry.model_name:
            ctk.CTkLabel(
                self.usage_model_frame,
                text="无 YAML 绑定",
                text_color=("#64748b", "#94a3b8"),
                anchor="w",
            ).pack(anchor="w", padx=4, pady=4)
            return
        ctk.CTkLabel(
            self.usage_model_frame,
            text=entry.model_name,
            font=ctk.CTkFont(size=14, weight="bold"),
            anchor="w",
        ).pack(anchor="w", padx=4, pady=(4, 2))
        meta_parts = [part for part in (entry.source_file, entry.path) if part]
        if meta_parts:
            ctk.CTkLabel(
                self.usage_model_frame,
                text=" · ".join(meta_parts),
                text_color=("#64748b", "#94a3b8"),
                anchor="w",
                wraplength=520,
                justify="left",
            ).pack(anchor="w", padx=4, pady=(0, 4))

    def _rebuild_usage_list(self, select_name: str | None = None) -> None:
        for child in self.usage_list_frame.winfo_children():
            child.destroy()
        self._usage_card_buttons.clear()

        if not self._usage_catalog:
            ctk.CTkLabel(
                self.usage_list_frame,
                text="暂无配置位，请检查 YAML 后刷新",
                text_color=("#64748b", "#94a3b8"),
            ).pack(padx=8, pady=12)
            return

        target = select_name or self._usage_selected
        if target is None and self._usage_catalog:
            target = self._usage_catalog[0].function_key

        for entry in self._usage_catalog:
            is_selected = entry.function_key == target
            btn = ctk.CTkButton(
                self.usage_list_frame,
                text=entry.function_key,
                anchor="center",
                height=44,
                fg_color=("#dbeafe", "#1e3a5f") if is_selected else ("#f1f5f9", "#1e293b"),
                text_color=("#0f172a", "#e2e8f0"),
                hover_color=("#bfdbfe", "#334155"),
                command=lambda n=entry.function_key: self._select_usage_function(n, force=True),
            )
            btn.pack(fill="x", padx=4, pady=4)
            self._usage_card_buttons[entry.function_key] = btn

        if target:
            self._select_usage_function(target, force=True, skip_prompt=True)

    def _maybe_save_usage_prompt(self) -> bool:
        """Return True if safe to switch away (saved, discarded, or no dirty)."""
        if not self._usage_dirty:
            return True
        choice = messagebox.askyesnocancel(
            "未保存的修改",
            "当前配置位的功能说明尚未保存。是否保存？\n是=保存  否=放弃  取消=留在当前页",
        )
        if choice is None:
            return False
        if choice:
            self.save_usage_current()
        else:
            self._usage_dirty = False
            entry = self._usage_entry_by_function(self._usage_selected or "")
            if entry is not None:
                self.usage_desc_box.delete("1.0", "end")
                self.usage_desc_box.insert("1.0", entry.description)
        return True

    def refresh_usage(self, silent: bool = False) -> None:
        if self._usage_dirty and not self._maybe_save_usage_prompt():
            return
        try:
            rows = core.list_models_data(self.app_yml.get(), self.local_yml.get())
            yaml_keys = {
                str(row.get("function", "")).strip()
                for row in rows
                if str(row.get("function", "")).strip()
            }
            is_v1, orphan_models = detect_v1_orphan_models(migration_rows=rows)
            if is_v1 and orphan_models:
                orphan_text = "、".join(orphan_models)
                self.log(
                    f"v1 迁移提示：以下 model 说明不在当前 YAML 中，迁移后将丢弃：{orphan_text}"
                )
                if not silent:
                    messagebox.showinfo(
                        "v1 迁移提示",
                        "检测到旧版 model-usage.json（按 model_name 存储）。\n\n"
                        f"以下 model 说明不在当前 YAML 映射中，迁移后将丢弃：\n{orphan_text}\n\n"
                        "同一 model_name 绑定多个配置位时，说明会复制到各配置位。",
                    )
            self._usage_saved = load_model_usage(migration_rows=rows)
            removed = prune_orphan_model_usage(yaml_keys)
            if removed and not silent:
                self.log(f"已清理不在 YAML 中的配置位说明：{'、'.join(removed)}")
            previous = self._usage_selected
            self._usage_catalog = build_model_catalog(
                self.app_yml.get(),
                self.local_yml.get(),
                self._usage_saved,
            )
            if previous:
                keys = {e.function_key for e in self._usage_catalog}
                if previous not in keys:
                    previous = None
            self._rebuild_usage_list(select_name=previous)
            if not silent:
                yaml_count = sum(1 for e in self._usage_catalog if e.in_yaml)
                self.log(f"模型功能列表已刷新：共 {yaml_count} 个配置位。")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("模型列表读取失败", str(e))
            self.log(f"模型功能列表读取失败: {e}")

    def _select_usage_function(
        self,
        function_key: str,
        *,
        force: bool = False,
        skip_prompt: bool = False,
    ) -> None:
        if not force and function_key == self._usage_selected:
            return
        if not skip_prompt and self._usage_dirty and not self._maybe_save_usage_prompt():
            return
        if self._usage_selected and self._usage_selected != function_key:
            self._sync_usage_editor_to_catalog()

        entry = self._usage_entry_by_function(function_key)
        if entry is None:
            return

        self._usage_selected = function_key
        self._usage_dirty = False

        for key, btn in self._usage_card_buttons.items():
            selected = key == function_key
            btn.configure(
                fg_color=("#dbeafe", "#1e3a5f") if selected else ("#f1f5f9", "#1e293b"),
            )

        hints: list[str] = []
        if entry.is_placeholder:
            hints.append("占位或未解析")
        if entry.orphan:
            hints.append("已不在当前 YAML 配置中")
        self.usage_title_label.configure(text=entry.function_key)
        self.usage_hint_label.configure(text=" · ".join(hints) if hints else " ")
        self._render_usage_model_info(entry)

        self.usage_desc_box.delete("1.0", "end")
        if entry.description:
            self.usage_desc_box.insert("1.0", entry.description)
        self._update_usage_status_label()

    def save_usage_current(self) -> None:
        if not self._usage_selected:
            messagebox.showwarning("提示", "请先选择左侧配置位。")
            return
        self._sync_usage_editor_to_catalog()
        descriptions = catalog_to_descriptions(self._usage_catalog)
        try:
            path = save_model_usage(descriptions, existing=self._usage_saved)
            self._usage_saved = load_model_usage(path)
            entry = self._usage_entry_by_function(self._usage_selected)
            if entry is not None:
                entry.description = descriptions.get(self._usage_selected, "")
            self._usage_dirty = False
            self._update_usage_status_label()
            self.log(f"已保存配置位「{self._usage_selected}」的功能说明。")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("保存失败", str(e))
            self.log(f"模型功能保存失败: {e}")

    def save_usage_all(self) -> None:
        if self._usage_selected:
            self._sync_usage_editor_to_catalog()
        if not self._usage_catalog:
            messagebox.showwarning("提示", "没有可保存的配置位。")
            return
        descriptions = catalog_to_descriptions(self._usage_catalog)
        try:
            path = save_model_usage(descriptions, existing=self._usage_saved)
            self._usage_saved = load_model_usage(path)
            for entry in self._usage_catalog:
                entry.description = descriptions.get(entry.function_key, "")
            self._usage_dirty = False
            self._update_usage_status_label()
            self.log(f"已保存全部 {len(descriptions)} 个配置位的功能说明到 {path.name}。")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("保存失败", str(e))
            self.log(f"模型功能保存失败: {e}")

    def _on_cleanup_body_configure(self, event) -> None:
        if getattr(self, "_cleanup_body", None) is not event.widget:
            return
        self._sync_cleanup_list_height()

    def _sync_cleanup_list_height(self) -> None:
        body = getattr(self, "_cleanup_body", None)
        if body is None or not body.winfo_exists():
            return
        body.update_idletasks()
        total_h = body.winfo_height()
        if total_h <= 1:
            return
        action_bar = getattr(self, "_cleanup_action_bar", None)
        action_h = action_bar.winfo_height() if action_bar is not None else 0
        list_h = max(160, total_h - action_h - 24)
        if list_h == getattr(self, "_cleanup_list_height", 0):
            return
        self._cleanup_list_height = list_h
        self.cleanup_list_frame.configure(height=list_h)

    def refresh_cleanup_scan(self) -> None:
        for child in self.cleanup_list_frame.winfo_children():
            child.destroy()
        self._cleanup_check_vars.clear()
        self._cleanup_items = scan_cache_items()

        if not self._cleanup_items:
            self.cleanup_empty_label.configure(text="当前没有可清理的缓存文件，一切很干净。")
            self.cleanup_empty_label.pack(pady=48)
            self.cleanup_summary_label.configure(text="可清理合计：0 B")
            self._sync_cleanup_list_height()
            return
        total = sum(item.size_bytes for item in self._cleanup_items)
        self.cleanup_summary_label.configure(
            text=f"可清理 {len(self._cleanup_items)} 项，合计 {format_size(total)}"
        )

        for item in self._cleanup_items:
            row = ctk.CTkFrame(self.cleanup_list_frame)
            row.pack(fill="x", padx=6, pady=6)
            row.grid_columnconfigure(1, weight=1)

            var = ctk.BooleanVar(value=item.default_selected)
            self._cleanup_check_vars[item.item_id] = var
            ctk.CTkCheckBox(row, text="", variable=var, width=28).grid(
                row=0, column=0, rowspan=2, padx=(8, 4), pady=10, sticky="n"
            )

            title_row = ctk.CTkFrame(row, fg_color="transparent")
            title_row.grid(row=0, column=1, sticky="ew", padx=(0, 8), pady=(10, 0))
            title_row.grid_columnconfigure(1, weight=1)
            ctk.CTkLabel(
                title_row,
                text=item.label,
                font=ctk.CTkFont(size=14, weight="bold"),
                anchor="w",
            ).grid(row=0, column=0, sticky="w")
            ctk.CTkLabel(
                title_row,
                text=format_size(item.size_bytes),
                text_color=("#b45309", "#fbbf24"),
                anchor="e",
            ).grid(row=0, column=1, sticky="e")

            ctk.CTkLabel(
                row,
                text=item.description,
                text_color=("#64748b", "#94a3b8"),
                anchor="w",
                justify="left",
                wraplength=640,
            ).grid(row=1, column=1, sticky="ew", padx=(0, 8), pady=(2, 10))

            ctk.CTkLabel(
                row,
                text=str(item.path),
                font=ctk.CTkFont(size=11),
                text_color=("#94a3b8", "#64748b"),
                anchor="w",
            ).grid(row=2, column=1, sticky="ew", padx=(0, 8), pady=(0, 10))

        self._sync_cleanup_list_height()

    def _cleanup_select_all(self) -> None:
        for var in self._cleanup_check_vars.values():
            var.set(True)

    def _cleanup_select_none(self) -> None:
        for var in self._cleanup_check_vars.values():
            var.set(False)

    def _reset_quota_snapshot_ui(self) -> None:
        self.latest_quota_rows = []
        self.clear_tree(self.quota_tree)

    def execute_cleanup(self) -> None:
        selected = [item_id for item_id, var in self._cleanup_check_vars.items() if var.get()]
        if not selected:
            messagebox.showwarning("提示", "请至少勾选一项要清理的缓存。")
            return

        labels = [item.label for item in self._cleanup_items if item.item_id in selected]
        preview = "\n".join(f"• {name}" for name in labels)
        if not messagebox.askyesno(
            "确认清理缓存",
            f"将删除以下缓存，不可恢复：\n\n{preview}\n\n是否继续？",
        ):
            self.log("用户取消清理缓存。")
            return

        results = clear_cache_entries(selected)
        freed = sum(r.freed_bytes for r in results if r.success)
        ok_count = sum(1 for r in results if r.success)
        fail = [r for r in results if not r.success]

        if "quota_snapshot" in selected:
            self._reset_quota_snapshot_ui()
        if "quota_runtime" in selected:
            try:
                materialize_platforms_config()
                self.config_yml.set(str(materialize_platforms_config()))
            except Exception as e:  # noqa: BLE001
                self.log(f"重建 platforms 配置时出错: {e}")

        self.refresh_cleanup_scan()
        self.log(f"缓存清理完成：成功 {ok_count} 项，释放约 {format_size(freed)}。")
        for r in results:
            status = "成功" if r.success else "失败"
            self.log(f"  [{status}] {r.label}: {r.message}")

        if fail:
            messagebox.showwarning(
                "部分清理失败",
                "\n".join(f"{r.label}: {r.message}" for r in fail),
            )
        else:
            messagebox.showinfo("清理完成", f"已释放约 {format_size(freed)} 磁盘空间。")


def main() -> int:
    app = ModelGuiApp()
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
