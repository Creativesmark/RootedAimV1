package com.example.rootedaimv1;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivity(intent);
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show();
        }

        Intent accessibilityIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(accessibilityIntent);
        Toast.makeText(this, "Please enable Accessibility Service for RootedAimV1", Toast.LENGTH_LONG).show();

        Intent serviceIntent = new Intent(this, AimbotService.class);
        startForegroundService(serviceIntent);
        finish();
    }
}
