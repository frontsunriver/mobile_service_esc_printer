package com.example.caposbackground;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite helper for storing receipt/config data from JSON (header, content, footer, ip,
 * receipt_flag, thermal, kitchen, kitchen1..kitchen5).
 */
public class ReceiptDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "ReceiptDbHelper";
    private static final String DB_NAME = "capos_receipts.db";
    private static final int DB_VERSION = 1;

    public static final String TABLE_RECEIPTS = "receipts";
    public static final String COL_ID = "_id";
    public static final String COL_HEADER = "header";
    public static final String COL_CONTENT = "content";
    public static final String COL_FOOTER = "footer";
    public static final String COL_IP = "ip";
    public static final String COL_RECEIPT_FLAG = "receipt_flag";
    public static final String COL_THERMAL = "thermal";
    public static final String COL_KITCHEN = "kitchen";
    public static final String COL_KITCHEN1 = "kitchen1";
    public static final String COL_KITCHEN2 = "kitchen2";
    public static final String COL_KITCHEN3 = "kitchen3";
    public static final String COL_KITCHEN4 = "kitchen4";
    public static final String COL_KITCHEN5 = "kitchen5";
    public static final String COL_CREATED_AT = "created_at";

    private static final String CREATE_TABLE =
            "CREATE TABLE " + TABLE_RECEIPTS + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_HEADER + " TEXT, "
                    + COL_CONTENT + " TEXT, "
                    + COL_FOOTER + " TEXT, "
                    + COL_IP + " TEXT, "
                    + COL_RECEIPT_FLAG + " TEXT, "
                    + COL_THERMAL + " TEXT, "
                    + COL_KITCHEN + " TEXT, "
                    + COL_KITCHEN1 + " TEXT, "
                    + COL_KITCHEN2 + " TEXT, "
                    + COL_KITCHEN3 + " TEXT, "
                    + COL_KITCHEN4 + " TEXT, "
                    + COL_KITCHEN5 + " TEXT, "
                    + COL_CREATED_AT + " INTEGER NOT NULL DEFAULT (strftime('%s','now'))"
                    + ")";

    public ReceiptDbHelper(@Nullable Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE);
        Log.d(TAG, "Created table " + TABLE_RECEIPTS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // For future schema changes
        if (oldVersion < 1) {
            // already created in onCreate
        }
    }

    /**
     * Get all receipt rows (for periodic poll to print any that exist in DB).
     * Order: oldest first (_id ASC).
     */
    public List<PendingReceipt> getAllPending() {
        List<PendingReceipt> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE_RECEIPTS, null, null, null, null, null, COL_ID + " ASC")) {
            int idxId = c.getColumnIndex(COL_ID);
            int idxHeader = c.getColumnIndex(COL_HEADER);
            int idxContent = c.getColumnIndex(COL_CONTENT);
            int idxFooter = c.getColumnIndex(COL_FOOTER);
            int idxThermal = c.getColumnIndex(COL_THERMAL);
            int idxKitchen = c.getColumnIndex(COL_KITCHEN);
            int idxK1 = c.getColumnIndex(COL_KITCHEN1);
            int idxK2 = c.getColumnIndex(COL_KITCHEN2);
            int idxK3 = c.getColumnIndex(COL_KITCHEN3);
            int idxK4 = c.getColumnIndex(COL_KITCHEN4);
            int idxK5 = c.getColumnIndex(COL_KITCHEN5);
            while (c.moveToNext()) {
                long id = idxId >= 0 ? c.getLong(idxId) : -1;
                String header = idxHeader >= 0 ? c.getString(idxHeader) : "";
                String content = idxContent >= 0 ? c.getString(idxContent) : "";
                String footer = idxFooter >= 0 ? c.getString(idxFooter) : "";
                boolean thermal = isFlagColumn(c, idxThermal);
                boolean kitchen = isFlagColumn(c, idxKitchen);
                boolean k1 = isFlagColumn(c, idxK1);
                boolean k2 = isFlagColumn(c, idxK2);
                boolean k3 = isFlagColumn(c, idxK3);
                boolean k4 = isFlagColumn(c, idxK4);
                boolean k5 = isFlagColumn(c, idxK5);
                list.add(new PendingReceipt(id, header, content, footer, thermal, kitchen, k1, k2, k3, k4, k5));
            }
        } catch (Exception e) {
            Log.e(TAG, "getAllPending failed", e);
        }
        return list;
    }

    private static boolean isFlagColumn(Cursor c, int columnIndex) {
        if (columnIndex < 0) return false;
        if (c.isNull(columnIndex)) return false;
        try {
            long v = c.getLong(columnIndex);
            return v == 1;
        } catch (Exception ignored) { }
        String s = c.getString(columnIndex);
        return "1".equals(s != null ? s.trim() : "");
    }

    /**
     * Delete a receipt row by id. Idempotent (safe to call multiple times).
     */
    public void delete(long id) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_RECEIPTS, COL_ID + "=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.e(TAG, "delete failed for id=" + id, e);
        }
    }

    /**
     * Insert one row from a JSON object containing header, content, footer, ip,
     * receipt_flag, thermal, kitchen, kitchen1..kitchen5. Missing keys are stored as null.
     *
     * @return row id, or -1 on error
     */
    public long insertFromJson(JSONObject json) {
        if (json == null) return -1;
        ContentValues cv = new ContentValues();
        putOpt(cv, COL_HEADER, json.opt("header"));
        putOpt(cv, COL_CONTENT, json.opt("content"));
        putOpt(cv, COL_FOOTER, json.opt("footer"));
        putOpt(cv, COL_IP, json.opt("ip"));
        putOpt(cv, COL_RECEIPT_FLAG, json.opt("receipt_flag"));
        putOpt(cv, COL_THERMAL, json.opt("thermal"));
        putOpt(cv, COL_KITCHEN, json.opt("kitchen"));
        putOpt(cv, COL_KITCHEN1, json.opt("kitchen1"));
        putOpt(cv, COL_KITCHEN2, json.opt("kitchen2"));
        putOpt(cv, COL_KITCHEN3, json.opt("kitchen3"));
        putOpt(cv, COL_KITCHEN4, json.opt("kitchen4"));
        putOpt(cv, COL_KITCHEN5, json.opt("kitchen5"));
        try {
            SQLiteDatabase db = getWritableDatabase();
            long id = db.insert(TABLE_RECEIPTS, null, cv);
            if (id >= 0) Log.d(TAG, "Inserted receipt row id=" + id);
            return id;
        } catch (Exception e) {
            Log.e(TAG, "insertFromJson failed", e);
            return -1;
        }
    }

    private static void putOpt(ContentValues cv, String key, Object value) {
        if (value == null || value == JSONObject.NULL) return;
        if (value instanceof Number) cv.put(key, ((Number) value).longValue());
        else cv.put(key, value.toString());
    }
}
