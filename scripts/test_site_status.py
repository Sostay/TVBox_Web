import urllib.request, json, sys

sys.stdout.reconfigure(encoding='utf-8')

req = urllib.request.Request('http://127.0.0.1:9988/config')
with urllib.request.urlopen(req) as r:
    cfg = json.loads(r.read().decode('utf-8'))

sites = cfg.get('video', {}).get('sites', [])
working = []
errored = []

for s in sites[:35]:
    api = s.get('api')
    name = s.get('name')
    url = f'http://127.0.0.1:9988{api}/home'
    r_test = urllib.request.Request(url, data=b'{}', headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r_test, timeout=3) as res:
            data = json.loads(res.read().decode('utf-8'))
            items = len(data.get('list', []))
            working.append((name, api, items))
    except Exception as e:
        errored.append((name, str(e)))

print(f'Working sites ({len(working)}):')
for w in working:
    print(f'  ✓ {w[0]} -> {w[2]} items ({w[1]})')

print(f'\nErrored sites ({len(errored)}):')
for e in errored:
    print(f'  ✗ {e[0]} -> {e[1]}')
