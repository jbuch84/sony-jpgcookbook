package com.github.ma1co.pmcademo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;

import com.sony.wifi.direct.DirectConfiguration;
import com.sony.wifi.direct.DirectManager;

import java.lang.reflect.Method;
import java.util.List;

/**
 * JPEG.CAM Manager: Connectivity & Networking
 * Manages Wi-Fi, Hotspot, and the JPEG.CAM Dashboard server.
 */
public class ConnectivityManager {
    private Context context;
    private WifiManager wifiManager;
    private android.net.ConnectivityManager connManager;
    private DirectManager directManager;
    private HttpServer server;

    private BroadcastReceiver wifiReceiver;
    private BroadcastReceiver directStateReceiver;
    private BroadcastReceiver groupCreateSuccessReceiver;
    private BroadcastReceiver groupCreateFailureReceiver;

    private Handler wifiPollHandler;
    private Runnable wifiPollRunnable;

    private boolean isHomeWifiRunning = false;
    private boolean isHotspotRunning = false;

    private String connStatusHotspot = "Press ENTER to Start";
    private String connStatusWifi = "Press ENTER to Start";

    public interface StatusUpdateListener {
        void onStatusUpdate(String target, String status);
    }

    private StatusUpdateListener listener;

    public ConnectivityManager(Context context, StatusUpdateListener listener) {
        this.context = context;
        this.listener = listener;
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.connManager = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.directManager = (DirectManager) context.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
        this.server = new HttpServer(context);
    }

    public String getConnStatusHotspot() { return connStatusHotspot; }
    public String getConnStatusWifi() { return connStatusWifi; }
    public boolean isHomeWifiRunning() { return isHomeWifiRunning; }
    public boolean isHotspotRunning() { return isHotspotRunning; }

    private void setAutoPowerOffMode(boolean enable) {
        String mode = enable ? "APO/NORMAL" : "APO/NO";
        Intent intent = new Intent();
        intent.setAction("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", mode);
        context.sendBroadcast(intent);
    }

    // --- THE MAGIC FIX: WAKE SONY RTOS VIA REFLECTION ---
    // Prevents the 15-second kernel panic without requiring missing compile-time libraries
    private void wakeUpSonyWifiHardware() {
        try {
            Class<?> nmClass = Class.forName("com.sony.scalar.sysutil.NetworkManager");
            Object nmInstance = nmClass.newInstance();
            Method enableWifi = nmClass.getMethod("enableWifi");
            enableWifi.invoke(nmInstance);
        } catch (Exception e) {
            // Ignore, will naturally fallback to standard Android 
        }
    }

    private void sleepSonyWifiHardware() {
        try {
            Class<?> nmClass = Class.forName("com.sony.scalar.sysutil.NetworkManager");
            Object nmInstance = nmClass.newInstance();
            Method disableWifi = nmClass.getMethod("disableWifi");
            disableWifi.invoke(nmInstance);
        } catch (Exception e) {}
    }

    public void startHomeWifi() {
        stopNetworking(); 
        isHomeWifiRunning = true;
        updateStatus("WIFI", "Waking up hardware...");

        // 1. WAKE UP SONY HARDWARE FIRST
        wakeUpSonyWifiHardware();

        // 2. Delay for 1 second to let the chip boot, then scan normally
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isHomeWifiRunning) return;

                updateStatus("WIFI", "Connecting to Router...");
                
                if (!wifiManager.isWifiEnabled()) {
                    wifiManager.setWifiEnabled(true);
                } else {
                    wifiManager.reconnect();
                }

                wifiPollHandler = new Handler();
                wifiPollRunnable = new Runnable() {
                    int attempts = 0;
                    @Override
                    public void run() {
                        if (!isHomeWifiRunning) return;

                        NetworkInfo info = connManager.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
                        if (info != null && info.isConnected()) {
                            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                            int ip = wifiInfo.getIpAddress();
                            if (ip != 0) {
                                String ipAddress = String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                                updateStatus("WIFI", "http://" + ipAddress + ":" + HttpServer.PORT);
                                startServer();
                                setAutoPowerOffMode(false); 
                                return;
                            }
                        }

                        attempts++;
                        if (attempts > 15) { // 30 seconds total
                            updateStatus("WIFI", "Timed out.");
                            stopNetworking();
                        } else {
                            updateStatus("WIFI", "Searching... (" + attempts + "/15)");
                            wifiPollHandler.postDelayed(this, 2000);
                        }
                    }
                };
                wifiPollHandler.postDelayed(wifiPollRunnable, 2000);
            }
        }, 1000);
    }

    public void startHotspot() {
        stopNetworking(); 
        isHotspotRunning = true;
        updateStatus("HOTSPOT", "Starting Hotspot...");

        // 1. WAKE UP SONY HARDWARE FIRST
        wakeUpSonyWifiHardware();

        // --- GEN 2 LOGIC (A5100) ---
        if (directManager == null) {
            directManager = (DirectManager) context.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
        }

        if (directManager != null) {
            if (!wifiManager.isWifiEnabled()) {
                wifiManager.setWifiEnabled(true);
            }

            directStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getIntExtra(DirectManager.EXTRA_DIRECT_STATE, DirectManager.DIRECT_STATE_UNKNOWN) == DirectManager.DIRECT_STATE_ENABLED) {
                        List<DirectConfiguration> configs = directManager.getConfigurations();
                        if (configs != null && !configs.isEmpty()) {
                            directManager.startGo(configs.get(configs.size() - 1).getNetworkId());
                        } else {
                            updateStatus("HOTSPOT", "Error: No Configs");
                            stopNetworking();
                        }
                    }
                }
            };
            
            groupCreateSuccessReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    DirectConfiguration config = intent.getParcelableExtra(DirectManager.EXTRA_DIRECT_CONFIG);
                    if (config != null) {
                        String password = "N/A";
                        try {
                            Method getPassphrase = config.getClass().getMethod("getPassphrase");
                            password = (String) getPassphrase.invoke(config);
                        } catch (Exception e1) {
                            try {
                                Method getPassword = config.getClass().getMethod("getPassword");
                                password = (String) getPassword.invoke(config);
                            } catch (Exception e2) {
                                password = "Error";
                            }
                        }
                        updateStatus("HOTSPOT", "PW: " + password + " (192.168.122.1)");
                        startServer();
                        setAutoPowerOffMode(false); 
                    }
                }
            };

            groupCreateFailureReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateStatus("HOTSPOT", "Hardware Error: Retry");
                    stopNetworking();
                }
            };
            
            context.registerReceiver(directStateReceiver, new IntentFilter(DirectManager.DIRECT_STATE_CHANGED_ACTION));
            context.registerReceiver(groupCreateSuccessReceiver, new IntentFilter(DirectManager.GROUP_CREATE_SUCCESS_ACTION));
            context.registerReceiver(groupCreateFailureReceiver, new IntentFilter(DirectManager.GROUP_CREATE_FAILURE_ACTION));

            directManager.setDirectEnabled(true);
            return;
        }

        // --- GEN 3 LOGIC (A7II, A6500) FALLBACK ---
        try {
            if (wifiManager.isWifiEnabled()) {
                wifiManager.setWifiEnabled(false);
            }

            Method setWifiApEnabled = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            boolean success = (Boolean) setWifiApEnabled.invoke(wifiManager, null, true);
            
            if (success) {
                Method getWifiApConfiguration = wifiManager.getClass().getMethod("getWifiApConfiguration");
                WifiConfiguration apConfig = (WifiConfiguration) getWifiApConfiguration.invoke(wifiManager);
                
                if (apConfig != null) {
                    updateStatus("HOTSPOT", "PW: " + apConfig.preSharedKey + " (192.168.43.1)");
                } else {
                    updateStatus("HOTSPOT", "Connect Phone (192.168.43.1)");
                }
                startServer();
                setAutoPowerOffMode(false); 
            } else {
                updateStatus("HOTSPOT", "Hardware Unsupported");
                isHotspotRunning = false;
            }
        } catch (Exception e) {
            updateStatus("HOTSPOT", "Error: " + e.getMessage());
            isHotspotRunning = false;
        }
    }

    private void unregisterReceiverSafe(BroadcastReceiver receiver) {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception e) {
                // Ignore if it was never fully registered
            }
        }
    }

    public void stopNetworking() {
        if (server != null && server.isAlive()) server.stop();
        
        if (wifiPollHandler != null && wifiPollRunnable != null) {
            wifiPollHandler.removeCallbacks(wifiPollRunnable);
            wifiPollHandler = null;
            wifiPollRunnable = null;
        }

        unregisterReceiverSafe(wifiReceiver);
        wifiReceiver = null;
        unregisterReceiverSafe(directStateReceiver);
        directStateReceiver = null;
        unregisterReceiverSafe(groupCreateSuccessReceiver);
        groupCreateSuccessReceiver = null;
        unregisterReceiverSafe(groupCreateFailureReceiver);
        groupCreateFailureReceiver = null;

        if (isHomeWifiRunning) {
            try { wifiManager.disconnect(); } catch (Exception e) {}
            isHomeWifiRunning = false;
        }
        
        if (isHotspotRunning) {
            try { if (directManager != null) directManager.setDirectEnabled(false); } catch (Exception e) {}
            try {
                Method setWifiApEnabled = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                setWifiApEnabled.invoke(wifiManager, null, false);
            } catch (Exception e) {}

            isHotspotRunning = false;
        }
        
        // Power down the hardware antenna
        sleepSonyWifiHardware();

        updateStatus("WIFI", "Press ENTER to Start");
        updateStatus("HOTSPOT", "Press ENTER to Start");
        setAutoPowerOffMode(true); 
    }

    private void startServer() {
        try { if (!server.isAlive()) server.start(); } catch (Exception e) {}
    }

    private void updateStatus(String target, String status) {
        if ("HOTSPOT".equals(target)) connStatusHotspot = status;
        else connStatusWifi = status;
        if (listener != null) listener.onStatusUpdate(target, status);
    }
}