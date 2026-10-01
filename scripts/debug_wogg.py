import sys

sys.stdout.reconfigure(encoding='utf-8')

with open('D:\\DS_Harness\\TVBox_Web\\index.js', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

# Search for wogg spider definition
pos = text.find('key:"wogg"')
if pos != -1:
    print(f"=== Found key:'wogg' at pos {pos} ===")
    print(text[max(0, pos-200):pos+1200])
else:
    print("key:'wogg' not found")
