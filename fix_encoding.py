with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "rb") as f:
    data = bytearray(f.read())

def fix_bytes(data, marker, old_corrupted, new_correct, label):
    idx = data.find(marker)
    if idx < 0:
        print(f"{label}: marker not found")
        return False
    pos = idx + len(marker)
    actual = data[pos:pos+len(old_corrupted)]
    if actual == old_corrupted:
        data[pos:pos+len(old_corrupted)] = new_correct
        print(f"{label}: fixed at {pos}, old={actual.hex()}, new={new_correct.hex()}")
        return True
    elif actual == new_correct[:len(old_corrupted)]:
        print(f"{label}: already correct at {pos}")
        return True
    else:
        print(f"{label}: unexpected bytes {actual.hex()} at {pos}")
        return False

fix_bytes(data,
    b"\\xe8\\xaf\\xb7\\xe8\\xbe\\x93\\xe5\\x85\\xa5\\xe6\\x98\\xb5",
    b"\\xe7\\xa7\\xb0\\x22\\x20",
    b"\\xe7\\xa7\\xb0\\x22\\x20\\x73",
    "Fix 1")

fix_bytes(data,
    b"\\xe4\\xb8\\xaa\\xe4\\xba\\xba\\xe7\\xae\\x80",
    b"\\xe4\\xbb\\x8b\\x3e\\x0d",
    b"\\xe4\\xbb\\x8b\\x22\\x3e\\x0d",
    "Fix 2")

fix_bytes(data,
    b"\\xe5\\x86\\x99\\xe7\\x82\\xb9\\xe4\\xbb\\x80\\xe4\\xb9\\x88\\xe4\\xbb\\x8b\\xe7\\xbb\\x8d\\xe8\\x87\\xaa",
    b"\\xe5\\xb7\\xb1\\x22",
    b"\\xe5\\xb7\\xb1\\x22\\x20",
    "Fix 3")

with open("ai-generate-code-frontend/src/page/User/UserSettings.vue", "wb") as f:
    f.write(data)
print("Script complete.")
