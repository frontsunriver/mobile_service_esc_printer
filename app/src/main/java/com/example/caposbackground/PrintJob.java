package com.example.caposbackground;

/**
 * One receipt print job (header, content, footer) for a printer queue.
 */
public class PrintJob {
    public final long id;
    public final String header;
    public final String content;
    public final String footer;

    public PrintJob(long id, String header, String content, String footer) {
        this.id = id;
        this.header = header != null ? header : "";
        this.content = content != null ? content : "";
        this.footer = footer != null ? footer : "";
    }
}
