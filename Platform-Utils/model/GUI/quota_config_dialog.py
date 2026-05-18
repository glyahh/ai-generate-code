"""额度查询「配置与说明」弹窗（单例 Toplevel）。"""

from __future__ import annotations

from typing import TYPE_CHECKING, Callable

import customtkinter as ctk

from GUI.quota_setup_panel import QuotaUrlBrowserGuidePanel

if TYPE_CHECKING:
    from GUI.gui import ModelGuiApp

LogFn = Callable[[str], None]


def open_quota_config_dialog(
    parent: ctk.CTk,
    app: ModelGuiApp,
    *,
    log: LogFn,
) -> QuotaUrlBrowserGuidePanel | None:
    """打开或聚焦配置弹窗；返回内嵌的引导面板实例。"""
    existing = getattr(parent, "_quota_config_toplevel", None)
    panel = getattr(parent, "quota_guide_panel", None)
    if existing is not None:
        try:
            if existing.winfo_exists():
                existing.lift()
                existing.focus_force()
                if panel is not None:
                    panel.refresh_status()
                return panel
        except Exception:
            pass

    top = ctk.CTkToplevel(parent)
    top.title("配置与说明")
    top.geometry("420x560")
    top.minsize(380, 480)
    top.transient(parent)

    body = ctk.CTkFrame(top, fg_color="transparent")
    body.pack(fill="both", expand=True, padx=8, pady=8)
    guide = QuotaUrlBrowserGuidePanel(body, app, log=log)
    guide.pack(fill="both", expand=True)

    parent._quota_config_toplevel = top  # type: ignore[attr-defined]
    app.quota_guide_panel = guide

    def _on_close() -> None:
        if getattr(parent, "_quota_config_toplevel", None) is top:
            parent._quota_config_toplevel = None  # type: ignore[attr-defined]
        app.quota_guide_panel = None
        try:
            top.destroy()
        except Exception:
            pass

    top.protocol("WM_DELETE_WINDOW", _on_close)
    top.lift()
    top.focus_force()
    return guide
