import urllib.request, json, sys

sys.stdout.reconfigure(encoding='utf-8')

req = urllib.request.Request('http://127.0.0.1:8999/api/spider/wogg/3/detail', data=json.dumps({'id': '/voddetail/132056.html'}).encode('utf-8'), headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(req) as r:
    d = json.loads(r.read().decode('utf-8'))['list'][0]
    raw = d.get('vod_play_url')
    print('Length of raw vod_play_url:', len(raw))
    groups = raw.split('$$$')
    print('Total groups (lines):', len(groups))
    for i, g in enumerate(groups):
        print(f"Group {i} length: {len(g)}, count of $: {g.count('$')}, count of #: {g.count('#')}")
        if '$' in g:
            p = g.split('$', 1)
            print(f"  name: {p[0][:50]}, id length: {len(p[1])}")
            # Call play!
            flag = d.get('vod_play_from').split('$$$')[i]
            play_req = urllib.request.Request('http://127.0.0.1:8999/api/spider/wogg/3/play', data=json.dumps({'flag': flag, 'id': p[1]}).encode('utf-8'), headers={'Content-Type': 'application/json'})
            try:
                with urllib.request.urlopen(play_req, timeout=15) as play_r:
                    play_data = json.loads(play_r.read().decode('utf-8'))
                    print(f"  PLAY SUCCESS for line {flag}:")
                    print(f"    url: {play_data.get('url')[:100]}...")
                    print(f"    header: {play_data.get('header')}")
            except Exception as e:
                print(f"  Play failed: {e}")
            break
