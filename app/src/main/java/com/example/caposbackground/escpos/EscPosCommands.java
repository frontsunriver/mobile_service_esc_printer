package com.example.caposbackground.escpos;

/**
 * ESC/POS command constants (hex bytes). Reference: Epson ESC/POS command set.
 * Use with {@link EscPosPrinter} or any OutputStream to a thermal printer.
 */
public final class EscPosCommands {

    private EscPosCommands() {}

    // --- Initialize ---
    /** ESC @ - Initialize printer (clear buffer, reset mode) */
    public static final byte[] INIT = {0x1B, 0x40};
    /** FS . - Cancel condensed mode */
    public static final byte[] CANCEL_CONDENSED = {0x1C, 0x2E};

    // --- Font / style ---
    /** ESC ! n - Select print mode: 0x00 = normal */
    public static final byte[] FONT_NORMAL = {0x1B, 0x21, 0x00};
    /** ESC ! n - Double height (0x10) + double width (0x20) = 0x30 = big */
    public static final byte[] FONT_BIG = {0x1B, 0x21, 0x30};
    /** ESC ! n - Underline (0x80) */
    public static final byte[] FONT_UNDERLINE = {0x1B, 0x21, (byte) 0x80};
    /** ESC E n - Bold: 1 = on, 0 = off */
    public static final byte[] BOLD_ON = {0x1B, 0x45, 0x01};
    public static final byte[] BOLD_OFF = {0x1B, 0x45, 0x00};
    /** GS B - Inverse/reverse (black background): 1 = on, 0 = off */
    public static final byte[] REVERSE_ON = {0x1D, 0x42, 0x01};
    public static final byte[] REVERSE_OFF = {0x1D, 0x42, 0x00};

    // --- Alignment ---
    /** ESC a n - Justification: 0 = left, 1 = center, 2 = right */
    public static final byte[] ALIGN_LEFT = {0x1B, 0x61, 0x00};
    public static final byte[] ALIGN_CENTER = {0x1B, 0x61, 0x01};
    public static final byte[] ALIGN_RIGHT = {0x1B, 0x61, 0x02};

    // --- Paper ---
    /** GS V m - Cut: 0 = full, 1 = partial */
    public static final byte[] CUT_FULL = {0x1D, 0x56, 0x00};
    public static final byte[] CUT_PARTIAL = {0x1D, 0x56, 0x01};
    /** ESC d n - Feed n lines */
    public static final byte[] FEED_LINE = {0x1B, 0x64, 0x01};
    /** ESC J n - Feed n dot lines */
    public static final byte[] FEED_DOTS = {0x1B, 0x4A, 0x18};

    // --- Line spacing (optional) ---
    /** ESC 3 n - Set line spacing to n dots */
    // public static final byte[] LINE_SPACING = {0x1B, 0x33, 0x30};
}
