"""URL+浏览器 模式右侧引导配置（Firecrawl / 百炼登录相关）。"""

from __future__ import annotations

import os
import subprocess
from typing import TYPE_CHECKING, Callable

import customtkinter as ctk
from tkinter import messagebox

from quota.persist import firecrawl_api_key_from_env, load_gui_env_local, save_gui_env_fields
from quota.scrapers import http_text

if TYPE_CHECKING:
    from GUI.gui import ModelGuiApp

LogFn = Callable[[str], None]


class QuotaUrlBrowserGuidePanel(ctk.CTkScrollableFrame):
    def __init__(self, master: ctk.CTkBaseClass, app: ModelGuiApp, *, log: LogFn) -> None:
        super().__init__(master, fg_color=("#f8fafc", "#1e293b"), corner_radius=10)
        self._app = app
        self._log = log

        self.firecrawl_api_key = ctk.StringVar(value="")
        self.bailian_username = ctk.StringVar(value="")
        self.bailian_password = ctk.StringVar(value="")

        self._status = ctk.CTkLabel(self, text="", anchor="w", justify="left", wraplength=280)
        self._status.pack(fill="x", padx=12, pady=(12, 8))

        self._section_title("Playwright")
        ctk.CTkLabel(
            self,
            text="左侧填写 Cookie 或会话目录；首次请在浏览器中登录百炼控制台",
            anchor="w",
            justify="left",
            wraplength=280,
            text_color=("#64748b", "#94a3b8"),
        ).pack(fill="x", padx=12, pady=(0, 8))

        self._section_title("Firecrawl Browser")
        ctk.CTkLabel(
            self,
            text=(
                "1. 取消勾选 Playwright\n"
                "2. 安装 CLI：npm i -g firecrawl-cli\n"
                "3. 填写 API Key 并保存\n"
                "4. 勾选「使用 Firecrawl Browser」后执行查询"
            ),
            anchor="w",
            justify="left",
            wraplength=280,
            text_color=("#64748b", "#94a3b8"),
        ).pack(fill="x", padx=12, pady=(0, 6))

        ctk.CTkLabel(self, text="FIRECRAWL_API_KEY", anchor="w").pack(fill="x", padx=12, pady=(4, 2))
        ctk.CTkEntry(self, textvariable=self.firecrawl_api_key, show="*", placeholder_text="fc-...").pack(
            fill="x", padx=12, pady=(0, 8)
        )

        self._section_title("百炼控制台账号")

        ctk.CTkLabel(self, text="账号", anchor="w").pack(fill="x", padx=12, pady=(2, 2))
        ctk.CTkEntry(self, textvariable=self.bailian_username, placeholder_text="阿里云 / 百炼登录名").pack(
            fill="x", padx=12, pady=(0, 6)
        )
        ctk.CTkLabel(self, text="密码", anchor="w").pack(fill="x", padx=12, pady=(2, 2))
        ctk.CTkEntry(self, textvariable=self.bailian_password, show="*", placeholder_text="仅保存在本机").pack(
            fill="x", padx=12, pady=(0, 8)
        )

        btn_row = ctk.CTkFrame(self, fg_color="transparent")
        btn_row.pack(fill="x", padx=12, pady=(4, 12))
        ctk.CTkButton(btn_row, text="保存到本地配置", width=120, command=self._save_config).pack(
            side="left", padx=(0, 6)
        )
        ctk.CTkButton(btn_row, text="检测 Firecrawl", width=110, command=self._check_firecrawl).pack(
            side="left"
        )

        self.load_from_env()
        self.refresh_status()

    def _section_title(self, text: str) -> None:
        ctk.CTkLabel(
            self,
            text=text,
            font=ctk.CTkFont(size=14, weight="bold"),
            anchor="w",
        ).pack(fill="x", padx=12, pady=(10, 4))

    def load_from_env(self) -> None:
        env, _ = load_gui_env_local()
        self.firecrawl_api_key.set(env.get("FIRECRAWL_API_KEY", ""))
        self.bailian_username.set(env.get("BAILIAN_CONSOLE_USERNAME", ""))
        self.bailian_password.set(env.get("BAILIAN_CONSOLE_PASSWORD", ""))

    def refresh_status(self) -> None:
        lines: list[str] = []
        if self._app.use_playwright.get() and self._app.use_firecrawl_browser.get():
            lines.append("⚠ 已勾选 Playwright：Firecrawl 不会生效")
        if http_text.has_firecrawl_cli():
            lines.append("✓ firecrawl CLI 已安装")
        else:
            lines.append("✗ 未找到 firecrawl 命令")
        if firecrawl_api_key_from_env() or self.firecrawl_api_key.get().strip():
            lines.append("✓ FIRECRAWL_API_KEY 已配置")
        else:
            lines.append("✗ 未配置 FIRECRAWL_API_KEY")
        if self._app.cookie_file.get().strip():
            lines.append("✓ 已填 Cookie 文件")
        elif self._app.browser_profile_dir.get().strip():
            lines.append("✓ 已填会话目录")
        self._status.configure(text="\n".join(lines))

    def _save_config(self) -> None:
        updates = {
            "FIRECRAWL_API_KEY": self.firecrawl_api_key.get().strip(),
            "BAILIAN_CONSOLE_USERNAME": self.bailian_username.get().strip(),
            "BAILIAN_CONSOLE_PASSWORD": self.bailian_password.get().strip(),
            "QUOTA_QUERY_URL": self._app.query_url.get().strip(),
            "QUOTA_COOKIE_FILE": self._app.cookie_file.get().strip(),
            "QUOTA_BROWSER_PROFILE": self._app.browser_profile_dir.get().strip(),
            "QUOTA_USE_PLAYWRIGHT": "true" if self._app.use_playwright.get() else "false",
            "QUOTA_USE_FIRECRAWL": "true" if self._app.use_firecrawl_browser.get() else "false",
        }
        path = save_gui_env_fields(updates)
        if path is None:
            messagebox.showerror("保存失败", "没有可写入的配置项。")
            return
        key = self.firecrawl_api_key.get().strip()
        if key:
            os.environ["FIRECRAWL_API_KEY"] = key
        self._log(f"引导配置已保存：{path.name}")
        messagebox.showinfo("已保存", f"配置已写入\n{path}")
        self.refresh_status()

    def _check_firecrawl(self) -> None:
        if not http_text.has_firecrawl_cli():
            messagebox.showwarning(
                "Firecrawl",
                "未检测到 firecrawl 命令。\n\n请先安装：\nnpm install -g firecrawl-cli\n\n安装后重启终端或 GUI。",
            )
            self.refresh_status()
            return
        try:
            result = subprocess.run(
                http_text._firecrawl_argv("--version"),
                capture_output=True,
                text=True,
                timeout=15,
                env=http_text.firecrawl_subprocess_env(),
            )
            out = (result.stdout or result.stderr or "").strip()
            messagebox.showinfo("Firecrawl", out or "命令可执行")
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("Firecrawl", str(e))
        self.refresh_status()
