package com.example.caposbackground;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1;
    private static final int STORAGE_PERMISSION_REQUEST = 2;

    /** Server state from broadcasts (works even when getInstance() is null). */
    private boolean serverRunning;
    private String serverUrl;

    private Button buttonToggle;
    private Button buttonCopy;
    private TextView textUrl;
    private TextView labelUrl;
    private TextView status;

    private final BroadcastReceiver serverStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            runOnUiThread(() -> {
                if (HttpServerForegroundService.ACTION_SERVER_STARTED.equals(intent.getAction())) {
                    serverRunning = true;
                    serverUrl = intent.getStringExtra(HttpServerForegroundService.EXTRA_SERVER_URL);
                    if (serverUrl == null) serverUrl = "";
                    updateUi();
                } else if (HttpServerForegroundService.ACTION_SERVER_STOPPED.equals(intent.getAction())) {
                    serverRunning = false;
                    serverUrl = null;
                    updateUi();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        buttonToggle = findViewById(R.id.button_toggle);
        buttonCopy = findViewById(R.id.button_copy);
        textUrl = findViewById(R.id.text_url);
        labelUrl = findViewById(R.id.label_url);
        status = findViewById(R.id.status);

        ensureNotificationPermission();
        ensureStoragePermission();
        buttonToggle.setOnClickListener(v -> toggleServer());
        buttonCopy.setOnClickListener(v -> copyUrlToClipboard());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(HttpServerForegroundService.ACTION_SERVER_STARTED);
        filter.addAction(HttpServerForegroundService.ACTION_SERVER_STOPPED);
        ContextCompat.registerReceiver(this, serverStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(serverStateReceiver);
        } catch (IllegalArgumentException ignored) { /* not registered */ }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            }
        }
    }

    /** Request storage permission so Download/CaposBackground/printer_ips.txt can be read (Android 9 and below). */
    private void ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return; // Android 10+ uses MediaStore, no path permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
        }
    }

    private void toggleServer() {
        if (isServerRunning()) {
            stopServer();
        } else {
            startServer();
        }
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private void startServer() {
        Intent intent = new Intent(this, HttpServerForegroundService.class);
        intent.setAction(HttpServerForegroundService.ACTION_START_SERVER);
        intent.setPackage(getPackageName());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, R.string.service_running, Toast.LENGTH_SHORT).show();
            updateUi();
            scheduleUiRefresh(0, 15);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service", e);
            Toast.makeText(this, "Cannot start service: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Poll until we receive server URL from broadcast (handles getInstance() null). */
    private void scheduleUiRefresh(int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) return;
        uiHandler.postDelayed(() -> {
            updateUi();
            if (!serverRunning) {
                scheduleUiRefresh(attempt + 1, maxAttempts);
            }
        }, 400);
    }

    private void stopServer() {
        stopService(new Intent(this, HttpServerForegroundService.class));
        serverRunning = false;
        serverUrl = null;
        updateUi();
    }

    private boolean isServerRunning() {
        return serverRunning;
    }

    private void updateUi() {
        if (serverRunning && serverUrl != null) {
            buttonToggle.setText(R.string.stop_server);
            textUrl.setText(serverUrl);
            textUrl.setVisibility(View.VISIBLE);
            labelUrl.setVisibility(View.VISIBLE);
            buttonCopy.setVisibility(View.VISIBLE);
            status.setText(R.string.service_running);
        } else {
            buttonToggle.setText(R.string.start_server);
            textUrl.setVisibility(View.GONE);
            labelUrl.setVisibility(View.GONE);
            buttonCopy.setVisibility(View.GONE);
            status.setText(R.string.server_stopped);
        }
    }

    private void copyUrlToClipboard() {
        if (!serverRunning || serverUrl == null) return;
        String url = serverUrl;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Server URL", url));
            Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Notification permission helps show that the server is running.", Toast.LENGTH_LONG).show();
        }
        if (requestCode == STORAGE_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Storage access granted. Printer config can be read from Download/CaposBackground/.", Toast.LENGTH_SHORT).show();
        }
    }
}
