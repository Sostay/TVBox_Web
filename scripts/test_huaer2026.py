import urllib.request, json, sys, re

sys.stdout.reconfigure(encoding='utf-8')

vod_id = '150255'
print(f"Fetching detail for 花儿与少年2026 ({vod_id})...")
det_req = urllib.request.Request('http://127.0.0.1:8999/api/spider/guazi/3/detail', data=json.dumps({'id': vod_id}).encode('utf-8'), headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(det_req) as r:
    det_data = json.loads(r.read().decode('utf-8'))
    d = det_data['list'][0]
    froms = d.get('vod_play_from', '').split('$$$')
    urls = d.get('vod_play_url', '').split('$$$')
    print(f"Lines ({len(froms)}):", froms)
    
    eps = urls[0].split('#')
    print(f"Total episodes: {len(eps)}")
    for i in [0, 1, 5, 10, len(eps)-1]:
        if i < len(eps):
            p = eps[i].split('$')
            ep_title = p[0]
            ep_param = p[1]
            print(f"\n--- Testing Ep {i}: [{ep_title}] ---")
            print('  param snippet:', ep_param[:80])
            
            play_req = urllib.request.Request('http://127.0.0.1:8999/api/spider/guazi/3/play', data=json.dumps({'flag': froms[0], 'id': ep_param}).encode('utf-8'), headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(play_req) as pr:
                play_res = json.loads(pr.read().decode('utf-8'))
                print('  Play URL:', play_res.get('url'))
                # Fetch m3u8 and check duration
                m_req = urllib.request.Request(play_res.get('url'), headers=play_res.get('header', {}))
                try:
                    with urllib.request.urlopen(m_req, timeout=8) as mr:
                        m_txt = mr.read().decode('utf-8')
                        durations = re.findall(r'#EXTINF:([\d.]+)', m_txt)
                        total_dur = sum(float(x) for x in durations)
                        print(f'  M3U8 duration: {total_dur:.1f}s, segments: {len(durations)}')
                        print('  First segment URL:', m_txt.split('\n')[6:9])
                except Exception as e:
                    print('  Fetch error:', e)
