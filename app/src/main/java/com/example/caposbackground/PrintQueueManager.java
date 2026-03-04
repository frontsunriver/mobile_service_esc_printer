package com.example.caposbackground;

import android.content.Context;
import android.util.Log;

import com.example.caposbackground.escpos.EscPosPrinter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Queue-based printing: thermal (receipt) + kitchen + kitchen1..5.
 * Each queue has its own worker; jobs are enqueued after DB save and processed in order.
 */
public class PrintQueueManager {

    private static final String TAG = "PrintQueue";
    private static final int[] COL_WIDTHS = {34, 6, 8}; // Same as Node module.js

    public static final int TYPE_THERMAL = 0;
    public static final int TYPE_KITCHEN = 1;
    public static final int TYPE_KITCHEN1 = 2;
    public static final int TYPE_KITCHEN2 = 3;
    public static final int TYPE_KITCHEN3 = 4;
    public static final int TYPE_KITCHEN4 = 5;
    public static final int TYPE_KITCHEN5 = 6;

    private static volatile PrintQueueManager instance;
    private final Context appContext;
    private final PrinterConfig config;
    private final ReceiptDbHelper db;

    private final ConcurrentLinkedQueue<PrintJob> thermalQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PrintJob> kitchenQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PrintJob> kitchen1Queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PrintJob> kitchen2Queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PrintJob> kitchen3Queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PrintJob> kitchen4Queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PrintJob> kitchen5Queue = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean processingThermal = new AtomicBoolean(false);
    private final AtomicBoolean processingKitchen = new AtomicBoolean(false);
    private final AtomicBoolean processingKitchen1 = new AtomicBoolean(false);
    private final AtomicBoolean processingKitchen2 = new AtomicBoolean(false);
    private final AtomicBoolean processingKitchen3 = new AtomicBoolean(false);
    private final AtomicBoolean processingKitchen4 = new AtomicBoolean(false);
    private final AtomicBoolean processingKitchen5 = new AtomicBoolean(false);

    /** IDs already enqueued (so we don't enqueue same row twice from DB poll). Removed after print or on failure. */
    private final Set<Long> enqueuedIds = ConcurrentHashMap.newKeySet();

    public static PrintQueueManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PrintQueueManager.class) {
                if (instance == null && context != null) {
                    instance = new PrintQueueManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private PrintQueueManager(Context appContext) {
        this.appContext = appContext;
        this.config = new PrinterConfig(appContext);
        this.db = new ReceiptDbHelper(appContext);
    }

    /** Reload printer IP list from the external config file (e.g. after user edits it). */
    public void reloadPrinterConfig() {
        config.loadFromFile();
    }

    /** Path where printer_ips.txt is read from. Use this to tell the user where to place the file. */
    public static String getPrinterConfigFilePath(Context context) {
        return PrinterConfig.getConfigFilePath(context != null ? context.getApplicationContext() : null);
    }

    /**
     * Enqueue a pending receipt from DB poll. Skips if this id was already enqueued (avoids duplicates).
     * Call this from the 8-sec poll when rows exist in the database.
     */
    public void enqueueFromPending(PendingReceipt r) {
        if (r == null) return;
        if (!enqueuedIds.add(r.id)) return; // already enqueued
        PrintJob job = new PrintJob(r.id, r.header, r.content, r.footer);
        enqueue(job, r.thermal, r.kitchen, r.kitchen1, r.kitchen2, r.kitchen3, r.kitchen4, r.kitchen5);
    }

    /** Enqueue one job to the queues indicated by flags (thermal, kitchen, kitchen1..5). */
    public void enqueue(PrintJob job, boolean thermal, boolean kitchen,
                        boolean k1, boolean k2, boolean k3, boolean k4, boolean k5) {
        enqueuedIds.add(job.id); // avoid duplicate from DB poll
        if (thermal) {
            thermalQueue.add(job);
            startProcessThermal();
        }
        if (kitchen) {
            kitchenQueue.add(job);
            startProcessKitchen();
        }
        if (k1) {
            kitchen1Queue.add(job);
            startProcessKitchen1();
        }
        if (k2) {
            kitchen2Queue.add(job);
            startProcessKitchen2();
        }
        if (k3) {
            kitchen3Queue.add(job);
            startProcessKitchen3();
        }
        if (k4) {
            kitchen4Queue.add(job);
            startProcessKitchen4();
        }
        if (k5) {
            kitchen5Queue.add(job);
            startProcessKitchen5();
        }
    }

    private void startProcessThermal() {
        if (!processingThermal.compareAndSet(false, true)) return;
        new Thread(this::processThermalQueue, "PrintQueue-thermal").start();
    }

    private void startProcessKitchen() {
        if (!processingKitchen.compareAndSet(false, true)) return;
        new Thread(this::processKitchenQueue, "PrintQueue-kitchen").start();
    }

    private void startProcessKitchen1() {
        if (!processingKitchen1.compareAndSet(false, true)) return;
        new Thread(this::processKitchen1Queue, "PrintQueue-kitchen1").start();
    }

    private void startProcessKitchen2() {
        if (!processingKitchen2.compareAndSet(false, true)) return;
        new Thread(this::processKitchen2Queue, "PrintQueue-kitchen2").start();
    }

    private void startProcessKitchen3() {
        if (!processingKitchen3.compareAndSet(false, true)) return;
        new Thread(this::processKitchen3Queue, "PrintQueue-kitchen3").start();
    }

    private void startProcessKitchen4() {
        if (!processingKitchen4.compareAndSet(false, true)) return;
        new Thread(this::processKitchen4Queue, "PrintQueue-kitchen4").start();
    }

    private void startProcessKitchen5() {
        if (!processingKitchen5.compareAndSet(false, true)) return;
        new Thread(this::processKitchen5Queue, "PrintQueue-kitchen5").start();
    }

    private void processThermalQueue() {
        try {
            processQueue(thermalQueue, TYPE_THERMAL, true);
        } finally {
            processingThermal.set(false);
        }
    }

    private void processKitchenQueue() {
        try {
            processQueue(kitchenQueue, TYPE_KITCHEN, false);
        } finally {
            processingKitchen.set(false);
        }
    }

    private void processKitchen1Queue() {
        try {
            processQueue(kitchen1Queue, TYPE_KITCHEN1, false);
        } finally {
            processingKitchen1.set(false);
        }
    }

    private void processKitchen2Queue() {
        try {
            processQueue(kitchen2Queue, TYPE_KITCHEN2, false);
        } finally {
            processingKitchen2.set(false);
        }
    }

    private void processKitchen3Queue() {
        try {
            processQueue(kitchen3Queue, TYPE_KITCHEN3, false);
        } finally {
            processingKitchen3.set(false);
        }
    }

    private void processKitchen4Queue() {
        try {
            processQueue(kitchen4Queue, TYPE_KITCHEN4, false);
        } finally {
            processingKitchen4.set(false);
        }
    }

    private void processKitchen5Queue() {
        try {
            processQueue(kitchen5Queue, TYPE_KITCHEN5, false);
        } finally {
            processingKitchen5.set(false);
        }
    }

    private static String getTypeLabel(int type) {
        switch (type) {
            case TYPE_THERMAL: return "thermal";
            case TYPE_KITCHEN: return "kitchen";
            case TYPE_KITCHEN1: return "kitchen1";
            case TYPE_KITCHEN2: return "kitchen2";
            case TYPE_KITCHEN3: return "kitchen3";
            case TYPE_KITCHEN4: return "kitchen4";
            case TYPE_KITCHEN5: return "kitchen5";
            default: return "type=" + type;
        }
    }

    private void processQueue(ConcurrentLinkedQueue<PrintJob> queue, int type, boolean isThermal) {
        String ip = config.getIpForType(type);
        if (ip == null || ip.trim().isEmpty()) {
            Log.w(TAG, "No printer IP for type " + type + ", skipping");
            return;
        }
        int port = config.getPort();
        while (true) {
            PrintJob job = queue.poll();
            if (job == null) break;
            EscPosPrinter printer = null;
            try {
                Log.i(TAG, "Sending print jobId=" + job.id + " " + getTypeLabel(type) + " printer IP=" + ip + " port=" + port);
                Log.d("PRINT DATA", job.header + job.content + job.footer);
                printer = EscPosPrinter.connect(ip, port);
                if (isThermal) {
                    printer.printReceipt(job.header, job.content, job.footer, COL_WIDTHS);
                } else {
                    printer.printKitchenReceipt(job.header, job.content, job.footer);
                }
                db.delete(job.id);
                enqueuedIds.remove(job.id);
            } catch (Exception e) {
                Log.e(TAG, "Print failed type=" + type + " jobId=" + job.id + " ip=" + ip, e);
                enqueuedIds.remove(job.id); // allow retry on next poll
            } finally {
                if (printer != null) printer.close();
            }
        }
    }
}
