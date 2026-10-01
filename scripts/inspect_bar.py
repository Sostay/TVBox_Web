with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

idx = text.find('function bAr')
print('bAr length from 6325787:')
bAr_code = text[idx:idx+40000]

# Search for "quark" or "baidu" inside bAr_code
pos = 0
matches = []
for p in ['ali', 'quark', 'baidu', '115', 'uc', 'pan189']:
    found = bAr_code.find(p)
    matches.append((p, found))

print('Matches in bAr:', matches)

# Find provider card definitions in bAr
pos_quark = bAr_code.find('quark')
if pos_quark != -1:
    print('Context around quark in bAr:')
    print(bAr_code[max(0, pos_quark-200):pos_quark+500])
