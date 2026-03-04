package com.example.caposbackground;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Foreground service that runs the HTTP server in the background so it can receive
 * requests even when the app is not in the foreground.
 */
public class HttpServerForegroundService extends Service {

    private static final String TAG = "HttpServerService";
    /**
     * Current service instance when running. Set when the service is created and when the server starts;
     * cleared in onDestroy. Use {@link #getInstance()} to get from outside this class.
     */
    static volatile HttpServerForegroundService instance;

    /**
     * Returns the running service instance, or null if the service is not running.
     * Call from MainActivity or any component that needs the server URL or running state.
     */
    public static HttpServerForegroundService getInstance() {
        return instance;
    }

    public static final String CHANNEL_ID = "http_server_channel";
    public static final int NOTIFICATION_ID = 1001;

    private static final long PRINT_POLL_INTERVAL_MS = 8000L;

    private final IBinder binder = new LocalBinder();
    private MobileHttpServer httpServer;
    private int port = MobileHttpServer.DEFAULT_PORT;
    private boolean isRunning;

    private final Handler printPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable printPollRunnable = this::pollDatabaseAndEnqueuePrintJobs;
    private ReceiptDbHelper receiptDbHelper;

    public class LocalBinder extends Binder {
        HttpServerForegroundService getService() {
            return HttpServerForegroundService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "onCreate");
        createNotificationChannel();
        // Must call startForeground() immediately so the service is not killed (Android 8+)
        Notification notification = buildNotification(getString(R.string.service_running));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand action=" + (intent != null ? intent.getAction() : "null"));
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        instance = this;
        // Start the HTTP server (when user tapped "Start HTTP server" or service was (re)started)
        startServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopServer();
        Intent stopped = new Intent(ACTION_SERVER_STOPPED);
        stopped.setPackage(getPackageName());
        sendBroadcast(stopped);
        stopForeground(true);
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.channel_description));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, HttpServerForegroundService.class);
        stopIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_delete, getString(R.string.stop_server), stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    /** Broadcast action sent when the HTTP server has successfully started. */
    public static final String ACTION_SERVER_STARTED = "com.example.caposbackground.SERVER_STARTED";
    /** Broadcast action sent when the HTTP server has stopped. */
    public static final String ACTION_SERVER_STOPPED = "com.example.caposbackground.SERVER_STOPPED";
    /** Intent extra: server URL (e.g. http://192.168.1.10:8080). */
    public static final String EXTRA_SERVER_URL = "server_url";
    /** Intent action to stop the service (e.g. from notification). */
    public static final String ACTION_STOP_SERVICE = "com.example.caposbackground.STOP_SERVICE";
    /** Intent action to start the HTTP server (use this when starting the service from the app). */
    public static final String ACTION_START_SERVER = "com.example.caposbackground.START_SERVER";

    private void startServer() {
        if (httpServer != null) {
            Log.d(TAG, "startServer: already running");
            return;
        }
        Log.d(TAG, "startServer: starting HTTP server on port " + port);
        try {
            httpServer = new MobileHttpServer(this, port);
            httpServer.start(30000);
            isRunning = true;
            if (receiptDbHelper == null) receiptDbHelper = new ReceiptDbHelper(this);
            startPrintPoll();
            String url = getServerUrl();
            Log.i(TAG, "HTTP server started on port " + port + " " + getLocalIpAddress());
            Intent broadcast = new Intent(ACTION_SERVER_STARTED);
            broadcast.setPackage(getPackageName());
            broadcast.putExtra(EXTRA_SERVER_URL, url);
            sendBroadcast(broadcast);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
            isRunning = false;
        }
    }

    private void stopServer() {
        stopPrintPoll();
        if (httpServer != null) {
            try {
                httpServer.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping server", e);
            }
            httpServer = null;
        }
        isRunning = false;
    }

    /** Poll DB every 8 sec; enqueue any existing receipts for printing. */
    private void pollDatabaseAndEnqueuePrintJobs() {
        try {
            PrintQueueManager queue = PrintQueueManager.getInstance(this);
            queue.reloadPrinterConfig(); // reload IP list from external file every 8 sec
            if (receiptDbHelper != null) {
                java.util.List<PendingReceipt> pending = receiptDbHelper.getAllPending();
                if (!pending.isEmpty()) {
                    Log.d(TAG, "Print poll: found " + pending.size() + " pending receipt(s) in SQL, enqueueing for print");
                    for (PendingReceipt r : pending) {
                        queue.enqueueFromPending(r);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Print poll failed", e);
        } finally {
            if (isRunning) {
                printPollHandler.postDelayed(printPollRunnable, PRINT_POLL_INTERVAL_MS);
            }
        }
    }

    private void startPrintPoll() {
        printPollHandler.removeCallbacks(printPollRunnable);
        printPollHandler.postDelayed(printPollRunnable, PRINT_POLL_INTERVAL_MS);
        Log.d(TAG, "Print poll started (every " + (PRINT_POLL_INTERVAL_MS / 1000) + " sec)");
    }

    private void stopPrintPoll() {
        printPollHandler.removeCallbacks(printPollRunnable);
        Log.d(TAG, "Print poll stopped");
    }

    /**
     * Get the device's local IP address (e.g. on WiFi) for displaying the server URL.
     */
    public String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP", e);
        }
        return "127.0.0.1";
    }

    public boolean isRunning() {
        return isRunning && httpServer != null && httpServer.isAlive();
    }

    public int getPort() {
        return port;
    }

    /**
     * Returns the base URL clients can use to reach this server (e.g. http://192.168.1.10:8080).
     */
    public String getServerUrl() {
        return "http://" + getLocalIpAddress() + ":" + getPort();
    }
}
