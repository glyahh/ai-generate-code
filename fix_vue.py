with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add imports after appApply line
old_import = "import { appApplyListMyHistoryUsingPost, appApplyUsingPost } from \"@/api/appController\""
new_import = old_import + "\\nimport { userPersonalizationGetUsingGet, userPersonalizationPutUsingPut } from \"@/api/userController\"\\nimport type { UserPersonalizationVO, UserPersonalizationUpdateRequest } from \"@/api/userController\""
content = content.replace(old_import, new_import, 1)

# 2. Add StarOutlined
old_star = "  HistoryOutlined,"
new_star = old_star + "\\n  StarOutlined,"
content = content.replace(old_star, new_star, 1)

# 3. Update MenuKey type
old_type = "type MenuKey = \x27password\x27 | \x27profile\x27 | \x27apply\x27 | \x27history\x27"
new_type = "type MenuKey = \x27password\x27 | \x27profile\x27 | \x27apply\x27 | \x27history\x27 | \x27personalization\x27"
content = content.replace(old_type, new_type, 1)

# 4. Add personalization menu item
old_item = "{ key: \x27profile\x27, label: \x27\u57fa\u672c\u8d44\u6599\x27, icon: UserOutlined },"
new_item = old_item + "\\n    { key: \x27personalization\x27, label: \x27\u4e2a\u6027\u5316\x27, icon: StarOutlined },"
content = content.replace(old_item, new_item, 1)

# 5. Add personalization functions after handleProfileSubmit closing brace
old_end_func = "    profileLoading.value = false\\n  }\\n}\\n\\n// \u7533\u8bf7\u7ba1\u7406"
new_end_func = old_end_func.replace("// \u7533\u8bf7\u7ba1\u7406", personalization_funcs + "\\n\\n// \u7533\u8bf7\u7ba1\u7406")
content = content.replace(old_end_func, new_end_func, 1)

# 6. Add personalization template section before password section
old_template = "          <section v-else-if=\x22activeMenu === \x5c\x27password\x5c\x27\x22 class=\x22work-panel\x22>\\n            <h3 class=\x22panel-title\x22>\u5b89\u5168\u8bbe\u7f6e</h3>"
new_template = personalization_template + "\\n\\n" + old_template
content = content.replace(old_template, new_template, 1)

# 7. Add CSS before </style>
old_css_end = "}</style>"
new_css_end = personalization_css + "\\n}</style>"
content = content.replace(old_css_end, new_css_end, 1)

with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "w", encoding="utf-8") as f:
    f.write(content)
print("Done")
