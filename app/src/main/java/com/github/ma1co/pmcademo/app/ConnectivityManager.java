package com.github.ma1co.pmcademo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.List;

/**
 * JPEG.CAM Manager: Connectivity & Networking
 * Manages Wi-Fi, Hotspot, and the JPEG.CAM Dashboard server.
 */
public class ConnectivityManager {
    private Context context;
    private WifiManager wifiManager;
    private android.net.ConnectivityManager connManager;
    private HttpServer server;

    private BroadcastReceiver wifiReceiver;
    private BroadcastReceiver directStateReceiver;
    private BroadcastReceiver groupCreateSuccessReceiver;
    private BroadcastReceiver groupCreateFailureReceiver;
    
    // Gen 3 P2P Properties
    private BroadcastReceiver p2pReceiver; 
    private Object p2pManager;
    private Object p2pChannel;

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

    public void startHomeWifi() {
        stopNetworking(); 
        isHomeWifiRunning = true;
        updateStatus("WIFI", "Connecting to Router...");
        
        wifiReceiver = new BroadcastReceiver() {
            int attempts = 0; 
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isHomeWifiRunning) return;
                String action = intent.getAction();
                
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    if (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED) {
                        wifiManager.reconnect(); 
                    }
                } else if (android.net.ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                    NetworkInfo info = connManager.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
                    if (info != null && info.isConnected()) {
                        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        int ip = wifiInfo.getIpAddress();
                        if (ip != 0) {
                            String ipAddress = String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                            updateStatus("WIFI", "http://" + ipAddress + ":" + HttpServer.PORT);
                            startServer();
                            setAutoPowerOffMode(false); 
                        }
                    } else {
                        attempts++;
                        if (attempts > 30) {
                            updateStatus("WIFI", "Timed out.");
                            stopNetworking();
                        } else {
                            updateStatus("WIFI", "Searching for network...");
                        }
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        context.registerReceiver(wifiReceiver, filter);
        
        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);
        else wifiManager.reconnect();
    }

    public void startHotspot() {
        stopNetworking(); 
        isHotspotRunning = true;
        updateStatus("HOTSPOT", "Initializing...");

        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);

        try {
            // 1. Try Gen 2 Logic (A5100) using 100% Reflection to prevent Dalvik VerifyErrors on Gen 3
            final Object directMgr = context.getSystemService("wifi-direct");
            if (directMgr != null) {
                // Dynamically load the Sony class and extract its proprietary constants
                Class<?> dmClass = Class.forName("com.sony.wifi.direct.DirectManager");
                
                final String ACTION_STATE_CHANGED = (String) dmClass.getField("DIRECT_STATE_CHANGED_ACTION").get(null);
                final String ACTION_SUCCESS = (String) dmClass.getField("GROUP_CREATE_SUCCESS_ACTION").get(null);
                final String ACTION_FAILURE = (String) dmClass.getField("GROUP_CREATE_FAILURE_ACTION").get(null);
                
                final String EXTRA_STATE = (String) dmClass.getField("EXTRA_DIRECT_STATE").get(null);
                final String EXTRA_CONFIG = (String) dmClass.getField("EXTRA_DIRECT_CONFIG").get(null);
                
                final int STATE_ENABLING = dmClass.getField("DIRECT_STATE_ENABLING").getInt(null);
                final int STATE_ENABLED = dmClass.getField("DIRECT_STATE_ENABLED").getInt(null);

                directStateReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int state = intent.getIntExtra(EXTRA_STATE, -1);
                        if (state == STATE_ENABLING) {
                            updateStatus("HOTSPOT", "Enabling Direct...");
                        } else if (state == STATE_ENABLED) {
                            try {
                                List<?> configs = (List<?>) directMgr.getClass().getMethod("getConfigurations").invoke(directMgr);
                                if (configs != null && !configs.isEmpty()) {
                                    updateStatus("HOTSPOT", "Creating Group...");
                                    Object lastConfig = configs.get(configs.size() - 1);
                                    int networkId = (Integer) lastConfig.getClass().getMethod("getNetworkId").invoke(lastConfig);
                                    directMgr.getClass().getMethod("startGo", int.class).invoke(directMgr, networkId);
                                } else {
                                    updateStatus("HOTSPOT", "Error: No Configs");
                                    stopNetworking();
                                }
                            } catch (Exception e) {}
                        }
                    }
                };
                
                groupCreateSuccessReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        try {
                            Object config = intent.getParcelableExtra(EXTRA_CONFIG);
                            if (config != null) {
                                String pass = (String) config.getClass().getMethod("getPreSharedKey").invoke(config);
                                updateStatus("HOTSPOT", "PW: " + pass + " (192.168.122.1)");
                                startServer();
                                setAutoPowerOffMode(false); 
                            }
                        } catch(Exception e) {}
                    }
                };

                groupCreateFailureReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        updateStatus("HOTSPOT", "Group Creation Failed");
                        stopNetworking();
                    }
                };
                
                context.registerReceiver(directStateReceiver, new IntentFilter(ACTION_STATE_CHANGED));
                context.registerReceiver(groupCreateSuccessReceiver, new IntentFilter(ACTION_SUCCESS));
                context.registerReceiver(groupCreateFailureReceiver, new IntentFilter(ACTION_FAILURE));

                directMgr.getClass().getMethod("setDirectEnabled", boolean.class).invoke(directMgr, true);
                return;
            }

            // 2. Try Gen 3 Logic (A7II, A6500) via standard Android P2P Manager
            p2pManager = context.getSystemService("wifip2p");
            if (p2pManager != null) {
                updateStatus("HOTSPOT", "Waking P2P Radio...");

                Class<?> p2pClass = Class.forName("android.net.wifi.p2p.WifiP2pManager");
                Class<?> channelListenerClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$ChannelListener");
                final Class<?> actionListenerClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$ActionListener");
                final Class<?> channelClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$Channel");

                p2pChannel = p2pClass.getMethod("initialize", Context.class, android.os.Looper.class, channelListenerClass)
                                     .invoke(p2pManager, context, context.getMainLooper(), null);

                final java.lang.reflect.Method createGroupMethod = p2pClass.getMethod("createGroup", channelClass, actionListenerClass);

                p2pReceiver = new BroadcastReceiver() {
                    boolean groupRequested = false;
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("android.net.wifi.p2p.STATE_CHANGE".equals(action)) {
                            int state = intent.getIntExtra("wifi_p2p_state", 1);
                            if (state == 2 && !groupRequested) { // 2 = ENABLED
                                groupRequested = true;
                                try {
                                    updateStatus("HOTSPOT", "Building Group...");
                                    createGroupMethod.invoke(p2pManager, p2pChannel, null);
                                } catch (Exception e) {}
                            }
                        } else if ("android.net.wifi.p2p.CONNECTION_STATE_CHANGE".equals(action)) {
                            NetworkInfo info = intent.getParcelableExtra("networkInfo");
                            if (info != null && info.isConnected()) {
                                Object group = intent.getParcelableExtra("p2pGroupInfo");
                                if (group != null) {
                                    try {
                                        String pass = (String) group.getClass().getMethod("getPassphrase").invoke(group);
                                        updateStatus("HOTSPOT", "PW: " + pass + " (192.168.122.1)");
                                        startServer();
                                        setAutoPowerOffMode(false); 
                                    } catch (Exception e) {}
                                }
                            }
                        }
                    }
                };

                IntentFilter filter = new IntentFilter();
                filter.addAction("android.net.wifi.p2p.STATE_CHANGE");
                filter.addAction("android.net.wifi.p2p.CONNECTION_STATE_CHANGE");
                context.registerReceiver(p2pReceiver, filter);

                // BYPASS: If Wi-Fi is already on, skip the wait and trigger immediately
                if (wifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED) {
                    try {
                        updateStatus("HOTSPOT", "Building Group...");
                        createGroupMethod.invoke(p2pManager, p2pChannel, null);
                    } catch (Exception e) {}
                }
                
                return;
            }

            updateStatus("HOTSPOT", "Hardware Unsupported");
            isHotspotRunning = false;

        } catch (Exception e) {
            updateStatus("HOTSPOT", "Error: " + e.getMessage());
            isHotspotRunning = false;
        }
    }

    public void stopNetworking() {
        if (server != null && server.isAlive()) server.stop();
        
        if (wifiReceiver != null) {
            try { context.unregisterReceiver(wifiReceiver); wifiReceiver = null; } catch (Exception e) {}
        }
        
        try { wifiManager.disconnect(); } catch (Exception e) {}
        
        if (isHomeWifiRunning) {
            isHomeWifiRunning = false;
        }
        
        if (isHotspotRunning) {
            // Gen 2 Cleanup (Dynamic)
            try { context.unregisterReceiver(directStateReceiver); } catch (Exception e) {}
            try { context.unregisterReceiver(groupCreateSuccessReceiver); } catch (Exception e) {}
            try { context.unregisterReceiver(groupCreateFailureReceiver); } catch (Exception e) {}
            try {
                Object directMgr = context.getSystemService("wifi-direct");
                if (directMgr != null) directMgr.getClass().getMethod("setDirectEnabled", boolean.class).invoke(directMgr, false);
            } catch (Exception e) {}
            
            // Gen 3 Cleanup 
            if (p2pReceiver != null) {
                try { context.unregisterReceiver(p2pReceiver); p2pReceiver = null; } catch (Exception e) {}
            }
            try {
                if (p2pManager != null && p2pChannel != null) {
                    Class<?> p2pClass = Class.forName("android.net.wifi.p2p.WifiP2pManager");
                    Class<?> channelClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$Channel");
                    Class<?> actionListenerClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$ActionListener");
                    p2pClass.getMethod("removeGroup", channelClass, actionListenerClass).invoke(p2pManager, p2pChannel, null);
                }
            } catch (Exception e) {}

            isHotspotRunning = false;
        }
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