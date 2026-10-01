package com.tvbox.web.server;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tvbox.web.engine.SpiderManager;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class WebServer extends NanoHTTPD {

    private static final String TAG = "WebServer";
    private final Context mContext;
    private final SpiderManager mSpiderManager;
    private final Gson mGson = new Gson();
    private final OkHttpClient mHttp = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    public WebServer(Context context, int port) {
        super(port);
        this.mContext = context;
        this.mSpiderManager = SpiderManager.get(context);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // Handle CORS Preflight
        if (method == Method.OPTIONS) {
            Response resp = newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "");
            addCorsHeaders(resp);
            return resp;
        }

        try {
            // 1. Static Web UI
            if (uri.equals("/") || uri.equals("/index.html")) {
                InputStream is = mContext.getAssets().open("web/index.html");
                Response resp = newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", is);
                addCorsHeaders(resp);
                return resp;
            }

            // 1.1 Cloud Drive Config Center Web UI (/website)
            if (uri.equals("/website") || uri.equals("/website/") || uri.equals("/website/index.html")) {
                InputStream is = mContext.getAssets().open("web/website.html");
                Response resp = newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", is);
                addCorsHeaders(resp);
                return resp;
            }

            // 2. Stream Proxy with Range & Anti-Hotlinking Injection
            if (uri.startsWith("/api/stream")) {
                return handleStreamProxy(session);
            }

            // 3. Fetch external source config / proxy
            if (uri.startsWith("/api/fetch_source")) {
                return handleFetchSource(session);
            }

            // 4. Config API
            if (uri.equals("/api/config")) {
                JsonObject cfg = mSpiderManager.getConfig();
                String json = cfg != null ? cfg.toString() : "{\"sites\":[]}";
                Response resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json);
                addCorsHeaders(resp);
                return resp;
            }

            // 4. Spider Method Execution: /api/spider/:key/:action
            if (uri.startsWith("/api/spider/")) {
                return handleSpiderCall(session, uri);
            }

            // 404 Fallback
            Response resp = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            addCorsHeaders(resp);
            return resp;
        } catch (Exception e) {
            Log.e(TAG, "Server error: " + e.getMessage(), e);
            Response resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: " + e.getMessage());
            addCorsHeaders(resp);
            return resp;
        }
    }

    private Response handleSpiderCall(IHTTPSession session, String uri) {
        try {
            // URI format: /api/spider/<key>/<action>  e.g. /api/spider/wogg/home
            String path = uri.substring("/api/spider/".length());
            String[] parts = path.split("/");
            if (parts.length < 2) {
                return jsonResponse("{\"error\":\"Invalid spider URL format\"}", Response.Status.BAD_REQUEST);
            }

            String siteKey = parts[0];
            String action = parts[parts.length - 1]; // e.g. home, category, detail, play, search

            // Read POST body JSON
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String postData = files.get("postData");
            JsonObject body = TextUtils.isEmpty(postData) ? new JsonObject() : mGson.fromJson(postData, JsonObject.class);

            // Locate site config
            JsonObject site = findSiteByKey(siteKey);
            if (site == null) {
                return jsonResponse("{\"error\":\"Site not found: " + siteKey + "\"}", Response.Status.NOT_FOUND);
            }

            String apiClass = site.has("api") ? site.get("api").getAsString() : "";
            String ext = site.has("ext") ? site.get("ext").getAsString() : "";

            String result = "{}";
            switch (action) {
                case "init":
                    result = "{\"status\":\"ok\"}";
                    break;
                case "home":
                    result = mSpiderManager.callHome(apiClass, ext);
                    break;
                case "category":
                    String tid = body.has("tid") ? body.get("tid").getAsString() : "1";
                    String pg = body.has("pg") ? body.get("pg").getAsString() : "1";
                    result = mSpiderManager.callCategory(apiClass, ext, tid, pg);
                    break;
                case "detail":
                    String vodId = body.has("id") ? body.get("id").getAsString() : "";
                    result = mSpiderManager.callDetail(apiClass, ext, vodId);
                    break;
                case "play":
                    String flag = body.has("flag") ? body.get("flag").getAsString() : "";
                    String playId = body.has("id") ? body.get("id").getAsString() : "";
                    result = mSpiderManager.callPlay(apiClass, ext, flag, playId);
                    break;
                case "search":
                    String wd = body.has("wd") ? body.get("wd").getAsString() : "";
                    String searchPg = body.has("pg") ? body.get("pg").getAsString() : "1";
                    result = mSpiderManager.callSearch(apiClass, ext, wd, searchPg);
                    break;
            }

            return jsonResponse(result, Response.Status.OK);
        } catch (Exception e) {
            Log.e(TAG, "Spider call error: " + e.getMessage(), e);
            return jsonResponse("{\"error\":\"" + e.getMessage() + "\"}", Response.Status.INTERNAL_ERROR);
        }
    }

    private JsonObject findSiteByKey(String key) {
        JsonObject cfg = mSpiderManager.getConfig();
        if (cfg == null || !cfg.has("sites")) return null;
        JsonArray sites = cfg.getAsJsonArray("sites");
        for (int i = 0; i < sites.size(); i++) {
            JsonObject s = sites.get(i).getAsJsonObject();
            if (s.has("key") && s.get("key").getAsString().equals(key)) {
                return s;
            }
        }
        return null;
    }

    private Response handleStreamProxy(IHTTPSession session) {
        try {
            Map<String, String> parms = session.getParms();
            String targetUrl = parms.get("url");
            if (TextUtils.isEmpty(targetUrl)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url");
            }
            targetUrl = URLDecoder.decode(targetUrl, "UTF-8");

            String headersStr = parms.get("headers");
            Map<String, String> customHeaders = new HashMap<>();
            if (!TextUtils.isEmpty(headersStr)) {
                JsonObject hObj = mGson.fromJson(URLDecoder.decode(headersStr, "UTF-8"), JsonObject.class);
                for (String k : hObj.keySet()) {
                    customHeaders.put(k, hObj.get(k).getAsString());
                }
            }

            Request.Builder reqBuilder = new Request.Builder().url(targetUrl);
            for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                reqBuilder.header(e.getKey(), e.getValue());
            }

            // Range header pass-through
            String clientRange = session.getHeaders().get("range");
            if (!TextUtils.isEmpty(clientRange)) {
                reqBuilder.header("Range", clientRange);
            }

            okhttp3.Response okResp = mHttp.newCall(reqBuilder.build()).execute();
            if (okResp.body() == null) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Empty proxy response");
            }

            String contentType = okResp.header("Content-Type", "application/octet-stream");
            boolean isM3u8 = contentType.contains("mpegurl") || targetUrl.toLowerCase().endsWith(".m3u8");

            if (isM3u8) {
                // Rewrite M3U8 lines to pipe through proxy
                String rawM3u8 = okResp.body().string();
                String baseUrl = targetUrl.substring(0, targetUrl.lastIndexOf('/') + 1);
                StringBuilder sb = new StringBuilder();

                BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(rawM3u8.getBytes(StandardCharsets.UTF_8))));
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        sb.append(line).append("\n");
                        continue;
                    }

                    // Resolve absolute URL for TS segment
                    String segUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://")
                            ? trimmed
                            : baseUrl + trimmed;

                    String proxiedSeg = "/api/stream?url=" + URLEncoder.encode(segUrl, "UTF-8")
                            + "&headers=" + (headersStr != null ? headersStr : "");
                    sb.append(proxiedSeg).append("\n");
                }

                Response resp = newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", sb.toString());
                addCorsHeaders(resp);
                return resp;
            }

            // Direct TS / MP4 streaming
            InputStream is = okResp.body().byteStream();
            long contentLength = okResp.body().contentLength();
            Response resp = contentLength >= 0
                    ? newFixedLengthResponse(Response.Status.OK, contentType, is, contentLength)
                    : newChunkedResponse(Response.Status.OK, contentType, is);

            if (okResp.header("Content-Range") != null) {
                resp.addHeader("Content-Range", okResp.header("Content-Range"));
            }
            if (okResp.header("Accept-Ranges") != null) {
                resp.addHeader("Accept-Ranges", okResp.header("Accept-Ranges"));
            }

            addCorsHeaders(resp);
            return resp;
        } catch (Exception e) {
            Log.e(TAG, "Stream proxy error: " + e.getMessage(), e);
            Response resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy Error: " + e.getMessage());
            addCorsHeaders(resp);
            return resp;
        }
    }

    private Response handleFetchSource(IHTTPSession session) {
        try {
            Map<String, String> parms = session.getParms();
            String targetUrl = parms.get("url");
            if (TextUtils.isEmpty(targetUrl)) {
                return jsonResponse("{\"error\":\"Missing url parameter\"}", Response.Status.BAD_REQUEST);
            }
            targetUrl = URLDecoder.decode(targetUrl, "UTF-8");

            Request req = new Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();

            okhttp3.Response okResp = mHttp.newCall(req).execute();
            if (!okResp.isSuccessful() || okResp.body() == null) {
                return jsonResponse("{\"error\":\"Failed to fetch source: HTTP " + okResp.code() + "\"}", Response.Status.INTERNAL_ERROR);
            }

            String content = okResp.body().string().trim();
            // Handle Base64 encoded configs if applicable
            if (!content.startsWith("{") && !content.startsWith("[")) {
                try {
                    byte[] decoded = android.util.Base64.decode(content, android.util.Base64.DEFAULT);
                    String decodedStr = new String(decoded, StandardCharsets.UTF_8).trim();
                    if (decodedStr.startsWith("{") || decodedStr.startsWith("[")) {
                        content = decodedStr;
                    }
                } catch (Exception ignored) {
                }
            }

            final String finalContent = content;
            final String finalUrl = targetUrl;
            mSpiderManager.loadConfigContent(finalUrl, finalContent);

            Response resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", content);
            addCorsHeaders(resp);
            return resp;
        } catch (Exception e) {
            Log.e(TAG, "fetch_source error: " + e.getMessage(), e);
            return jsonResponse("{\"error\":\"" + e.getMessage() + "\"}", Response.Status.INTERNAL_ERROR);
        }
    }

    private Response jsonResponse(String json, Response.IStatus status) {
        Response resp = newFixedLengthResponse(status, "application/json; charset=utf-8", json);
        addCorsHeaders(resp);
        return resp;
    }

    private void addCorsHeaders(Response resp) {
        resp.addHeader("Access-Control-Allow-Origin", "*");
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
        resp.addHeader("Access-Control-Allow-Headers", "*");
        resp.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges");
    }
}
