import sys

sys.stdout.reconfigure(encoding='utf-8')

with open('index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

for target in ['/home', '/website', '/play', '/category', '/detail']:
    pattern = f'"{target}"'
    pos = text.find(pattern)
    if pos != -1:
        print(f'=== Route {target} at {pos} ===')
        print(text[max(0, pos-40):pos+400])
        print('\n')
