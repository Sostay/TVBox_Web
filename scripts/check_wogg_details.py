import urllib.request, json, sys

sys.stdout.reconfigure(encoding='utf-8')

# Get home list of wogg
req = urllib.request.Request('http://127.0.0.1:8999/api/spider/wogg/3/home', data=b'{}', headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(req) as r:
    data = json.loads(r.read().decode('utf-8'))
    items = data.get('list', [])

print(f'Checking {len(items[:15])} items from Wogg:')
for item in items[:15]:
    vod_id = item.get('vod_id')
    vod_name = item.get('vod_name')
    det_req = urllib.request.Request('http://127.0.0.1:8999/api/spider/wogg/3/detail', data=json.dumps({'id': vod_id}).encode('utf-8'), headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(det_req, timeout=10) as det_r:
            det_data = json.loads(det_r.read().decode('utf-8'))
            d_list = det_data.get('list', [])
            if not d_list:
                print(f'  [EMPTY LIST] {vod_name} ({vod_id}) -> list is empty!')
            else:
                d = d_list[0]
                froms = d.get('vod_play_from', '')
                urls = d.get('vod_play_url', '')
                if not froms or not urls:
                    print(f'  [NO PLAY URL] {vod_name} ({vod_id}) -> froms: "{froms}", urls: "{urls[:30]}"')
                else:
                    print(f'  [OK] {vod_name} -> {froms[:30]}')
    except Exception as e:
        print(f'  [ERROR] {vod_name} ({vod_id}) -> {e}')
