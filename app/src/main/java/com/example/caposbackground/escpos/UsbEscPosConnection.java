package com.example.caposbackground.escpos;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens a USB ESC/POS printer (USB class 7, or any device with a bulk OUT endpoint)
 * and exposes it as an {@link OutputStream}.
 */
public final class UsbEscPosConnection {

    private static final String TAG = "UsbEscPos";
    private static final String ACTION_USB_PERMISSION = "com.example.caposbackground.USB_PRINTER_PERMISSION";
    private static final int PERMISSION_TIMEOUT_MS = 20000;
    private static final int BULK_TIMEOUT_MS = 5000;
    private static final int MAX_PACKET = 16384;

    private UsbEscPosConnection() {}

    public static OutputStream open(Context context) throws IOException {
        if (context == null) throw new IOException("No context for USB printer");
        Context app = context.getApplicationContext();
        UsbManager usbManager = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) throw new IOException("USB not available on this device");

        UsbDevice device = findPrinter(usbManager);
        if (device == null) {
            throw new IOException("No USB printer found. Plug in the printer and grant USB access.");
        }
        ensurePermission(app, usbManager, device);

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            throw new IOException("Could not open USB printer (permission missing?)");
        }

        UsbInterface printerInterface = null;
        UsbEndpoint outEndpoint = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            UsbEndpoint ep = findBulkOut(intf);
            if (ep == null) continue;
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER || printerInterface == null) {
                printerInterface = intf;
                outEndpoint = ep;
                if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) break;
            }
        }
        if (printerInterface == null || outEndpoint == null) {
            connection.close();
            throw new IOException("USB printer has no bulk OUT endpoint");
        }
        if (!connection.claimInterface(printerInterface, true)) {
            connection.close();
            throw new IOException("Could not claim USB printer interface");
        }
        Log.i(TAG, "USB printer open vid=" + device.getVendorId()
                + " pid=" + device.getProductId()
                + " name=" + device.getDeviceName());
        return new UsbBulkOutputStream(connection, printerInterface, outEndpoint);
    }

    /** Find a connected USB printer, or null. Does not request permission. */
    public static UsbDevice findPrinter(Context context) {
        if (context == null) return null;
        UsbManager usbManager = (UsbManager) context.getApplicationContext()
                .getSystemService(Context.USB_SERVICE);
        return usbManager == null ? null : findPrinter(usbManager);
    }

    /** Prompt for USB permission if a printer is attached and permission is not yet granted. */
    public static void requestPermissionIfNeeded(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        UsbManager usbManager = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;
        UsbDevice device = findPrinter(usbManager);
        if (device == null || usbManager.hasPermission(device)) return;
        usbManager.requestPermission(device, permissionIntent(app));
    }

    private static UsbDevice findPrinter(UsbManager usbManager) {
        UsbDevice fallback = null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            boolean hasBulkOut = false;
            boolean isPrinterClass = false;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                if (findBulkOut(intf) != null) hasBulkOut = true;
                if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) isPrinterClass = true;
            }
            if (isPrinterClass) return device;
            if (hasBulkOut && fallback == null) fallback = device;
        }
        return fallback;
    }

    private static UsbEndpoint findBulkOut(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                return ep;
            }
        }
        return null;
    }

    private static PendingIntent permissionIntent(Context app) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(app.getPackageName());
        return PendingIntent.getBroadcast(app, 0, intent, flags);
    }

    private static void ensurePermission(Context app, UsbManager usbManager, UsbDevice device)
            throws IOException {
        if (usbManager.hasPermission(device)) return;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean granted = new AtomicBoolean(false);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                UsbDevice extra = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (extra != null && extra.getDeviceId() == device.getDeviceId()) {
                    granted.set(ok);
                    latch.countDown();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        try {
            usbManager.requestPermission(device, permissionIntent(app));
            if (!latch.await(PERMISSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("USB printer permission timed out");
            }
            if (!granted.get() && !usbManager.hasPermission(device)) {
                throw new IOException("USB printer permission denied");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("USB permission interrupted", e);
        } finally {
            try {
                app.unregisterReceiver(receiver);
            } catch (Exception ignored) { }
        }
    }

    private static final class UsbBulkOutputStream extends OutputStream {
        private final UsbDeviceConnection connection;
        private final UsbInterface usbInterface;
        private final UsbEndpoint endpoint;
        private volatile boolean closed;

        UsbBulkOutputStream(UsbDeviceConnection connection, UsbInterface usbInterface,
                            UsbEndpoint endpoint) {
            this.connection = connection;
            this.usbInterface = usbInterface;
            this.endpoint = endpoint;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int count) throws IOException {
            if (closed) throw new IOException("USB printer closed");
            if (buffer == null) throw new NullPointerException();
            if (offset < 0 || count < 0 || offset + count > buffer.length) {
                throw new IndexOutOfBoundsException();
            }
            int sent = 0;
            while (sent < count) {
                int chunk = Math.min(MAX_PACKET, count - sent);
                int n = connection.bulkTransfer(endpoint, buffer, offset + sent, chunk, BULK_TIMEOUT_MS);
                if (n < 0) throw new IOException("USB bulk transfer failed: " + n);
                if (n == 0) throw new IOException("USB bulk transfer wrote 0 bytes");
                sent += n;
            }
        }

        @Override
        public void flush() {
            // bulk transfers are synchronous
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                connection.releaseInterface(usbInterface);
            } catch (Exception ignored) { }
            try {
                connection.close();
            } catch (Exception ignored) { }
        }
    }
}
