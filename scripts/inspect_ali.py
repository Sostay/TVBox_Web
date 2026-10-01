with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

# Search for "ali" provider or "aliyun"
import re
matches = re.findall(r'(\b(?:ali|alipan|aliyundrive)\w*)\b', text, re.IGNORECASE)
from collections import Counter
counts = Counter(matches)
print("Top matches for ali*:", counts.most_common(20))

# Search for where ali is registered in panAdapter
idx = text.find('panAdapter')
while idx != -1:
    snippet = text[max(0, idx-50):idx+250]
    if 'ali' in snippet.lower():
        print("panAdapter with ali at", idx, ":", snippet)
    idx = text.find('panAdapter', idx+10)
    if idx > 10000000:
        break
