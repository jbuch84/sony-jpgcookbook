package com.github.ma1co.pmcademo.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;

public class MainActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build the UI Menu Programmatically
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(30, 30, 30, 30);

        TextView statusText = new TextView(this);
        statusText.setTextSize(20);
        statusText.setTextColor(Color.WHITE);
        statusText.setGravity(Gravity.CENTER);
        statusText.setText("Alpha OS Dashboard\nReady to Connect");
        statusText.setPadding(0, 0, 0, 40);

        Button btnHome = new Button(this);
        btnHome.setText("1. Connect to Home Wi-Fi");
        btnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Let PMCA's native code handle the network AND the server
                Intent intent = new Intent(MainActivity.this, WifiActivity.class);
                startActivity(intent);
            }
        });

        Button btnHotspot = new Button(this);
        btnHotspot.setText("2. Start Camera Hotspot (Travel)");
        btnHotspot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Let PMCA's native code handle the hotspot AND the server
                Intent intent = new Intent(MainActivity.this, WifiDirectActivity.class);
                startActivity(intent);
            }
        });

        layout.addView(statusText);
        layout.addView(btnHome);
        layout.addView(btnHotspot);

        setContentView(layout);
    }
}