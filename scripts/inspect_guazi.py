with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

idx = text.find('key:"guazi"')
if idx != -1:
    print('Found guazi at', idx)
    print(text[max(0, idx-100):idx+1200])
else:
    print('key:"guazi" not found')
