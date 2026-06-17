with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "r", encoding="utf-8") as f:
    content = f.read()

# Check step 1
s1 = "import { appApplyListMyHistoryUsingPost, appApplyUsingPost } from \"@/api/appController\""
idx = content.find(s1)
if idx >= 0:
    print(f"S1 found at {idx}: {content[idx:idx+80]!r}")
else:
    print("S1 NOT FOUND")

# Check step 5
s5 = "profileLoading.value = false\n  }\n}"
idx = content.find(s5)
if idx >= 0:
    after = content[idx+len(s5):idx+len(s5)+20]
    print(f"S5 found, after closing: {after!r}")
else:
    print("S5 NOT FOUND")

# Check step 6
s6 = "  loadApplyList()"
idx = content.find(s6)
if idx >= 0:
    after = content[idx+len(s6):idx+len(s6)+10]
    print(f"S6 found, after: {after!r}")
else:
    print("S6 NOT FOUND")

# Check step 8
s8 = "}</style>"
idx = content.find(s8)
if idx >= 0:
    before = content[idx-10:idx+10]
    print(f"S8 found: ...{before!r}...")
else:
    print("S8 NOT FOUND")
