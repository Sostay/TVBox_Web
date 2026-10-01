package com.tvbox.web;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.tvbox.web.service.WebServerService;
import com.tvbox.web.utils.NetworkUtils;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView mTvUrl;
    private TextView mTvSubIp;
    private Button mBtnCopy;
    private Button mBtnOpenBrowser;
    private Button mBtnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTvUrl = findViewById(R.id.tvUrl);
        mTvSubIp = findViewById(R.id.tvSubIp);
        mBtnCopy = findViewById(R.id.btnCopy);
        mBtnOpenBrowser = findViewById(R.id.btnOpenBrowser);
        mBtnToggle = findViewById(R.id.btnToggle);

        updateAddressDisplay();
        startServerService();

        mBtnCopy.setOnClickListener(v -> {
            String url = mTvUrl.getText().toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("TVBox Web URL", url));
                Toast.makeText(this, "已复制访问地址: " + url, Toast.LENGTH_SHORT).show();
            }
        });

        mBtnOpenBrowser.setOnClickListener(v -> {
            String url = mTvUrl.getText().toString();
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browserIntent);
            } catch (Exception e) {
                Toast.makeText(this, "打开浏览器失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        mBtnToggle.setOnClickListener(v -> {
            restartServerService();
            updateAddressDisplay();
            Toast.makeText(this, "服务已重启", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateAddressDisplay() {
        String mainIp = NetworkUtils.getLocalIpAddress(this);
        String mainUrl = "http://" + mainIp + ":" + WebServerService.SERVER_PORT;
        mTvUrl.setText(mainUrl);

        // List alternative IPs if multiple network interfaces exist
        List<String> allIps = NetworkUtils.getAllIpAddresses();
        StringBuilder sb = new StringBuilder();
        for (String iface : allIps) {
            if (!iface.contains(mainIp)) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(iface);
            }
        }
        if (sb.length() > 0) {
            mTvSubIp.setText("检测到备用接口: " + sb);
        } else {
            mTvSubIp.setText("优先匹配家庭 Wi-Fi 局域网地址");
        }
    }

    private void startServerService() {
        Intent intent = new Intent(this, WebServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void restartServerService() {
        stopService(new Intent(this, WebServerService.class));
        startServerService();
    }
}
