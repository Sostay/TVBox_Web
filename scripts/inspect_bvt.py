with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

idx = text.find('PackageName:BVt')
if idx == -1:
    idx = text.find('PackageName:')
print(text[max(0, idx-500):idx+300])
