with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

# Search for quark share resolve function
idx = text.find('https://pan.quark.cn/s/')
while idx != -1:
    print('Found quark share at', idx, ':', text[max(0, idx-50):idx+200])
    idx = text.find('https://pan.quark.cn/s/', idx+10)
    if idx > 10000000:
        break
