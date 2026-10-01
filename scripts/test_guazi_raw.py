import urllib.request, json, sys

sys.stdout.reconfigure(encoding='utf-8')

# Call play for 第1期中
vod_d_id = '150255'
# From our previous script:
# param: vod_d_id=150255&vurl_id=4543598&domain_type=8&resolution=1080&type=play&_client_ts=45310176232500||1080
ep_param = 'vod_d_id=150255&vurl_id=4543598&domain_type=8&resolution=1080&type=play&_client_ts=45310176232500||1080'

play_req = urllib.request.Request('http://127.0.0.1:8999/api/spider/guazi/3/play', data=json.dumps({'flag': '《免费分享》', 'id': ep_param}).encode('utf-8'), headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(play_req) as pr:
    res = json.loads(pr.read().decode('utf-8'))
    print('Play Response:')
    print(json.dumps(res, indent=2, ensure_ascii=False))
