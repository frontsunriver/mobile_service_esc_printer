package com.example.caposbackground;

/**
 * One row from the receipts table, used to enqueue print jobs from DB poll.
 */
public class PendingReceipt {
    public final long id;
    public final String header;
    public final String content;
    public final String footer;
    public final boolean thermal;
    public final boolean kitchen;
    public final boolean kitchen1;
    public final boolean kitchen2;
    public final boolean kitchen3;
    public final boolean kitchen4;
    public final boolean kitchen5;

    public PendingReceipt(long id, String header, String content, String footer,
                          boolean thermal, boolean kitchen,
                          boolean k1, boolean k2, boolean k3, boolean k4, boolean k5) {
        this.id = id;
        this.header = header != null ? header : "";
        this.content = content != null ? content : "";
        this.footer = footer != null ? footer : "";
        this.thermal = thermal;
        this.kitchen = kitchen;
        this.kitchen1 = k1;
        this.kitchen2 = k2;
        this.kitchen3 = k3;
        this.kitchen4 = k4;
        this.kitchen5 = k5;
    }
}
