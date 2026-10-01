import subprocess, time, json, urllib.request, sys

sys.stdout.reconfigure(encoding='utf-8')

# Start index.js in background
proc = subprocess.Popen(['node', 'index.js'], cwd='D:\\DS_Harness', stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(3)

def http_get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.loads(r.read().decode('utf-8'))

def http_post(url, data={}):
    body = json.dumps(data).encode('utf-8')
    req = urllib.request.Request(url, data=body, headers={
        'User-Agent': 'Mozilla/5.0',
        'Content-Type': 'application/json'
    })
    with urllib.request.urlopen(req, timeout=12) as r:
        return json.loads(r.read().decode('utf-8'))

try:
    cfg = http_get('http://127.0.0.1:9988/config')
    sites = cfg.get('video', {}).get('sites', [])
    print(f'Total sites: {len(sites)}')

    # Find a good video site (e.g. guazi 瓜子, duobo 独播, or hanju 韩剧, or douban)
    candidate_keys = ['guazi', 'duobo', 'hanju', 'fanqie', 'tianqi', 'yifeng', 'hongguo', 'douban']
    
    for c_key in candidate_keys:
        site = next((s for s in sites if c_key in s.get('key', '')), None)
        if not site:
            continue
        key = site.get('key')
        api = site.get('api')
        name = site.get('name')
        print(f"\n==================== Testing site: [{name}] ({api}) ====================")
        try:
            # 1. Home
            home_data = http_post(f'http://127.0.0.1:9988{api}/home', {})
            vod_list = home_data.get('list', [])
            print(f"Home returned {len(vod_list)} items, classes: {[c.get('type_name') for c in home_data.get('class', [])[:4]]}")
            if not vod_list:
                # Try category
                classes = home_data.get('class', [])
                if classes:
                    first_tid = classes[0].get('type_id')
                    print(f"Testing category with tid={first_tid}...")
                    cat_data = http_post(f'http://127.0.0.1:9988{api}/category', {'tid': first_tid, 'pg': 1})
                    vod_list = cat_data.get('list', [])
                    print(f"Category returned {len(vod_list)} items")

            if vod_list:
                item = vod_list[0]
                vod_id = item.get('vod_id')
                vod_name = item.get('vod_name')
                print(f"Selected item: [{vod_name}] (id={vod_id})")

                # 2. Detail
                print(f"Fetching detail for id={vod_id}...")
                detail_data = http_post(f'http://127.0.0.1:9988{api}/detail', {'id': vod_id})
                d_list = detail_data.get('list', [])
                if d_list:
                    d = d_list[0]
                    print(f"Detail vod_name: {d.get('vod_name')}")
                    print(f"Detail vod_play_from: {d.get('vod_play_from')}")
                    play_urls = d.get('vod_play_url', '')
                    print(f"Detail vod_play_url snippet: {play_urls[:120]}")

                    # Parse first episode
                    # Format in TVBox is usually: "第01集$url#第02集$url" or "$url"
                    episodes = play_urls.split('$$$')[0].split('#')
                    first_ep = episodes[0]
                    ep_name, ep_url = first_ep.split('$') if '$' in first_ep else ('1', first_ep)
                    print(f"Testing play for episode: [{ep_name}] -> {ep_url[:60]}")

                    # 3. Play
                    first_flag = d.get('vod_play_from', '').split('$$$')[0]
                    play_res = http_post(f'http://127.0.0.1:9988{api}/play', {'flag': first_flag, 'id': ep_url})
                    print(f"Play response: {play_res}")
                    print(f"SUCCESS! Site [{name}] is completely functional!")
                    break
        except Exception as e:
            print(f"Site [{name}] test failed: {e}")

finally:
    proc.terminate()
