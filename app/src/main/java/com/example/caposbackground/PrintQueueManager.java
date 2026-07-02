package com.example.caposbackground;

import android.content.Context;
import android.util.Log;

import com.example.caposbackground.escpos.EscPosPrinter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /**
     * When the POS sends customer + kitchen as two HTTP posts (thermal then kitchen),
     * ignore the second post to the same printer IP within this window.
     */
    private static final long SPLIT_RECEIPT_DEDUP_MS = 15000L;
    /**
     * POS often retries the same combined checkout POST while the first print is still
     * connecting (slow wake-up). Dedup identical combined posts within this window.
     */
    private static final long CHECKOUT_COMBINED_DEDUP_MS = 30000L;
    /**
     * Ignore a second identical thermal- or kitchen-only POST to the same IP within this window
     * (HTTP retry / double fire on checkout). Intentional re-sends after this still print.
     */
    private static final long RECEIPT_IMMEDIATE_DEDUP_MS = 2000L;

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
    private final Object dispatchLock = new Object();
    /** One mutex per printer IP so thermal/kitchen never print in parallel to the same device. */
    private final ConcurrentHashMap<String, Object> ipPrintLocks = new ConcurrentHashMap<>();

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
    /** jobId@ip already sent — one physical print per receipt per printer. */
    private final Set<String> completedPrintKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, AtomicInteger> jobTargetsRemaining = new ConcurrentHashMap<>();
    /** Per-IP dispatch/print state for burst and split-receipt dedup. */
    private final ConcurrentHashMap<String, IpPrintState> ipPrintState = new ConcurrentHashMap<>();
    /** Last time identical receipt content was sent to an IP (T:/K:hash@ip -> time). */
    private final ConcurrentHashMap<String, Long> recentPrintByContentIp = new ConcurrentHashMap<>();

    private static final class IpPrintState {
        String contentHash = "";
        long lastDispatchMs;
        boolean hadThermal;
        boolean hadKitchen;
        volatile boolean printing;
    }

    private Object lockForIp(String ip) {
        return ipPrintLocks.computeIfAbsent(ip, k -> new Object());
    }

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

        synchronized (dispatchLock) {
            List<Integer> typesToPrint = resolveTypesToPrint(r);
            if (typesToPrint.isEmpty()) {
                claimedReceiptIds.remove(receiptId);
                db.delete(receiptId);
                Log.w(TAG, "No printer targets for receiptId=" + receiptId);
                return;
            }

            if (shouldSkipBurstOrSplitDuplicate(r, typesToPrint)) {
                claimedReceiptIds.remove(receiptId);
                db.delete(receiptId);
                Log.i(TAG, "Skip duplicate burst/split receiptId=" + receiptId);
                return;
            }

            jobTargetsRemaining.put(job.id, new AtomicInteger(typesToPrint.size()));
            noteDispatch(r, typesToPrint);
            Log.i(TAG, "Dispatch receiptId=" + receiptId + " targets=" + typesToPrint.size()
                    + " types=" + typesToPrint);

            ConcurrentLinkedQueue<PrintJob>[] queues = new ConcurrentLinkedQueue[]{
                    thermalQueue, kitchenQueue, kitchen1Queue, kitchen2Queue,
                    kitchen3Queue, kitchen4Queue, kitchen5Queue
            };
            Runnable[] starters = {
                    this::startProcessThermal, this::startProcessKitchen, this::startProcessKitchen1,
                    this::startProcessKitchen2, this::startProcessKitchen3, this::startProcessKitchen4,
                    this::startProcessKitchen5
            };
            for (int type : typesToPrint) {
                queues[type].add(job);
                starters[type].run();
            }
        }
    }

    /** One print target per unique printer IP; kitchen beats thermal on the same IP. */
    private List<Integer> resolveTypesToPrint(PendingReceipt r) {
        Map<String, Integer> typeByIp = new LinkedHashMap<>();
        int[] types = {
                TYPE_KITCHEN, TYPE_KITCHEN1, TYPE_KITCHEN2, TYPE_KITCHEN3,
                TYPE_KITCHEN4, TYPE_KITCHEN5, TYPE_THERMAL
        };
        boolean[] flags = {
                r.kitchen, r.kitchen1, r.kitchen2, r.kitchen3, r.kitchen4, r.kitchen5, r.thermal
        };
        for (int i = 0; i < types.length; i++) {
            if (!flags[i]) continue;
            String ip = normalizeIp(config.getIpForType(types[i]));
            if (ip == null) continue;
            typeByIp.putIfAbsent(ip, types[i]);
        }
        return new ArrayList<>(typeByIp.values());
    }

    private static String normalizeIp(String ip) {
        if (ip == null) return null;
        ip = ip.trim();
        return ip.isEmpty() ? null : ip;
    }

    private static String contentHash(PendingReceipt r) {
        return contentHash(r.header, r.content, r.footer);
    }

    private static String contentHash(String header, String content, String footer) {
        String s = (header != null ? header : "")
                + "\n" + (content != null ? content : "")
                + "\n" + (footer != null ? footer : "");
        return Integer.toHexString(s.hashCode());
    }

    private static String contentIpKey(boolean thermal, String hash, String ip) {
        return (thermal ? "T:" : "K:") + hash + "@" + ip;
    }

    private static boolean isThermalOnly(PendingReceipt r) {
        return r.thermal && !hasKitchenFlag(r);
    }

    private static boolean isKitchenOnly(PendingReceipt r) {
        return !r.thermal && hasKitchenFlag(r);
    }

    private static boolean hasKitchenFlag(PendingReceipt r) {
        return r.kitchen || r.kitchen1 || r.kitchen2 || r.kitchen3 || r.kitchen4 || r.kitchen5;
    }

    /**
     * Drop duplicate checkout jobs: same content to same IP while printing,
     * combined thermal+kitchen retry, thermal+kitchen split POST pair, or
     * immediate thermal/kitchen-only HTTP retry. Intentional re-sends after
     * {@link #RECEIPT_IMMEDIATE_DEDUP_MS} still print.
     */
    private boolean shouldSkipBurstOrSplitDuplicate(PendingReceipt r, List<Integer> typesToPrint) {
        String hash = contentHash(r);
        long now = System.currentTimeMillis();
        for (int type : typesToPrint) {
            String ip = normalizeIp(config.getIpForType(type));
            if (ip == null) continue;
            IpPrintState state = ipPrintState.get(ip);
            if (state == null) continue;
            if (!hash.equals(state.contentHash)) continue;

            long since = now - state.lastDispatchMs;

            if (state.printing) {
                if (r.thermal && hasKitchenFlag(r)) return true;
                if (isThermalOnly(r)) return true;
                if (isKitchenOnly(r)) return true;
            }

            if (r.thermal && hasKitchenFlag(r) && since < CHECKOUT_COMBINED_DEDUP_MS) {
                return true;
            }

            if (since <= SPLIT_RECEIPT_DEDUP_MS) {
                if (isThermalOnly(r) && state.hadKitchen && !state.hadThermal) return true;
                if (isKitchenOnly(r) && state.hadThermal && !state.hadKitchen) return true;
            }

            if (isThermalOnly(r) && state.hadThermal && since < RECEIPT_IMMEDIATE_DEDUP_MS) {
                return true;
            }
            if (isKitchenOnly(r) && state.hadKitchen && since < RECEIPT_IMMEDIATE_DEDUP_MS) {
                return true;
            }
        }
        return false;
    }

    private void noteDispatch(PendingReceipt r, List<Integer> typesToPrint) {
        String hash = contentHash(r);
        long now = System.currentTimeMillis();
        for (int type : typesToPrint) {
            String ip = normalizeIp(config.getIpForType(type));
            if (ip == null) continue;
            IpPrintState state = ipPrintState.computeIfAbsent(ip, k -> new IpPrintState());
            state.contentHash = hash;
            state.lastDispatchMs = now;
            if (type == TYPE_THERMAL) {
                state.hadThermal = true;
            } else {
                state.hadKitchen = true;
            }
        }
    }

    private static String printKey(long jobId, String ip) {
        return jobId + "@" + ip;
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
            processQueue(thermalQueue, TYPE_THERMAL, true);
        } finally {
            processingThermal.set(false);
            if (!thermalQueue.isEmpty()) startProcessThermal();
        }
    }

    private void processKitchenQueue() {
        try {
            processQueue(kitchenQueue, TYPE_KITCHEN, false);
        } finally {
            processingKitchen.set(false);
            if (!kitchenQueue.isEmpty()) startProcessKitchen();
        }
    }

    private void processKitchen1Queue() {
        try {
            processQueue(kitchen1Queue, TYPE_KITCHEN1, false);
        } finally {
            processingKitchen1.set(false);
            if (!kitchen1Queue.isEmpty()) startProcessKitchen1();
        }
    }

    private void processKitchen2Queue() {
        try {
            processQueue(kitchen2Queue, TYPE_KITCHEN2, false);
        } finally {
            processingKitchen2.set(false);
            if (!kitchen2Queue.isEmpty()) startProcessKitchen2();
        }
    }

    private void processKitchen3Queue() {
        try {
            processQueue(kitchen3Queue, TYPE_KITCHEN3, false);
        } finally {
            processingKitchen3.set(false);
            if (!kitchen3Queue.isEmpty()) startProcessKitchen3();
        }
    }

    private void processKitchen4Queue() {
        try {
            processQueue(kitchen4Queue, TYPE_KITCHEN4, false);
        } finally {
            processingKitchen4.set(false);
            if (!kitchen4Queue.isEmpty()) startProcessKitchen4();
        }
    }

    private void processKitchen5Queue() {
        try {
            processQueue(kitchen5Queue, TYPE_KITCHEN5, false);
        } finally {
            processingKitchen5.set(false);
            if (!kitchen5Queue.isEmpty()) startProcessKitchen5();
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

    private void processQueue(ConcurrentLinkedQueue<PrintJob> queue, int type, boolean isThermal) {
        String ip = normalizeIp(config.getIpForType(type));
        if (ip == null) {
            Log.w(TAG, "No printer IP for " + getTypeLabel(type));
            return;
        }
        int port = config.getPort();

        while (true) {
            PrintJob job = queue.poll();
            if (job == null) break;

            synchronized (lockForIp(ip)) {
                String key = printKey(job.id, ip);
                if (!completedPrintKeys.add(key)) {
                    Log.w(TAG, "Skip already-printed jobId=" + job.id + " ip=" + ip);
                    markTypePrinted(job.id, type);
                    continue;
                }

                String hash = contentHash(job.header, job.content, job.footer);
                String contentIpKey = contentIpKey(isThermal, hash, ip);
                long now = System.currentTimeMillis();
                Long lastPrinted = recentPrintByContentIp.get(contentIpKey);
                if (lastPrinted != null && now - lastPrinted < RECEIPT_IMMEDIATE_DEDUP_MS) {
                    Log.w(TAG, "Skip duplicate " + getTypeLabel(type) + " content jobId=" + job.id
                            + " ip=" + ip);
                    markTypePrinted(job.id, type);
                    continue;
                }

                IpPrintState state = ipPrintState.computeIfAbsent(ip, k -> new IpPrintState());
                state.printing = true;

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
                    recentPrintByContentIp.put(contentIpKey, System.currentTimeMillis());
                } catch (Exception e) {
                    Log.e(TAG, "PRINT ERROR jobId=" + job.id + " " + getTypeLabel(type)
                            + " connected=" + connected, e);
                    if (!connected) {
                        completedPrintKeys.remove(key);
                        jobTargetsRemaining.remove(job.id);
                        claimedReceiptIds.remove(job.id);
                    }
                } finally {
                    state.printing = false;
                    state.lastDispatchMs = System.currentTimeMillis();
                    if (printer != null) printer.close();
                    if (connected) {
                        markTypePrinted(job.id, type);
                    }
                }
            }
        }
    }
}
