with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "r", encoding="utf-8") as f:
    content = f.read()

# Normalize line endings to LF
content = content.replace("\r\n", "\n")

# 1. Add imports
old1 = "import { appApplyListMyHistoryUsingPost, appApplyUsingPost } from \"@/api/appController\"\nimport { message } from 'ant-design-vue'"
new1 = "import { appApplyListMyHistoryUsingPost, appApplyUsingPost } from \"@/api/appController\"\nimport { userPersonalizationGetUsingGet, userPersonalizationPutUsingPut } from \"@/api/userController\"\nimport type { UserPersonalizationVO, UserPersonalizationUpdateRequest } from \"@/api/userController\"\nimport { message } from 'ant-design-vue'"
if old1 in content:
    content = content.replace(old1, new1, 1)
    print("1. Imports done")
else:
    print("1. Imports - old string NOT FOUND")

# 2. StarOutlined
old2 = "  HistoryOutlined,"
new2 = "  HistoryOutlined,\n  StarOutlined,"
if old2 in content:
    content = content.replace(old2, new2, 1)
    print("2. StarOutlined done")
else:
    print("2. StarOutlined NOT FOUND")

# 3. MenuKey type
old3 = "type MenuKey = 'password' | 'profile' | 'apply' | 'history'"
new3 = "type MenuKey = 'password' | 'profile' | 'apply' | 'history' | 'personalization'"
if old3 in content:
    content = content.replace(old3, new3, 1)
    print("3. MenuKey done")
else:
    print("3. MenuKey NOT FOUND")

# 4. Menu item
old4 = "{ key: 'profile', label: '\u57fa\u672c\u8d44\u6599', icon: UserOutlined },"
new4 = "{ key: 'profile', label: '\u57fa\u672c\u8d44\u6599', icon: UserOutlined },\n    { key: 'personalization', label: '\u4e2a\u6027\u5316', icon: StarOutlined },"
if old4 in content:
    content = content.replace(old4, new4, 1)
    print("4. Menu item done")
else:
    print("4. Menu item NOT FOUND")

# 5. Functions
funcs_block = (
    "\nconst personalizationForm = ref({ appStyle: '', answerStyle: '' })\n"
    "const personalizationLoading = ref(false)\n\n"
    "async function loadPersonalization() {\n"
    "  try {\n"
    "    const res = await userPersonalizationGetUsingGet()\n"
    "    if (res?.data) {\n"
    "      personalizationForm.value = { appStyle: res.data.appStyle, answerStyle: res.data.answerStyle }\n"
    "    }\n"
    "  } catch (e: any) { message.error('loading') }\n"
    "}\n\n"
    "function customCount(val: string) {\n"
    "  const remaining = 2000 - String(val ?? '').length\n"
    "  return remaining > 0 ? '\u8fd8\u53ef\u8f93\u5165 ' + remaining + ' \u5b57' : '\u5df2\u8fbe\u4e0a\u9650'\n"
    "}\n\n"
    "async function handlePersonalizationSubmit() {\n"
    "  personalizationLoading.value = true\n"
    "  try {\n"
    "    const res = await userPersonalizationPutUsingPut({ body: { appStyle: personalizationForm.value.appStyle, answerStyle: personalizationForm.value.answerStyle } })\n"
    "    if (res?.data === true) message.success('\u4fdd\u5b58\u6210\u529f')\n"
    "    else message.error('\u5185\u5bb9\u4e0d\u4e3a\u7a7a')\n"
    "  } catch (e: any) { message.error('\u5185\u5bb9\u4e0d\u4e3a\u7a7a') }\n"
    "  finally { personalizationLoading.value = false }\n"
    "}\n"
)
old5 = "    profileLoading.value = false\n  }\n}\n\n// \u7533\u8bf7\u7ba1\u7406"
new5 = "    profileLoading.value = false\n  }\n}\n" + funcs_block + "\n// \u7533\u8bf7\u7ba1\u7406"
if old5 in content:
    content = content.replace(old5, new5, 1)
    print("5. Functions done")
else:
    print("5. Functions NOT FOUND")

# 6. onMounted
old6 = "  loadApplyList()\n})"
new6 = "  loadApplyList()\n  loadPersonalization()\n})"
if old6 in content:
    content = content.replace(old6, new6, 1)
    print("6. onMounted done")
else:
    print("6. onMounted NOT FOUND")

# 7. Template section
template_block = (
    "          <section v-else-if=\"activeMenu === 'personalization'\" class=\"work-panel\">\n"
    "            <h3 class=\"panel-title\">\u4e2a\u6027\u5316</h3>\n"
    "            <p class=\"panel-desc\">\u914d\u7f6e\u4f60\u7684\u4ee3\u7801\u751f\u6210\u504f\u597d\uff0c\u6bcf\u6b21\u751f\u6210\u65f6 AI \u5c06\u53c2\u8003\u4ee5\u4e0b\u6307\u5357</p>\n"
    "            <div class=\"form-card\">\n"
    "              <a-form layout=\"vertical\">\n"
    "                <a-form-item label=\"\u5e94\u7528\u98ce\u683c\">\n"
    "                  <a-textarea v-model:value=\"personalizationForm.appStyle\" placeholder=\"\u4f8b\u5982\uff1a\u6211\u559c\u6b22\u7b80\u6d01\u3001\u4f7f\u7528\u6bdb\u73bb\u7483\u6548\u679c\u3001\u4e3b\u8272\u8c03\u4e3a\u84dd\u8272...\" :rows=\"5\" size=\"large\" :maxlength=\"2000\" :show-count=\"customCount\" />\n"
    "                </a-form-item>\n"
    "                <a-form-item label=\"\u56de\u7b54\u98ce\u683c\">\n"
    "                  <a-textarea v-model:value=\"personalizationForm.answerStyle\" placeholder=\"\u4f8b\u5982\uff1a\u7528\u6b63\u5f0f\u7684\u8bed\u6c14\u56de\u7b54\uff0c\u4ee3\u7801\u4e0e\u89e3\u91ca\u4ea4\u66ff...\" :rows=\"5\" size=\"large\" :maxlength=\"2000\" :show-count=\"customCount\" />\n"
    "                </a-form-item>\n"
    "                <a-button type=\"primary\" size=\"large\" :loading=\"personalizationLoading\" class=\"submit-btn\"\n"
    "                  @click=\"handlePersonalizationSubmit\">\n"
    "                  \u4fdd\u5b58\u914d\u7f6e\n"
    "                </a-button>\n"
    "              </a-form>\n"
    "            </div>\n"
    "          </section>\n"
)
old7 = "          <section v-else-if=\"activeMenu === 'password'\" class=\"work-panel\">\n            <h3 class=\"panel-title\">\u5b89\u5168\u8bbe\u7f6e</h3>"
if old7 in content:
    content = content.replace(old7, template_block + old7, 1)
    print("7. Template done")
else:
    print("7. Template NOT FOUND")

# 8. CSS
css_block = (
    "\n/* \u4e2a\u6027\u5316\u9762\u677f\u6837\u5f0f */\n"
    ".personalization-section .panel-desc {\n"
    "  font-size: 14px;\n"
    "  color: var(--text-secondary, #888);\n"
    "  margin-bottom: 16px;\n"
    "}\n"
)
old8 = "}</style>"
if old8 in content:
    content = content.replace(old8, css_block + "}", 1)
    print("8. CSS done")
else:
    print("8. CSS NOT FOUND")

with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "w", encoding="utf-8") as f:
    f.write(content)
print("ALL DONE")
