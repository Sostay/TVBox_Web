import sys, re

sys.stdout.reconfigure(encoding='utf-8')

with open('index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

# Search for route registrations like .get('/' or .post('/'
routes = re.findall(r'\.(?:get|post|put|delete|all)\s*\(\s*[\"\'](/[^\"\']*)[\"\']', text)
print(f'Found {len(routes)} explicit string routes:')
unique_routes = sorted(list(set(routes)))
for r in unique_routes:
    print(' ', r)

# Also search for spider route definitions
idx = 0
while True:
    idx = text.find('/spider', idx)
    if idx == -1:
        break
    print('Spider route context:', text[max(0, idx-40):idx+80])
    idx += 7
    if idx > 10000000:
        break
