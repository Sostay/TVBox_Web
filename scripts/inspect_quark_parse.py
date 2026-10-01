with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

idx = text.find(r'\u5938\u514B\u5206\u4EAB\u94FE\u63A5')
if idx != -1:
    print('Found at', idx)
    print(text[max(0, idx-300):idx+300])
