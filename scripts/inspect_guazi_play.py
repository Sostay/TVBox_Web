with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

idx = text.find('async play(s){await T_(n),await f7(n);let c=String(s.body?.id||"")')
if idx != -1:
    print('Found play(s) at', idx)
    print(text[idx:idx+2000])
