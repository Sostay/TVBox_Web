import urllib.request, json, sys

sys.stdout.reconfigure(encoding='utf-8')

req = urllib.request.Request('http://127.0.0.1:8999/api/spider/wogg/3/home', data=b'{}', headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(req) as r:
    data = json.loads(r.read().decode('utf-8'))
    items = data.get('list', [])

print(f'Testing all {len(items)} items from Wogg home...')
empty_details = []
errors = []

for i, item in enumerate(items):
    vod_id = item.get('vod_id')
    vod_name = item.get('vod_name')
    det_req = urllib.request.Request('http://127.0.0.1:8999/api/spider/wogg/3/detail', data=json.dumps({'id': vod_id}).encode('utf-8'), headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(det_req, timeout=8) as det_r:
            det_data = json.loads(det_r.read().decode('utf-8'))
            d_list = det_data.get('list', [])
            if not d_list:
                empty_details.append((vod_name, vod_id))
    except Exception as e:
        errors.append((vod_name, vod_id, str(e)))

print(f'Total tested: {len(items)}')
print(f'Empty list count: {len(empty_details)}')
for ed in empty_details:
    print(f'  Empty: {ed[0]} ({ed[1]})')

print(f'Errors count: {len(errors)}')
for er in errors:
    print(f'  Error: {er[0]} ({er[1]}): {er[2]}')
