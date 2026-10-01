import subprocess, time, json, sys, os, urllib.request

sys.stdout.reconfigure(encoding='utf-8')

print("Starting node index.js in D:\\DS_Harness ...")
proc = subprocess.Popen(
    ["node", "index.js"],
    cwd="D:\\DS_Harness",
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    encoding="utf-8"
)

# Wait for server to boot
time.sleep(3)

def http_get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=5) as r:
        return r.status, json.loads(r.read().decode('utf-8'))

def http_post(url, data={}):
    body = json.dumps(data).encode('utf-8')
    req = urllib.request.Request(url, data=body, headers={
        'User-Agent': 'Mozilla/5.0',
        'Content-Type': 'application/json'
    })
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.status, json.loads(r.read().decode('utf-8'))

try:
    print("\n--- 1. Testing /health ---")
    st, res = http_get("http://127.0.0.1:9988/health")
    print("Health:", st, res)

    print("\n--- 2. Testing /config ---")
    st, cfg = http_get("http://127.0.0.1:9988/config")
    sites = cfg.get("video", {}).get("sites", [])
    print(f"Total sites: {len(sites)}")
    for s in sites[:10]:
        print(f"  key={s.get('key')} name={s.get('name')} type={s.get('type')} api={s.get('api')}")

    # Test invoking first real site, e.g. douban or wogg
    first_site = sites[0]
    key = first_site.get('key')
    api = first_site.get('api')
    print(f"\n--- 3. Testing first site: key={key}, api={api} ---")
    
    test_urls = [
        f"http://127.0.0.1:9988{api}/home",
        f"http://127.0.0.1:9988/spider/{key}/home",
        f"http://127.0.0.1:9988/spider/{key}/3/home",
    ]
    for u in test_urls:
        try:
            st, data = http_post(u, {})
            print(f"POST {u} -> status {st}")
            print("  Response keys:", list(data.keys()))
            if 'class' in data:
                print("  Classes:", len(data['class']), [c.get('type_name') for c in data['class'][:5]])
            if 'list' in data:
                print("  List items:", len(data['list']), [i.get('vod_name') for i in data['list'][:5]])
            break
        except Exception as e:
            print(f"POST {u} -> error: {e}")

    # Also test wogg (玩偶)
    wogg_site = next((s for s in sites if 'wogg' in s.get('key', '') or '玩偶' in s.get('name', '')), None)
    if wogg_site:
        print(f"\n--- 4. Testing Wogg site: key={wogg_site.get('key')}, api={wogg_site.get('api')} ---")
        w_url = f"http://127.0.0.1:9988{wogg_site.get('api')}/home"
        try:
            st, data = http_post(w_url, {})
            print(f"POST {w_url} -> status {st}")
            print("  Response keys:", list(data.keys()))
            if 'class' in data:
                print("  Classes:", [c.get('type_name') for c in data['class'][:5]])
            if 'list' in data:
                print("  List items:", [i.get('vod_name') for i in data['list'][:5]])
        except Exception as e:
            print("Wogg error:", e)

finally:
    proc.terminate()
    print("\nServer stopped.")
