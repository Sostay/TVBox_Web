with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

idx = text.find('function ZVt')
if idx != -1:
    print('Found ZVt at', idx)
    print(text[idx:idx+600])
