with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "r", encoding="utf-8") as f:
    content = f.read()

# Normalize to LF
content = content.replace("\r\n", "\n")

# Change 1: Add imports after appController line
old1 = "import { appApplyListMyHistoryUsingPost, appApplyUsingPost } from \"@/api/appController\"\nimport { message } from 'ant-design-vue'"
new1 = old1 + "\nimport { userPersonalizationGetUsingGet, userPersonalizationPutUsingPut } from \"@/api/userController\"\nimport type { UserPersonalizationVO, UserPersonalizationUpdateRequest } from \"@/api/userController\""
if old1 not in content:
    # Try without trailing \r
    old1b = old1.replace("\r", "")
    print("Trying alternate old1...")
content = content.replace(old1, new1, 1)
print("1.", "Done" if old1 in content else "Not applied (might be preexisting)")

# Change 2: Add loadPersonalization() after loadApplyList()
old2 = "  loadApplyList()\n})"
new2 = "  loadApplyList()\n  loadPersonalization()\n})"
content = content.replace(old2, new2, 1)
print("2.", "Done" if old2 in content else "Not applied (might be preexisting)")

# Change 3: Add CSS before </style>
css3 = "\n/* 个性化面板样式 */\n.personalization-section .panel-desc {\n  font-size: 14px;\n  color: var(--text-secondary, #888);\n  margin-bottom: 16px;\n}\n"
old3 = "}</style>"
content = content.replace(old3, css3 + old3, 1)
print("3.", "Done" if old3 in content else "Not applied (might be preexisting)")

with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "w", encoding="utf-8") as f:
    f.write(content)
print("ALL DONE")
