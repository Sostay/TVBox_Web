import urllib.request, urllib.parse, json

m3u8_url = 'https://vd.wmvbo.com/0c408e46a6cd9f6e06e8f8b2c2ec01eb/20261001180627/44212568c2d312c1204cd31c/decry/vd/20260910/MzlkODA4Zjc4YzM/125904/1920_1080/aac/h264/hls/decrypt/index.m3u8'
custom_headers = json.dumps({"User-Agent": "Lavf/57.83.100", "Referer": "http://WJiZxLXA2.com/"})
stream_url = f'http://127.0.0.1:8999/api/stream?url={urllib.parse.quote(m3u8_url)}&headers={urllib.parse.quote(custom_headers)}'

req = urllib.request.Request(stream_url)
with urllib.request.urlopen(req) as r:
    content = r.read().decode('utf-8')
    print('Status:', r.status)
    print('Content-Type:', r.headers.get('Content-Type'))
    lines = content.split('\n')
    print(f'Total lines: {len(lines)}')
    for line in lines[:10]:
        print(' ', line)

    # Now let's fetch the first segment through gateway!
    first_seg_path = lines[7].strip() # /api/stream?url=...
    print('\nFirst segment path:', first_seg_path[:100])
    seg_url = f'http://127.0.0.1:8999{first_seg_path}'
    seg_req = urllib.request.Request(seg_url)
    try:
        with urllib.request.urlopen(seg_req, timeout=10) as sr:
            print('Segment Status:', sr.status)
            print('Segment Content-Length:', sr.headers.get('Content-Length'))
            seg_bytes = sr.read(100)
            print('Segment first 16 bytes:', seg_bytes[:16])
    except Exception as e:
        print('Segment fetch failed:', e)
