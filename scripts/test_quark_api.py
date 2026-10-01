import json, urllib.request, ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

with open('D:\\DS_Harness\\TVBox_Web\\wexfnwconfig.json', 'r', encoding='utf-8') as f:
    cfg = json.load(f)

quark_cookie = cfg.get('pan', {}).get('quark', {}).get('cookie', '')
print('Quark cookie present:', bool(quark_cookie), 'len:', len(quark_cookie))

# Quark get share detail API
share_id = '6954f5b47c32'
api_url = f'https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pwd_id={share_id}'

req = urllib.request.Request(api_url, headers={
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Cookie': quark_cookie,
    'Referer': f'https://pan.quark.cn/s/{share_id}'
})

try:
    with urllib.request.urlopen(req, context=ctx, timeout=10) as r:
        res = json.loads(r.read().decode('utf-8'))
        print('Quark API response code:', res.get('code'), res.get('message'))
        print('Data snippet:', json.dumps(res.get('data', {}), ensure_ascii=False)[:300])
except Exception as e:
    print('Quark API error:', e)
