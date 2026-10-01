from PIL import Image
import numpy as np

img0 = Image.open('D:\\DS_Harness\\TVBox_Web\\frame0.jpg')
img2 = Image.open('D:\\DS_Harness\\TVBox_Web\\frame2.jpg')
img10 = Image.open('D:\\DS_Harness\\TVBox_Web\\frame10.jpg')

arr0 = np.array(img0)
arr2 = np.array(img2)
arr10 = np.array(img10)

print('img0 mean color:', arr0.mean(axis=(0,1)))
print('img2 mean color:', arr2.mean(axis=(0,1)))
print('img10 mean color:', arr10.mean(axis=(0,1)))

diff0_2 = np.abs(arr0.astype(int) - arr2.astype(int)).mean()
diff2_10 = np.abs(arr2.astype(int) - arr10.astype(int)).mean()
print('Mean pixel diff between frame0 and frame2:', diff0_2)
print('Mean pixel diff between frame2 and frame10:', diff2_10)
