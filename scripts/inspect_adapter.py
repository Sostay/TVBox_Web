with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

# Let's see what providers panAdapter has:
# Look for panAdapter definition
idx = text.find('class ')
found_classes = []
while idx != -1:
    end_idx = text.find('{', idx)
    name = text[idx:end_idx].strip()
    if any(k in name.lower() for k in ['pan', 'quark', 'adapter']):
        print(f'Class: {name} at {idx}')
    idx = text.find('class ', idx+6)

# Look for panAdapter methods
idx = text.find('providerId')
print('=== providerId occurrences ===')
while idx != -1:
    print(text[max(0, idx-40):idx+80])
    idx = text.find('providerId', idx+10)
    if idx > 10000000:
        break
