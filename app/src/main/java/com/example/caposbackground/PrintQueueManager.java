package com.example.caposbackground;

import android.content.Context;
import android.util.Log;

import com.example.caposbackground.escpos.EscPosPrinter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Queue-based printing: thermal (receipt) + kitchen + kitchen1..5.
 * Each receipt is dispatched once from HTTP ({@link #dispatchReceipt(long)}).
 */
public class PrintQueueManager {

    private static final String TAG = "PrintQueue";
    private static final int[] COL_WIDTHS = {34, 6, 8};
    private static final int MAX_CONNECT_ATTEMPTS = 3;
    private static final long CONNECT_RETRY_DELAY_MS = 2000L;

    public static final int TYPE_THERMAL = 0;
    public static final int TYPE_KITCHEN = 1;
    public static final int TYPE_KITCHEN1 = 2;
    public static final int TYPE_KITCHEN2 = 3;
    public static final int TYPE_KITCHEN3 = 4;
    public static final int TYPE_KITCHEN4 = 5;
    public static final int TYPE_KITCHEN5 = 6;

    private static volatile PrintQueueManager instance;
    private final PrinterConfig config;
    private final ReceiptDbHelper db;
    private final Object kitchenLock = new Object();
    private final Object thermalLock = new Object();
    private final Object kitchen1Lock = new Object();
    private final Object kitchen2Lock = new Object();
    private final Object kitchen3Lock = new Object();
    private final Object kitchen4Lock = new Object();
    private final Object kitchen5Lock = new Object();

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

    /** Receipt ids already dispatched from HTTP. */
    private final Set<Long> claimedReceiptIds = ConcurrentHashMap.newKeySet();
    /** jobId:type already sent to a printer — never send again (prevents any retry duplicate). */
    private final Set<String> completedPrintKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, AtomicInteger> jobTargetsRemaining = new ConcurrentHashMap<>();

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
        this.config = new PrinterConfig(appContext);
        this.db = new ReceiptDbHelper(appContext);
    }

    public void reloadPrinterConfig() {
        config.loadFromFile();
    }

    public static String getPrinterConfigFilePath(Context context) {
        return PrinterConfig.getConfigFilePath(context != null ? context.getApplicationContext() : null);
    }

    /**
     * Dispatch a saved receipt to printers. Called only from HTTP after insert.
     * DB poll does not call this — avoids HTTP + poll double dispatch.
     */
    public void dispatchReceipt(long receiptId) {
        if (receiptId < 0) return;
        if (!claimedReceiptIds.add(receiptId)) {
            Log.d(TAG, "Skip dispatch (already claimed) receiptId=" + receiptId);
            return;
        }
        PendingReceipt r = db.getById(receiptId);
        if (r == null) {
            claimedReceiptIds.remove(receiptId);
            return;
        }

        PrintJob job = new PrintJob(r.id, r.header, r.content, r.footer);
        Set<String> seenIps = new HashSet<>();
        boolean[] flags = {
                r.thermal, r.kitchen, r.kitchen1, r.kitchen2, r.kitchen3, r.kitchen4, r.kitchen5
        };
        int[] types = {
                TYPE_THERMAL, TYPE_KITCHEN, TYPE_KITCHEN1, TYPE_KITCHEN2,
                TYPE_KITCHEN3, TYPE_KITCHEN4, TYPE_KITCHEN5
        };
        ConcurrentLinkedQueue<PrintJob>[] queues = new ConcurrentLinkedQueue[]{
                thermalQueue, kitchenQueue, kitchen1Queue, kitchen2Queue,
                kitchen3Queue, kitchen4Queue, kitchen5Queue
        };
        Runnable[] starters = {
                this::startProcessThermal, this::startProcessKitchen, this::startProcessKitchen1,
                this::startProcessKitchen2, this::startProcessKitchen3, this::startProcessKitchen4,
                this::startProcessKitchen5
        };

        List<Integer> typesToPrint = new ArrayList<>();
        for (int i = 0; i < flags.length; i++) {
            if (!flags[i]) continue;
            if (shouldPrintToType(types[i], seenIps)) {
                typesToPrint.add(types[i]);
            }
        }
        int targetCount = typesToPrint.size();
        if (targetCount == 0) {
            claimedReceiptIds.remove(receiptId);
            db.delete(receiptId);
            Log.w(TAG, "No printer targets for receiptId=" + receiptId);
            return;
        }

        jobTargetsRemaining.put(job.id, new AtomicInteger(targetCount));
        Log.i(TAG, "Dispatch receiptId=" + receiptId + " targets=" + targetCount
                + " ips=" + seenIps);

        for (int type : typesToPrint) {
            int idx = type; // TYPE_THERMAL=0 .. TYPE_KITCHEN5=6
            queues[idx].add(job);
            starters[idx].run();
        }
    }

    /** Same physical printer (IP) only receives one copy per receipt. */
    private boolean shouldPrintToType(int type, Set<String> seenIps) {
        String ip = config.getIpForType(type);
        if (ip == null || ip.trim().isEmpty()) {
            Log.w(TAG, "No IP for " + getTypeLabel(type));
            return false;
        }
        ip = ip.trim();
        if (!seenIps.add(ip)) {
            Log.w(TAG, "Skip " + getTypeLabel(type) + " — same IP already used: " + ip);
            return false;
        }
        return true;
    }

    private static String printKey(long jobId, int type) {
        return jobId + ":" + type;
    }

    private void markTypePrinted(long jobId, int type) {
        AtomicInteger remaining = jobTargetsRemaining.get(jobId);
        if (remaining == null) return;
        if (remaining.decrementAndGet() <= 0) {
            db.delete(jobId);
            jobTargetsRemaining.remove(jobId);
            claimedReceiptIds.remove(jobId);
            Log.d(TAG, "Finished receipt id=" + jobId);
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
            processQueue(thermalQueue, TYPE_THERMAL, true, thermalLock);
        } finally {
            processingThermal.set(false);
        }
    }

    private void processKitchenQueue() {
        try {
            processQueue(kitchenQueue, TYPE_KITCHEN, false, kitchenLock);
        } finally {
            processingKitchen.set(false);
        }
    }

    private void processKitchen1Queue() {
        try {
            processQueue(kitchen1Queue, TYPE_KITCHEN1, false, kitchen1Lock);
        } finally {
            processingKitchen1.set(false);
        }
    }

    private void processKitchen2Queue() {
        try {
            processQueue(kitchen2Queue, TYPE_KITCHEN2, false, kitchen2Lock);
        } finally {
            processingKitchen2.set(false);
        }
    }

    private void processKitchen3Queue() {
        try {
            processQueue(kitchen3Queue, TYPE_KITCHEN3, false, kitchen3Lock);
        } finally {
            processingKitchen3.set(false);
        }
    }

    private void processKitchen4Queue() {
        try {
            processQueue(kitchen4Queue, TYPE_KITCHEN4, false, kitchen4Lock);
        } finally {
            processingKitchen4.set(false);
        }
    }

    private void processKitchen5Queue() {
        try {
            processQueue(kitchen5Queue, TYPE_KITCHEN5, false, kitchen5Lock);
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

    private EscPosPrinter connectWithRetry(String ip, int port, long jobId, int type) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            try {
                if (attempt > 1) {
                    Log.i(TAG, "Connect retry " + attempt + "/" + MAX_CONNECT_ATTEMPTS
                            + " jobId=" + jobId + " " + getTypeLabel(type));
                    Thread.sleep(CONNECT_RETRY_DELAY_MS);
                }
                return EscPosPrinter.connect(ip, port);
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "Connect failed attempt " + attempt + " jobId=" + jobId
                        + " " + getTypeLabel(type), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Connect interrupted", e);
            }
        }
        throw last != null ? last : new IOException("Connect failed");
    }

    private void processQueue(ConcurrentLinkedQueue<PrintJob> queue, int type,
                              boolean isThermal, Object lock) {
        synchronized (lock) {
            String ip = config.getIpForType(type);
            if (ip == null || ip.trim().isEmpty()) {
                Log.w(TAG, "No printer IP for " + getTypeLabel(type));
                return;
            }
            int port = config.getPort();
            ip = ip.trim();

            while (true) {
                PrintJob job = queue.poll();
                if (job == null) break;

                String key = printKey(job.id, type);
                if (!completedPrintKeys.add(key)) {
                    Log.w(TAG, "Skip already-printed jobId=" + job.id + " " + getTypeLabel(type));
                    markTypePrinted(job.id, type);
                    continue;
                }

                EscPosPrinter printer = null;
                boolean connected = false;
                try {
                    Log.i(TAG, "PRINT START jobId=" + job.id + " " + getTypeLabel(type)
                            + " ip=" + ip + " port=" + port);
                    printer = connectWithRetry(ip, port, job.id, type);
                    connected = true;
                    if (isThermal) {
                        printer.printReceipt(job.header, job.content, job.footer, COL_WIDTHS);
                    } else {
                        printer.printKitchenReceipt(job.header, job.content, job.footer);
                    }
                    Log.i(TAG, "PRINT DONE jobId=" + job.id + " " + getTypeLabel(type));
                } catch (Exception e) {
                    Log.e(TAG, "PRINT ERROR jobId=" + job.id + " " + getTypeLabel(type)
                            + " connected=" + connected, e);
                    if (!connected) {
                        completedPrintKeys.remove(key);
                        jobTargetsRemaining.remove(job.id);
                        claimedReceiptIds.remove(job.id);
                    }
                } finally {
                    if (printer != null) printer.close();
                    if (connected) {
                        markTypePrinted(job.id, type);
                    }
                }
            }
        }
    }
}
