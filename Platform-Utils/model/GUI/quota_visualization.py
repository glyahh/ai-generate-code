from __future__ import annotations

import re
from tkinter import Canvas, ttk

import customtkinter as ctk

from quota.persist import save_gui_viz_mode, viz_mode_from_env

# ui-ux-pro-max: Dark OLED dashboard tokens
BG_PAGE = "#0f172a"
BG_CARD = "#1e293b"
BORDER = "#334155"
TEXT_PRIMARY = "#f1f5f9"
TEXT_MUTED = "#94a3b8"
ACCENT_USED = "#3b82f6"
ACCENT_REMAIN = "#10b981"
BAR_FILL = "#7c3aed"
BAR_TRACK = "#334155"
WARN = "#f59e0b"

FONT_FAMILY = "Microsoft YaHei UI"
VIZ_MODES = ("donut", "bar")
VIZ_MODE_LABELS = {"donut": "环形图", "bar": "进度条"}


def parse_quota_numbers(quota_text: str) -> tuple[float, float] | None:
    if not quota_text:
        return None
    cleaned = quota_text.replace("剩", "").replace("余", "").replace("共", "").replace("+", "")
    match = re.search(r"([\d,]+(?:\.\d+)?)\s*/\s*([\d,]+(?:\.\d+)?)", cleaned)
    if not match:
        return None
    remain = float(match.group(1).replace(",", ""))
    total = float(match.group(2).replace(",", ""))
    if total <= 0:
        return None
    used = max(total - remain, 0.0)
    return used, total


def parse_model_stats(rows: list[dict[str, str]]) -> list[dict[str, float | str]]:
    stats: list[dict[str, float | str]] = []
    for row in rows:
        parsed = parse_quota_numbers(str(row.get("quota", "")))
        if not parsed:
            continue
        used, total = parsed
        remain = max(total - used, 0.0)
        used_pct = (used / total * 100.0) if total > 0 else 0.0
        remain_pct = (remain / total * 100.0) if total > 0 else 0.0
        stats.append(
            {
                "model": str(row.get("model_name", "unknown")),
                "used": used,
                "remain": remain,
                "total": total,
                "used_pct": used_pct,
                "remain_pct": remain_pct,
            }
        )
    stats.sort(key=lambda item: float(item["remain_pct"]))
    return stats


def _grid_columns(width: int) -> int:
    if width >= 1080:
        return 3
    if width >= 720:
        return 2
    return 1


class QuotaVisualizationWindow(ctk.CTkToplevel):
    """模型用量可视化：环形图 / 进度条双视图。"""

    def __init__(
        self,
        parent: ctk.CTk,
        model_stats: list[dict[str, float | str]],
        *,
        initial_mode: str | None = None,
    ) -> None:
        super().__init__(parent)
        self._parent_app = parent
        self._model_stats = model_stats
        self._mode = initial_mode if initial_mode in VIZ_MODES else viz_mode_from_env()
        self._donut_canvas: Canvas | None = None
        self._donut_host: ctk.CTkFrame | None = None
        self._bar_host: ctk.CTkScrollableFrame | None = None
        self._cols = 2
        self._configure_job: str | None = None
        self._selected_index = 0
        self._bar_cards: list[ctk.CTkFrame] = []
        self._bar_card_low_remain: list[bool] = []

        self.title("模型用量可视化")
        self.geometry("1180x860")
        self.minsize(640, 520)
        self.configure(fg_color=BG_PAGE)

        self._build_chrome()
        self._apply_window_focus()
        self._render_active_view()
        self.bind("<Configure>", self._on_window_configure, add="+")

    def _apply_window_focus(self) -> None:
        self.transient(self._parent_app)
        self.lift()
        self.focus_force()
        self.after(100, lambda: self._brief_topmost(True))
        self.after(350, lambda: self._brief_topmost(False))

    def _brief_topmost(self, enabled: bool) -> None:
        try:
            self.attributes("-topmost", enabled)
        except Exception:
            pass

    def _build_chrome(self) -> None:
        header = ctk.CTkFrame(self, fg_color="transparent")
        header.pack(fill="x", padx=20, pady=(16, 8))

        title_col = ctk.CTkFrame(header, fg_color="transparent")
        title_col.pack(side="left", fill="x", expand=True)
        ctk.CTkLabel(
            title_col,
            text="模型用量可视化",
            font=ctk.CTkFont(family=FONT_FAMILY, size=22, weight="bold"),
            text_color=TEXT_PRIMARY,
            anchor="w",
        ).pack(anchor="w")

        toggle_frame = ctk.CTkFrame(header, fg_color="transparent")
        toggle_frame.pack(side="right", padx=(12, 0))
        self._mode_var = ctk.StringVar(value=VIZ_MODE_LABELS[self._mode])
        self._mode_switch = ctk.CTkSegmentedButton(
            toggle_frame,
            values=[VIZ_MODE_LABELS["donut"], VIZ_MODE_LABELS["bar"]],
            variable=self._mode_var,
            command=self._on_mode_change,
            font=ctk.CTkFont(family=FONT_FAMILY, size=13, weight="bold"),
            selected_color=BAR_FILL,
            selected_hover_color="#6d28d9",
            unselected_color=BG_CARD,
            unselected_hover_color=BORDER,
        )
        self._mode_switch.pack()

        self._content = ctk.CTkFrame(self, fg_color=BG_PAGE)
        self._content.pack(fill="both", expand=True, padx=16, pady=(0, 16))
        self._content.grid_columnconfigure(0, weight=1)
        self._content.grid_rowconfigure(0, weight=1)

    def _on_mode_change(self, label: str) -> None:
        label_to_mode = {v: k for k, v in VIZ_MODE_LABELS.items()}
        mode = label_to_mode.get(label, "donut")
        if mode == self._mode:
            return
        self._mode = mode
        save_gui_viz_mode(mode)
        self._render_active_view()

    def _clear_content(self) -> None:
        for child in self._content.winfo_children():
            child.destroy()
        self._donut_canvas = None
        self._donut_host = None
        self._bar_host = None
        self._bar_cards = []
        self._bar_card_low_remain = []

    def _clamp_selected_index(self) -> None:
        if not self._model_stats:
            self._selected_index = 0
        elif self._selected_index >= len(self._model_stats):
            self._selected_index = 0

    def _on_card_select(self, index: int) -> None:
        if index < 0 or index >= len(self._model_stats):
            return
        if index == self._selected_index:
            return
        self._selected_index = index
        if self._mode == "bar":
            self._apply_bar_selection()
        else:
            self._draw_donut_grid()

    def _apply_bar_selection(self) -> None:
        """仅当前选中卡片显示黄色描边，点击后黄框随之移动。"""
        for index, card in enumerate(self._bar_cards):
            if index == self._selected_index:
                card.configure(border_width=2, border_color=WARN)
            else:
                card.configure(border_width=1, border_color=BORDER)

    def _render_active_view(self) -> None:
        self._clear_content()
        self._clamp_selected_index()
        if self._mode == "bar":
            self._build_bar_view()
        else:
            self._build_donut_view()

    def _build_donut_view(self) -> None:
        host = ctk.CTkFrame(self._content, fg_color=BG_PAGE)
        host.grid(row=0, column=0, sticky="nsew")
        host.grid_columnconfigure(0, weight=1)
        host.grid_rowconfigure(0, weight=1)
        self._donut_host = host

        canvas = Canvas(host, bg=BG_PAGE, highlightthickness=0, bd=0)
        canvas.grid(row=0, column=0, sticky="nsew")
        y_scroll = ttk.Scrollbar(host, orient="vertical", command=canvas.yview)
        y_scroll.grid(row=0, column=1, sticky="ns")
        canvas.configure(yscrollcommand=y_scroll.set)
        self._donut_canvas = canvas
        self._draw_donut_grid()

    def _on_window_configure(self, event) -> None:
        if event.widget is not self:
            return
        if self._mode != "donut" or self._donut_canvas is None:
            return
        new_cols = _grid_columns(event.width)
        if new_cols == self._cols:
            return
        self._cols = new_cols
        if self._configure_job:
            self.after_cancel(self._configure_job)
        self._configure_job = self.after(120, self._draw_donut_grid)

    def _draw_donut_grid(self) -> None:
        canvas = self._donut_canvas
        if canvas is None:
            return
        canvas.delete("all")
        width = max(self.winfo_width() - 48, 600)
        cols = _grid_columns(width)
        self._cols = cols

        card_w = max(280, (width - 24) // cols)
        card_h = 360
        pie_r = 88
        inner_r = 52
        pad_x = 12
        pad_y = 12

        for index, stat in enumerate(self._model_stats):
            row_i = index // cols
            col_i = index % cols
            ox = col_i * card_w + pad_x
            oy = row_i * card_h + pad_y
            cx = ox + card_w // 2
            cy = oy + 130

            model = str(stat["model"])
            used = float(stat["used"])
            remain = float(stat["remain"])
            total = float(stat["total"])
            used_pct = float(stat["used_pct"])
            remain_pct = float(stat["remain_pct"])
            low_remain = remain_pct < 10.0
            selected = index == self._selected_index
            border_color = WARN if selected else BORDER
            border_width = 2 if selected else 1

            card_tag = f"card_{index}"
            canvas.create_rectangle(
                ox,
                oy,
                ox + card_w - 8,
                oy + card_h - 8,
                fill=BG_CARD,
                outline=border_color,
                width=border_width,
                tags=(card_tag,),
            )

            used_extent = 360.0 * (used / total) if total > 0 else 0.0
            pie_tag = f"pie_{index}"

            canvas.create_oval(
                cx - pie_r - 1,
                cy - pie_r - 1,
                cx + pie_r + 1,
                cy + pie_r + 1,
                outline=border_color,
                width=border_width,
                tags=(pie_tag, card_tag),
            )
            if used <= 0:
                canvas.create_oval(
                    cx - pie_r,
                    cy - pie_r,
                    cx + pie_r,
                    cy + pie_r,
                    fill=ACCENT_REMAIN,
                    outline=BG_CARD,
                    width=2,
                    tags=(pie_tag, card_tag),
                )
            elif remain <= 0:
                canvas.create_oval(
                    cx - pie_r,
                    cy - pie_r,
                    cx + pie_r,
                    cy + pie_r,
                    fill=ACCENT_USED,
                    outline=BG_CARD,
                    width=2,
                    tags=(pie_tag, card_tag),
                )
            else:
                canvas.create_arc(
                    cx - pie_r,
                    cy - pie_r,
                    cx + pie_r,
                    cy + pie_r,
                    start=90,
                    extent=-used_extent,
                    fill=ACCENT_USED,
                    outline=BG_CARD,
                    width=2,
                    tags=(pie_tag, card_tag),
                )
                canvas.create_arc(
                    cx - pie_r,
                    cy - pie_r,
                    cx + pie_r,
                    cy + pie_r,
                    start=90 - used_extent,
                    extent=-(360.0 - used_extent),
                    fill=ACCENT_REMAIN,
                    outline=BG_CARD,
                    width=2,
                    tags=(pie_tag, card_tag),
                )
            canvas.create_oval(
                cx - inner_r,
                cy - inner_r,
                cx + inner_r,
                cy + inner_r,
                fill=BG_CARD,
                outline=BG_CARD,
                tags=(pie_tag, card_tag),
            )
            canvas.create_text(
                cx,
                cy - 2,
                text=f"{used_pct:.1f}%",
                fill=TEXT_PRIMARY,
                font=(FONT_FAMILY, 22, "bold"),
                tags=(pie_tag, card_tag),
            )
            canvas.create_text(
                cx,
                oy + 248,
                text=model,
                fill=TEXT_PRIMARY,
                font=(FONT_FAMILY, 15, "bold"),
                width=card_w - 32,
                tags=(pie_tag, card_tag),
            )
            canvas.create_text(
                cx,
                oy + 278,
                text=f"已用 {used:,.0f}  |  剩余 {remain:,.0f}",
                fill=TEXT_MUTED,
                font=(FONT_FAMILY, 13),
                tags=(pie_tag, card_tag),
            )
            canvas.create_text(
                cx,
                oy + 302,
                text=f"剩余 {remain_pct:.1f}%",
                fill=WARN if low_remain else TEXT_MUTED,
                font=(FONT_FAMILY, 12),
                tags=(pie_tag, card_tag),
            )

            def _on_click(_event, idx: int = index) -> None:
                self._on_card_select(idx)

            canvas.tag_bind(pie_tag, "<Button-1>", _on_click)
            canvas.tag_bind(card_tag, "<Button-1>", _on_click)

        rows_count = (len(self._model_stats) + cols - 1) // cols
        total_h = max(rows_count * card_h + pad_y, 400)
        total_w = cols * card_w + pad_x
        canvas.configure(scrollregion=(0, 0, total_w, total_h))

    def _build_bar_view(self) -> None:
        scroll = ctk.CTkScrollableFrame(
            self._content,
            fg_color=BG_PAGE,
            scrollbar_button_color=BORDER,
            scrollbar_button_hover_color=TEXT_MUTED,
        )
        scroll.grid(row=0, column=0, sticky="nsew")
        scroll.grid_columnconfigure(0, weight=1)
        self._bar_host = scroll
        self._bar_cards = []
        self._bar_card_low_remain = []

        for index, stat in enumerate(self._model_stats):
            model = str(stat["model"])
            used = float(stat["used"])
            remain = float(stat["remain"])
            total = float(stat["total"])
            remain_pct = float(stat["remain_pct"])
            low_remain = remain_pct < 10.0

            card = ctk.CTkFrame(
                scroll,
                fg_color=BG_CARD,
                corner_radius=12,
                border_width=1,
                border_color=BORDER,
            )
            card.grid(row=index, column=0, sticky="ew", padx=4, pady=6)
            card.grid_columnconfigure(0, weight=1)
            self._bar_cards.append(card)
            self._bar_card_low_remain.append(low_remain)

            top = ctk.CTkFrame(card, fg_color="transparent")
            top.grid(row=0, column=0, sticky="ew", padx=16, pady=(14, 6))
            top.grid_columnconfigure(0, weight=1)
            ctk.CTkLabel(
                top,
                text=model,
                font=ctk.CTkFont(family=FONT_FAMILY, size=15, weight="bold"),
                text_color=TEXT_PRIMARY,
                anchor="w",
            ).grid(row=0, column=0, sticky="w")
            pct_color = WARN if low_remain else BAR_FILL
            ctk.CTkLabel(
                top,
                text=f"剩余 {remain_pct:.1f}%",
                font=ctk.CTkFont(family=FONT_FAMILY, size=15, weight="bold"),
                text_color=pct_color,
                anchor="e",
            ).grid(row=0, column=1, sticky="e")

            progress = ctk.CTkProgressBar(
                card,
                height=14,
                corner_radius=7,
                progress_color=BAR_FILL if not low_remain else WARN,
                fg_color=BAR_TRACK,
                border_width=0,
            )
            progress.grid(row=1, column=0, sticky="ew", padx=16, pady=(0, 6))
            progress.set(remain / total if total > 0 else 0.0)

            bottom = ctk.CTkFrame(card, fg_color="transparent")
            bottom.grid(row=2, column=0, sticky="ew", padx=16, pady=(0, 14))
            remain_text = ctk.CTkLabel(
                bottom,
                text=f"剩 {remain:,.0f}",
                font=ctk.CTkFont(family=FONT_FAMILY, size=14, weight="bold"),
                text_color=TEXT_PRIMARY,
                anchor="w",
            )
            remain_text.pack(side="left")
            ctk.CTkLabel(
                bottom,
                text=f" / 共 {total:,.0f}",
                font=ctk.CTkFont(family=FONT_FAMILY, size=13),
                text_color=TEXT_MUTED,
                anchor="w",
            ).pack(side="left")
            ctk.CTkLabel(
                bottom,
                text=f"已用 {used:,.0f}",
                font=ctk.CTkFont(family=FONT_FAMILY, size=12),
                text_color=TEXT_MUTED,
                anchor="e",
            ).pack(side="right")

            def _on_click_bar(_event, idx: int = index) -> None:
                self._on_card_select(idx)

            bind_targets: list[ctk.CTkBaseClass] = [card, top, bottom, progress, remain_text]
            for child in top.winfo_children():
                bind_targets.append(child)
            for child in bottom.winfo_children():
                bind_targets.append(child)
            for widget in bind_targets:
                widget.bind("<Button-1>", _on_click_bar)

        self._apply_bar_selection()


def open_quota_visualization(
    parent: ctk.CTk,
    rows: list[dict[str, str]],
) -> QuotaVisualizationWindow | None:
    """单例打开可视化窗口；无数据时返回 None（由调用方弹 messagebox）。"""
    stats = parse_model_stats(rows)
    if not stats:
        return None

    existing = getattr(parent, "_quota_viz_window", None)
    if existing is not None:
        try:
            if existing.winfo_exists():
                existing.destroy()
        except Exception:
            pass

    win = QuotaVisualizationWindow(parent, stats, initial_mode=viz_mode_from_env())
    parent._quota_viz_window = win  # type: ignore[attr-defined]

    def _on_close() -> None:
        if getattr(parent, "_quota_viz_window", None) is win:
            parent._quota_viz_window = None  # type: ignore[attr-defined]
        win.destroy()

    win.protocol("WM_DELETE_WINDOW", _on_close)
    return win
