package com.github.ma1co.pmcademo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.TextView;

import java.io.IOException;

// CRITICAL FIX: We are now extending BaseActivity so the camera doesn't freeze the power button.
public class WifiActivity extends BaseActivity { 
    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private TextView textView;
    private HttpServer httpServer;
    private BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        textView = new TextView(this);
        textView.setTextSize(20);
        textView.setPadding(30, 30, 30, 30);
        setContentView(textView);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateStatus();
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        
        // Tells the OpenMemories BaseActivity to prevent the camera from falling asleep while hosting the server
        setAutoPowerOffMode(false); 

        if (!wifiManager.isWifiEnabled()) {
            textView.setText("Enabling Wi-Fi...");
            wifiManager.setWifiEnabled(true);
        }
        updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
        
        // Re-enables normal camera sleep behavior when you exit
        setAutoPowerOffMode(true); 
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }

    private void updateStatus() {
        NetworkInfo info = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        if (info != null && info.isConnected()) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ip = wifiInfo.getIpAddress();
            if (ip != 0) {
                String ipAddress = String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                textView.setText("Connected to: " + wifiInfo.getSSID() + "\n\n" +
                                 "Open your PC browser to:\n\n" +
                                 "http://" + ipAddress + ":" + HttpServer.PORT);
                startServer();
            } else {
                textView.setText("Obtaining IP address from your router...");
            }
        } else {
            textView.setText("Searching for home Wi-Fi...\n\n" +
                             "If you haven't connected to your router yet, exit and go to:\n" +
                             "MENU -> Wireless -> Access Point Settings");
            stopServer();
        }
    }

    private void startServer() {
        if (httpServer == null) {
            httpServer = new HttpServer(this);
            try {
                httpServer.start();
            } catch (IOException e) {
                textView.setText("Server error: " + e.getMessage());
            }
        }
    }

    private void stopServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }
}