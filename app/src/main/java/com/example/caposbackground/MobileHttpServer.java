package com.example.caposbackground;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

/**
 * Lightweight HTTP server that runs inside the app and can receive HTTP requests.
 * Override handleRequest() or extend this class to customize responses.
 */
public class MobileHttpServer extends NanoHTTPD {

    private static final String TAG = "MobileHttpServer";
    public static final int DEFAULT_PORT = 8000;
    /** Key in params for raw POST body (e.g. JSON). */
    public static final String PARAM_RAW_BODY = "body";

    private final Context appContext;
    private final ReceiptDbHelper receiptDbHelper;

    /** Use this constructor from the service so receipt JSON can be saved to SQLite. */
    public MobileHttpServer(Context context, int port) {
        super(port);
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.receiptDbHelper = this.appContext != null ? new ReceiptDbHelper(this.appContext) : null;
    }

    public MobileHttpServer(int port) {
        this(null, port);
    }

    public MobileHttpServer() {
        this(DEFAULT_PORT);
    }

    /** Enqueue receipt to print queues (thermal, kitchen, kitchen1..5) based on JSON flags. */
    private void enqueuePrintJobIfNeeded(JSONObject bodyJson, long id) {
        if (appContext == null || bodyJson == null) return;
        boolean thermal = isFlagSet(bodyJson, "thermal");
        boolean kitchen = isFlagSet(bodyJson, "kitchen");
        boolean k1 = isFlagSet(bodyJson, "kitchen1");
        boolean k2 = isFlagSet(bodyJson, "kitchen2");
        boolean k3 = isFlagSet(bodyJson, "kitchen3");
        boolean k4 = isFlagSet(bodyJson, "kitchen4");
        boolean k5 = isFlagSet(bodyJson, "kitchen5");
        if (!thermal && !kitchen && !k1 && !k2 && !k3 && !k4 && !k5) return;
        String header = bodyJson.optString("header", "");
        String content = bodyJson.optString("content", "");
        String footer = bodyJson.optString("footer", "");
        PrintJob job = new PrintJob(id, header, content, footer);
        PrintQueueManager.getInstance(appContext).enqueue(job, thermal, kitchen, k1, k2, k3, k4, k5);
    }

    private static boolean isFlagSet(JSONObject json, String key) {
        if (json == null || key == null) return false;
        Object v = json.opt(key);
        if (v == null) return false;
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        return "1".equals(v.toString().trim());
    }

    @Override
    public Response serve(@NonNull IHTTPSession session) {
        try {
            String method = session.getMethod().name();
            String uri = session.getUri();
            Map<String, String> headers = session.getHeaders();

            // CORS preflight: browser sends OPTIONS before POST from another origin
            if (NanoHTTPD.Method.OPTIONS.equals(session.getMethod())) {
                return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""), "*");
            }

            // For POST/PUT, read and parse body (form-urlencoded or JSON)
            Map<String, String> params = new HashMap<>(session.getParms());
            JSONObject bodyJson = null;
            if (NanoHTTPD.Method.POST.equals(session.getMethod()) || NanoHTTPD.Method.PUT.equals(session.getMethod())) {
                String contentType = getHeaderIgnoreCase(headers, "content-type");
                String rawBody = readRawBody(session, headers);
                if (rawBody != null && !rawBody.isEmpty()) {
                    params.put(PARAM_RAW_BODY, rawBody);
                    if (contentType != null && contentType.toLowerCase().contains("application/json")) {
                        try {
                            bodyJson = new JSONObject(rawBody.trim());
                            params.putAll(parseJsonBody(rawBody));
                        } catch (Exception e) {
                            Log.e(TAG, "parseJsonBody failed", e);
                        }
                    } else {
                        params.putAll(parseFormBody(rawBody));
                    }
                }
            }

            if (bodyJson != null && receiptDbHelper != null) {
                long id = receiptDbHelper.insertFromJson(bodyJson);
                if (id >= 0) {
                    Log.d(TAG, "Saved receipt to SQLite row id=" + id);
                    enqueuePrintJobIfNeeded(bodyJson, id);
                }
            }

            Response response = handleRequest(session, method, uri, params, headers, bodyJson);
            return addCorsHeaders(response, "*");
        } catch (Throwable t) {
            Log.e(TAG, "serve() failed", t);
            Response err = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                    "Error: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            return addCorsHeaders(err, "*");
        }
    }

    private static Response addCorsHeaders(Response response, String allowOrigin) {
        response.addHeader("Access-Control-Allow-Origin", allowOrigin);
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.addHeader("Access-Control-Max-Age", "86400");
        return response;
    }

    /** Get header value case-insensitively. */
    private static String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        String lower = name.toLowerCase();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().equals(lower)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Read raw request body as UTF-8 string. Uses up to 30s socket timeout (set on server). */
    private static String readRawBody(IHTTPSession session, Map<String, String> headers) {
        StringBuilder sb = new StringBuilder(4096);
        try {
            String contentLength = getHeaderIgnoreCase(headers, "content-length");
            if (contentLength == null) return "";
            int len = Integer.parseInt(contentLength.trim());
            if (len <= 0 || len > 1024 * 1024) return "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(session.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[2048];
                int total = 0;
                int r;
                while (total < len && (r = reader.read(buf, 0, Math.min(buf.length, len - total))) > 0) {
                    sb.append(buf, 0, r);
                    total += r;
                }
            }
            return sb.toString();
        } catch (SocketTimeoutException e) {
            Log.w(TAG, "readRawBody timed out, returning " + sb.length() + " bytes read so far");
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "readRawBody failed", e);
            return sb.length() > 0 ? sb.toString() : "";
        }
    }

    /** Parse application/json body and put top-level keys into params (values as string). */
    private static Map<String, String> parseJsonBody(String rawBody) {
        Map<String, String> out = new HashMap<>();
        if (rawBody == null || rawBody.isEmpty()) return out;
        try {
            JSONObject obj = new JSONObject(rawBody.trim());
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String key = it.next();
                Object val = obj.opt(key);
                if (val != null) out.put(key, val.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "parseJsonBody failed", e);
        }
        return out;
    }

    /** Parse application/x-www-form-urlencoded body. */
    private static Map<String, String> parseFormBody(String rawBody) {
        Map<String, String> out = new HashMap<>();
        if (rawBody == null) return out;
        for (String pair : rawBody.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String key = decode(pair.substring(0, eq).trim());
                String value = decode(pair.substring(eq + 1).trim());
                if (!key.isEmpty()) out.put(key, value);
            }
        }
        return out;
    }

    /** Overload for subclasses that don't need JSON; delegates to the 6-arg version with bodyJson=null. */
    protected Response handleRequest(IHTTPSession session, String method, String uri,
                                     Map<String, String> params, Map<String, String> headers) {
        return handleRequest(session, method, uri, params, headers, null);
    }

    /**
     * Override this to implement your API. For JSON POST/PUT, {@code bodyJson} is the parsed body; otherwise null.
     * Use bodyJson.optString("key"), bodyJson.optJSONObject("nested"), bodyJson.optJSONArray("items"), etc.
     */
    protected Response handleRequest(IHTTPSession session, String method, String uri,
                                     Map<String, String> params, Map<String, String> headers,
                                     JSONObject bodyJson) {
        // Simple path-based actions (you can add more or override handleRequest)
        if ("/".equals(uri) || "/status".equals(uri) || uri.isEmpty()) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT,
                    "OK\nmethod=" + method + "\nuri=" + uri + "\nparams=" + params);
        }
        if ("/health".equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK");
        }

        // Default: echo request info; when body is JSON, bodyJson has parsed object (e.g. bodyJson.optString("key"))
        StringBuilder body = new StringBuilder();
        body.append("method=").append(method).append("\n");
        body.append("uri=").append(uri).append("\n");
        body.append("params=").append(params).append("\n");
        if (bodyJson != null) {
            body.append("bodyJson=").append(bodyJson.toString()).append("\n");
        } else if (params.containsKey(PARAM_RAW_BODY)) {
            body.append("postBody=").append(params.get(PARAM_RAW_BODY)).append("\n");
        }
        body.append("headers=").append(headers).append("\n");
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, body.toString());
    }


    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
