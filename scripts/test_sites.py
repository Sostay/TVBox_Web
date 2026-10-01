import subprocess, time, json, urllib.request, sys

sys.stdout.reconfigure(encoding='utf-8')

proc = subprocess.Popen(['node', 'index.js'], cwd='D:\\DS_Harness', stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(3)

def test_site(api, name):
    url = f'http://127.0.0.1:9988{api}/home'
    req = urllib.request.Request(url, data=b'{}', headers={'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0'})
    try:
        with urllib.request.urlopen(req, timeout=6) as r:
            data = json.loads(r.read().decode('utf-8'))
            items = [i.get('vod_name') for i in data.get('list', [])[:4]]
            classes = [c.get('type_name') for c in data.get('class', [])[:3]]
            print(f'SUCCESS [{name}]: {len(data.get("list", []))} items, classes={classes} -> {items}')
    except Exception as e:
        print(f'FAIL    [{name}]: {e}')

try:
    req = urllib.request.Request('http://127.0.0.1:9988/config')
    with urllib.request.urlopen(req) as r:
        cfg = json.loads(r.read().decode('utf-8'))

    sites = cfg.get('video', {}).get('sites', [])
    print(f'Testing {len(sites)} sites...')
    for s in sites[:30]:
        test_site(s.get('api'), s.get('name'))
finally:
    proc.terminate()
