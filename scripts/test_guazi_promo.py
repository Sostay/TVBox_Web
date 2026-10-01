import urllib.request, json, sys

sys.stdout.reconfigure(encoding='utf-8')

# Search for 花儿与少年
search_req = urllib.request.Request('http://127.0.0.1:8999/api/spider/guazi/3/search', data=json.dumps({'wd': '花儿与少年'}).encode('utf-8'), headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(search_req) as r:
    data = json.loads(r.read().decode('utf-8'))
    items = data.get('list', [])

print(f'Search returned {len(items)} items:')
target_item = None
for item in items:
    print(' ', item.get('vod_name'), item.get('vod_id'))
    if '花儿与少年' in item.get('vod_name', ''):
        target_item = item
        break

if not target_item and items:
    target_item = items[0]

if target_item:
    vod_id = target_item.get('vod_id')
    print(f"\nFetching detail for {target_item.get('vod_name')} ({vod_id})...")
    det_req = urllib.request.Request('http://127.0.0.1:8999/api/spider/guazi/3/detail', data=json.dumps({'id': vod_id}).encode('utf-8'), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(det_req) as r:
        det_data = json.loads(r.read().decode('utf-8'))
        d = det_data['list'][0]
        froms = d.get('vod_play_from', '').split('$$$')
        urls = d.get('vod_play_url', '').split('$$$')
        print(f"Lines ({len(froms)}):", froms)
        first_line_eps = urls[0].split('#')
        print(f"Total episodes in line 0: {len(first_line_eps)}")
        
        # Test first episode
        first_ep = first_line_eps[0].split('$')
        print('Testing first episode:', first_ep[0], 'id:', first_ep[1][:60])

        play_req = urllib.request.Request('http://127.0.0.1:8999/api/spider/guazi/3/play', data=json.dumps({'flag': froms[0], 'id': first_ep[1]}).encode('utf-8'), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(play_req) as play_r:
            play_res = json.loads(play_r.read().decode('utf-8'))
            print('\nPlay response:')
            print('  URL:', play_res.get('url'))
            print('  Header:', play_res.get('header'))

            # Fetch the first 50 lines of m3u8
            m3u8_url = play_res.get('url')
            headers = play_res.get('header', {})
            m_req = urllib.request.Request(m3u8_url, headers=headers)
            try:
                with urllib.request.urlopen(m_req, timeout=10) as mr:
                    m_content = mr.read().decode('utf-8')
                    print('\nM3U8 content head (first 500 chars):')
                    print(m_content[:500])
                    # Total duration
                    import re
                    durations = re.findall(r'#EXTINF:([\d.]+)', m_content)
                    total_dur = sum(float(x) for x in durations)
                    print(f'Total M3U8 duration: {total_dur:.1f} seconds, segment count: {len(durations)}')
            except Exception as e:
                print('Fetch m3u8 error:', e)
