import urllib.request, json, sys

sys.stdout.reconfigure(encoding='utf-8')

# Download first 3 segments
base = 'https://vd.wmvbo.com/0c408e46a6cd9f6e06e8f8b2c2ec01eb/20261001180627/44212568c2d312c1204cd31c/decry/vd/20260910/MzlkODA4Zjc4YzM/125904/1920_1080/aac/h264/hls/decrypt/'
headers = {'User-Agent': 'Lavf/57.83.100', 'Referer': 'http://WJiZxLXA2.com/'}

for seg in ['DFiN-00000.ts', 'DFiN-00001.ts', 'DFiN-00002.ts', 'DFiN-00010.ts']:
    url = base + seg
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as r:
        content = r.read()
        print(f'{seg}: status={r.status}, size={len(content)} bytes')
