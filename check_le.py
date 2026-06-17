with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "rb") as f:
    data = f.read()
lf = data.count(b"\x0a")
crlf = data.count(b"\x0d\x0a")
print("LF:", lf, "CRLF:", crlf)
# Check import line
idx = data.find(b"import { appApplyListMyHistoryUsingPost")
print("Import found at:", idx)
if idx > 0:
    end = data.find(b"\x0a", idx)
    print("Line ending at:", end, "byte:", hex(data[end]) if end > 0 else "N/A")
    print("Line:", data[idx:end+1])
