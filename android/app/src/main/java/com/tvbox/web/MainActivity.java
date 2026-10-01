package com.tvbox.web;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.tvbox.web.service.WebServerService;
import com.tvbox.web.utils.NetworkUtils;

public class MainActivity extends AppCompatActivity {

    private TextView mTvUrl;
    private Button mBtnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTvUrl = findViewById(R.id.tvUrl);
        mBtnToggle = findViewById(R.id.btnToggle);

        updateAddressDisplay();
        startServerService();

        mBtnToggle.setOnClickListener(v -> {
            restartServerService();
            updateAddressDisplay();
            Toast.makeText(this, "服务已重启", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateAddressDisplay() {
        String ip = NetworkUtils.getLocalIpAddress();
        mTvUrl.setText("http://" + ip + ":" + WebServerService.SERVER_PORT);
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
