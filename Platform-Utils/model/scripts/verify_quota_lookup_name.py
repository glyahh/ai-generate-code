#!/usr/bin/env python3
"""Print which model names quota query actually uses (from YAML, not UI cache)."""
from __future__ import annotations

import sys
from pathlib import Path

MODEL_DIR = Path(__file__).resolve().parents[1]
if str(MODEL_DIR) not in sys.path:
    sys.path.insert(0, str(MODEL_DIR))

import main as core  # noqa: E402
from quota.parsers.text_match import quota_lookup_model_name, resolve_quota_for_model

rows = core.list_models_data()
print("=== YAML -> quota lookup name ===")
for r in rows:
    lookup = quota_lookup_model_name(r["model_name"])
    print(f"  {r['function']:40}  yaml={r['model_name']!r}  lookup={lookup!r}")

streaming = [r for r in rows if r["function"] == "streaming-chat-model"]
if streaming:
    name = quota_lookup_model_name(streaming[0]["model_name"])
    sample = "MiniMax-M2.5\n362,917/1,000,000\n"
    q = resolve_quota_for_model(sample, name)
    print()
    print("=== streaming-chat-model simulation ===")
    print(f"  resolve_quota_for_model(model_name) = {name!r}")
    print(f"  is glm-5: {name.lower() == 'glm-5'}")
    print(f"  is MiniMax-M2.5: {name == 'MiniMax-M2.5'}")
    print(f"  parsed quota: {q!r}")
