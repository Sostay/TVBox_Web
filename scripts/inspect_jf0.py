with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

idx = text.find('function jf0')
if idx != -1:
    print('Found jf0 at', idx)
    print(text[idx:idx+800])

idx2 = text.find('async function jf0')
if idx2 != -1:
    print('Found async jf0 at', idx2)
    print(text[idx2:idx2+800])
