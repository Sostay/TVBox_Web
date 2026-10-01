import subprocess

# Run ffmpeg to get scene detection or blackdetect or crop
out0 = subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'frame=pkt_pts_time', '-of', 'csv=p=0', 'D:\\DS_Harness\\TVBox_Web\\DFiN-00000.ts'])
lines0 = out0.decode('utf-8').strip().split('\n')
print(f'DFiN-00000.ts has {len(lines0)} frames, duration {float(lines0[-1]) - float(lines0[0]):.2f}s')

out2 = subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'frame=pkt_pts_time', '-of', 'csv=p=0', 'D:\\DS_Harness\\TVBox_Web\\DFiN-00002.ts'])
lines2 = out2.decode('utf-8').strip().split('\n')
print(f'DFiN-00002.ts has {len(lines2)} frames, duration {float(lines2[-1]) - float(lines2[0]):.2f}s')
