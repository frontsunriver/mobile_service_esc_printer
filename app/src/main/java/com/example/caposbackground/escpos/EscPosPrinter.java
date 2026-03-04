package com.example.caposbackground.escpos;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static com.example.caposbackground.escpos.EscPosCommands.*;

/**
 * ESC/POS printer module for Android. Sends commands and text to a network thermal printer (TCP port 9100).
 *
 * <h3>How to use</h3>
 * <pre>
 * // 1) Connect by IP and port (default 9100)
 * EscPosPrinter printer = EscPosPrinter.connect("192.168.2.100", 9100);
 *
 * // 2) Print a full receipt (thermal: header + table content + footer + cut)
 * printer.printReceipt(header, content, footer, new int[]{34, 6, 8});
 *
 * // 3) Or print kitchen-style (all formatted lines + cut)
 * printer.printKitchenReceipt(header, content, footer);
 *
 * // 4) Or low-level: raw commands and text
 * printer.init();
 * printer.write(ALIGN_CENTER);
 * printer.text("Hello\n");
 * printer.cut();
 *
 * // 5) Always close when done
 * printer.close();
 * </pre>
 *
 * <p>Line formatting (same as Node module): use [L] left, [C] center, [R] right; &lt;b&gt;&lt;/b&gt; for bold,
 * &lt;u&gt;&lt;/u&gt; for underline, &lt;font size="big"&gt; for large text. Table lines: [L]col1[R]col2[R]col3.
 */
public class EscPosPrinter {

    private static final String TAG = "EscPosPrinter";
    /** Connection timeout (ms). Increase if printer is slow to wake or network is slow. */
    private static final int DEFAULT_TIMEOUT_MS = 20000;
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");

    /** Default column widths for receipt table (Name, QTY, Price). */
    public static final int[] DEFAULT_COL_WIDTHS = {34, 6, 8};

    private final OutputStream out;
    private final Charset charset;

    public EscPosPrinter(OutputStream out) {
        this(out, StandardCharsets.UTF_8);
    }

    public EscPosPrinter(OutputStream out, Charset charset) {
        this.out = out;
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
    }

    /**
     * Connect to a network printer.
     *
     * @param host Printer IP (e.g. "192.168.2.100")
     * @param port Usually 9100 for raw TCP printing
     * @return printer instance; call {@link #close()} when done
     */
    public static EscPosPrinter connect(String host, int port) throws IOException {
        return connect(host, port, DEFAULT_TIMEOUT_MS);
    }

    public static EscPosPrinter connect(String host, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        return new EscPosPrinter(socket.getOutputStream());
    }

    // --- Raw command API ---

    public EscPosPrinter write(byte[] cmd) throws IOException {
        out.write(cmd);
        return this;
    }

    public EscPosPrinter init() throws IOException {
        out.write(INIT);
        out.write(CANCEL_CONDENSED);
        out.write(FONT_NORMAL);
        out.flush();
        return this;
    }

    public EscPosPrinter text(String s) throws IOException {
        if (s != null && !s.isEmpty()) {
            out.write(s.getBytes(charset));
            out.flush();
        }
        return this;
    }

    public EscPosPrinter feedLine() throws IOException {
        out.write(FEED_LINE);
        out.flush();
        return this;
    }

    public EscPosPrinter cut() throws IOException {
        out.write(CUT_FULL);
        out.flush();
        return this;
    }

    public EscPosPrinter cutPartial() throws IOException {
        out.write(CUT_PARTIAL);
        out.flush();
        return this;
    }

    public void close() {
        try {
            out.close();
        } catch (IOException e) {
            Log.w(TAG, "close", e);
        }
    }

    // --- Formatted print (header/footer with [L]/[C]/[R], bold, underline, big) ---

    private static String stripTags(String s) {
        return s == null ? "" : TAG_PATTERN.matcher(s).replaceAll("").trim();
    }

    private static void parseLine(String line, int[] outAlign, boolean[] outBold, boolean[] outUnderline, boolean[] outBig) {
        outBold[0] = line != null && line.contains("<b>") && line.contains("</b>");
        outUnderline[0] = line != null && (line.contains("<u>") && line.contains("</u>"));
        outBig[0] = line != null && (line.contains("size=\"big\"") || line.contains("size='big'"));
        String clean = stripTags(line);
        if (clean.startsWith("[L]")) {
            outAlign[0] = 0;
        } else if (clean.startsWith("[R]")) {
            outAlign[0] = 2;
        } else if (clean.startsWith("[C]")) {
            outAlign[0] = 1;
        } else {
            outAlign[0] = 0;
        }
    }

    private static String stripPrefix(String clean) {
        if (clean.startsWith("[L]") || clean.startsWith("[R]") || clean.startsWith("[C]")) {
            return clean.substring(3).trim();
        }
        return clean.trim();
    }

    private void align(int a) throws IOException {
        if (a == 1) out.write(ALIGN_CENTER);
        else if (a == 2) out.write(ALIGN_RIGHT);
        else out.write(ALIGN_LEFT);
    }

    /** Print lines with [L]/[C]/[R], &lt;b&gt;, &lt;u&gt;, &lt;font size="big"&gt;. Optionally cut at end. Call init() once before if starting a new receipt. */
    public void printFormatted(String[] lines, boolean cutAtEnd) throws IOException {
        if (lines == null) return;
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            int[] align = {0};
            boolean[] bold = {false}, underline = {false}, big = {false};
            parseLine(line, align, bold, underline, big);
            String text = stripPrefix(stripTags(line));
            if (text.isEmpty()) continue;
            if (big[0]) out.write(FONT_BIG);
            else out.write(FONT_NORMAL);
            if (underline[0]) out.write(FONT_UNDERLINE);
            if (bold[0]) out.write(BOLD_ON);
            else out.write(BOLD_OFF);
            align(align[0]);
            out.write((text + "\n").getBytes(charset));
            out.flush();
        }
        if (cutAtEnd) {
            for (int i = 0; i < 6; i++) {
                feedLine();
            }
            cut();
        }
    }

    /** Print table: each line "[L]col1[R]col2[R]col3", padded to colWidths. Call init() once before if starting a new receipt. */
    public void printTable(String[] contentLines, int[] colWidths) throws IOException {
        if (contentLines == null || colWidths == null) return;
        for (String line : contentLines) {
            if (line == null || line.trim().isEmpty()) continue;
            String[] parts = parseTableLine(line);
            StringBuilder row = new StringBuilder();
            for (int i = 0; i < colWidths.length; i++) {
                String cell = i < parts.length ? parts[i] : "";
                int w = colWidths[i];
                if (cell.length() > w) cell = cell.substring(0, w);
                else while (cell.length() < w) cell = cell + " ";
                row.append(cell);
            }
            out.write(ALIGN_LEFT);
            out.write(BOLD_OFF);
            out.write((row.toString() + "\n").getBytes(charset));
            out.flush();
        }
    }

    private static String[] parseTableLine(String line) {
        String s = stripTags(line);
        if (s.startsWith("[L]")) s = s.substring(3);
        return s.split("\\[R\\]");
    }

    /** Thermal receipt: header (formatted) + content (table) + footer (formatted + cut). */
    public void printReceipt(String header, String content, String footer, int[] colWidths) throws IOException {
        init();
        if (header != null && !header.trim().isEmpty()) {
            printFormatted(header.split("\n"), false);
        }
        if (content != null && !content.trim().isEmpty()) {
            printTable(content.split("\n"), colWidths != null ? colWidths : DEFAULT_COL_WIDTHS);
        }
        if (footer != null && !footer.trim().isEmpty()) {
            printFormatted(footer.split("\n"), true);
        } else {
            cut();
        }
    }

    /** Kitchen receipt: header + content (formatted) + footer (with cut). */
    public void printKitchenReceipt(String header, String content, String footer) throws IOException {
        init();
        if (header != null && !header.trim().isEmpty()) {
            printFormatted(header.split("\n"), false);
        }
        if (content != null && !content.trim().isEmpty()) {
            printFormatted(content.split("\n"), false);
        }
        if (footer != null && !footer.trim().isEmpty()) {
            printFormatted(footer.split("\n"), true);
        } else {
            cut();
        }
    }
}
