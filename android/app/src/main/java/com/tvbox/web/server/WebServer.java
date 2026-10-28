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
import java.io.SequenceInputStream;
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

            // 1.0 PWA & Static Assets
            if (uri.equals("/manifest.webmanifest") || uri.equals("/manifest.json")) {
                InputStream is = mContext.getAssets().open("web/manifest.webmanifest");
                Response resp = newChunkedResponse(Response.Status.OK, "application/manifest+json; charset=utf-8", is);
                addCorsHeaders(resp);
                return resp;
            }
            if (uri.equals("/sw.js")) {
                InputStream is = mContext.getAssets().open("web/sw.js");
                Response resp = newChunkedResponse(Response.Status.OK, "application/javascript; charset=utf-8", is);
                addCorsHeaders(resp);
                return resp;
            }
            if (uri.equals("/icon-192.png") || uri.equals("/icon-512.png") || uri.equals("/favicon.ico")) {
                String assetFile = uri.substring(1);
                InputStream is = mContext.getAssets().open("web/" + assetFile);
                Response resp = newChunkedResponse(Response.Status.OK, uri.endsWith(".ico") ? "image/x-icon" : "image/png", is);
                addCorsHeaders(resp);
                return resp;
            }
            if (uri.equals("/tailwind.min.js") || uri.equals("/hls.min.js")) {
                String assetFile = uri.substring(1);
                InputStream is = mContext.getAssets().open("web/" + assetFile);
                Response resp = newChunkedResponse(Response.Status.OK, "application/javascript; charset=utf-8", is);
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

            // 1.2 Cloud Drive Config Center API (/website/api/*)
            if (uri.startsWith("/website/api/")) {
                return handleWebsiteApi(session, uri);
            }

            // 2. Stream Proxy with Range & Anti-Hotlinking Injection
            if (uri.startsWith("/api/stream")) {
                return handleStreamProxy(session);
            }

            // 2.05 Media tracks inspector endpoint (/api/media_tracks)
            if (uri.startsWith("/api/media_tracks")) {
                return handleMediaTracks(session);
            }

            // 2.1 Native Spider /proxy endpoint
            if (uri.startsWith("/proxy")) {
                return handleNativeSpiderProxy(session);
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

            String siteKey = URLDecoder.decode(parts[0], "UTF-8");
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
                    HashMap<String, String> extendMap = new HashMap<>();
                    if (body.has("extend") && body.get("extend").isJsonObject()) {
                        JsonObject extObj = body.getAsJsonObject("extend");
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : extObj.entrySet()) {
                            if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
                                extendMap.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }
                    }
                    result = mSpiderManager.callCategory(apiClass, ext, tid, pg, extendMap);
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
        if (key == null) return null;
        JsonObject cfg = mSpiderManager.getConfig();
        if (cfg == null || !cfg.has("sites")) return null;
        JsonArray sites = cfg.getAsJsonArray("sites");
        String trimmedKey = key.trim();
        for (int i = 0; i < sites.size(); i++) {
            JsonObject s = sites.get(i).getAsJsonObject();
            String sKey = s.has("key") ? s.get("key").getAsString() : "";
            String sName = s.has("name") ? s.get("name").getAsString() : "";
            if (sKey.equalsIgnoreCase(trimmedKey) || sName.equalsIgnoreCase(trimmedKey)
                    || sKey.contains(trimmedKey) || trimmedKey.contains(sKey)) {
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
            if (contentType.equals("application/octet-stream") || contentType.equals("binary/octet-stream")) {
                if (targetUrl.toLowerCase().contains(".mp4")) {
                    contentType = "video/mp4";
                } else if (targetUrl.toLowerCase().contains(".mkv")) {
                    contentType = "video/x-matroska";
                }
            }
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
                        if (trimmed.startsWith("#") && (trimmed.contains("URI=\"") || trimmed.contains("URI='"))) {
                            java.util.regex.Pattern p = java.util.regex.Pattern.compile("URI=[\"']([^\"']+)[\"']");
                            java.util.regex.Matcher m = p.matcher(trimmed);
                            StringBuffer sbLine = new StringBuffer();
                            while (m.find()) {
                                String rawUri = m.group(1);
                                String absUri = rawUri.startsWith("http://") || rawUri.startsWith("https://")
                                        ? rawUri
                                        : baseUrl + rawUri;
                                String proxied = "/api/stream?url=" + URLEncoder.encode(absUri, "UTF-8")
                                        + "&headers=" + (headersStr != null ? headersStr : "");
                                m.appendReplacement(sbLine, "URI=\"" + java.util.regex.Matcher.quoteReplacement(proxied) + "\"");
                            }
                            m.appendTail(sbLine);
                            sb.append(sbLine.toString()).append("\n");
                            continue;
                        }
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
            String audioTrackStr = parms.get("audio_track");
            boolean hasAudioTrack = !TextUtils.isEmpty(audioTrackStr);
            int audioTrack = -1;
            if (hasAudioTrack) {
                try { audioTrack = Integer.parseInt(audioTrackStr); } catch (NumberFormatException ignored) {}
            }
            boolean isStartOfFile = clientRange == null || clientRange.startsWith("bytes=0-");
            boolean isSmallProbe = false;
            if (clientRange != null && clientRange.startsWith("bytes=0-")) {
                try {
                    long end = Long.parseLong(clientRange.substring("bytes=0-".length()).trim());
                    if (end < 131072) {
                        isSmallProbe = true;
                    }
                } catch (Exception ignored) {}
            }
            boolean isMp4 = contentType.equals("video/mp4") || targetUrl.toLowerCase().contains(".mp4");
            boolean isStreamEligible = isMp4 || contentType.equals("video/x-matroska") || targetUrl.toLowerCase().contains(".mkv") || !isM3u8;

            if (!isSmallProbe && hasAudioTrack && audioTrack >= 0 && isStartOfFile && isStreamEligible) {
                // Stream-level audio track swap: fetch probe, swap audio track boxes, stream rest.
                okResp.close(); // release the initial (unused) upstream response
                byte[] probe = fetchRange(targetUrl, customHeaders, 0, 524287);
                if (probe != null && probe.length > 16) {
                    int offset = detectQuarkPrefix(probe);

                    // Check MKV (EBML 0x1A45DFA3)
                    if (offset + 4 <= probe.length
                            && (probe[offset] & 0xFF) == 0x1A
                            && (probe[offset + 1] & 0xFF) == 0x45
                            && (probe[offset + 2] & 0xFF) == 0xDF
                            && (probe[offset + 3] & 0xFF) == 0xA3) {
                        byte[] swapped = swapMkvAudioTrack(probe, audioTrack);
                        long totalSize = -1;
                        String cr = okResp.header("Content-Range");
                        if (cr != null) {
                            int slash = cr.lastIndexOf('/');
                            if (slash >= 0) {
                                try { totalSize = Long.parseLong(cr.substring(slash + 1).trim()); } catch (Exception ignored) {}
                            }
                        }
                        InputStream head = new ByteArrayInputStream(swapped);
                        InputStream rest = null;
                        if (totalSize > swapped.length) {
                            rest = fetchStream(targetUrl, customHeaders, swapped.length, totalSize - 1);
                        }
                        InputStream combined = rest != null ? new SequenceInputStream(head, rest) : head;
                        long clientEnd = -1;
                        if (clientRange != null && clientRange.startsWith("bytes=0-")) {
                            try { clientEnd = Long.parseLong(clientRange.substring("bytes=0-".length()).trim()); } catch (Exception ignored) {}
                        }
                        long fullLen = totalSize > 0 ? totalSize : swapped.length;
                        long respLen = (clientEnd >= 0 && clientEnd < fullLen) ? (clientEnd + 1) : fullLen;
                        Response resp = newFixedLengthResponse(Response.Status.OK, "video/x-matroska", combined, respLen);
                        resp.addHeader("Accept-Ranges", "bytes");
                        addCorsHeaders(resp);
                        return resp;
                    }

                    long[] moov = findMoovRange(probe, offset);
                    if (moov != null && moov[1] > 0 && moov[1] <= 32 * 1024 * 1024) {
                        long moovStart = moov[0];
                        long moovSize = moov[1];
                        long moovEnd = moovStart + moovSize;
                        byte[] fullMoov = fetchRange(targetUrl, customHeaders, 0, moovEnd - 1);
                        if (fullMoov != null && fullMoov.length >= moovEnd) {
                            byte[] swapped = swapMp4AudioTrack(fullMoov, audioTrack);
                            if (swapped != fullMoov) {
                                // Determine total file size
                                long totalSize = -1;
                                String cr = okResp.header("Content-Range");
                                if (cr != null) {
                                    int slash = cr.lastIndexOf('/');
                                    if (slash >= 0) {
                                        try { totalSize = Long.parseLong(cr.substring(slash + 1).trim()); }
                                        catch (NumberFormatException ignored) {}
                                    }
                                }
                                // Build combined stream: swapped moov + rest
                                InputStream head = new ByteArrayInputStream(swapped);
                                InputStream rest = null;
                                if (totalSize > moovEnd) {
                                    rest = fetchStream(targetUrl, customHeaders, moovEnd, totalSize - 1);
                                }
                                InputStream combined = rest != null
                                        ? new SequenceInputStream(head, rest)
                                        : head;
                                // Respect a bounded client range (e.g. bytes=0-N)
                                long clientEnd = -1; // -1 = open-ended / whole file
                                if (clientRange != null && clientRange.startsWith("bytes=0-")) {
                                    try {
                                        clientEnd = Long.parseLong(clientRange.substring("bytes=0-".length()).trim());
                                    } catch (NumberFormatException ignored) {}
                                }
                                long fullLen = totalSize > 0 ? totalSize : swapped.length;
                                long respLen = (clientEnd >= 0 && clientEnd < fullLen) ? (clientEnd + 1) : fullLen;
                                Response resp = newFixedLengthResponse(Response.Status.OK, contentType, combined, respLen);
                                resp.addHeader("Accept-Ranges", "bytes");
                                addCorsHeaders(resp);
                                return resp;
                            }
                        }
                    }
                }
            }

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

    private Response handleMediaTracks(IHTTPSession session) {
        try {
            Map<String, String> parms = session.getParms();
            String targetUrl = parms.get("url");
            if (TextUtils.isEmpty(targetUrl)) {
                return jsonResponse("{\"success\":false,\"error\":\"Missing url\"}", Response.Status.BAD_REQUEST);
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

            JsonObject result = new JsonObject();
            JsonArray audioTracks = new JsonArray();
            JsonArray subtitleTracks = new JsonArray();

            // 1. If HLS (.m3u8)
            if (targetUrl.toLowerCase().contains(".m3u8")) {
                Request.Builder reqBuilder = new Request.Builder().url(targetUrl);
                for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                    reqBuilder.header(e.getKey(), e.getValue());
                }
                okhttp3.Response okResp = mHttp.newCall(reqBuilder.build()).execute();
                if (okResp.body() != null) {
                    String body = okResp.body().string();
                    String[] lines = body.split("\n");
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                            JsonObject t = new JsonObject();
                            t.addProperty("index", audioTracks.size());
                            String name = extractAttr(trimmed, "NAME");
                            String lang = extractAttr(trimmed, "LANGUAGE");
                            t.addProperty("name", !TextUtils.isEmpty(name) ? name : "中文 (音频 " + (audioTracks.size() + 1) + ")");
                            t.addProperty("desc", "AAC 音频");
                            t.addProperty("lang", lang);
                            t.addProperty("codec", "AAC");
                            audioTracks.add(t);
                        } else if (trimmed.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES")) {
                            JsonObject t = new JsonObject();
                            t.addProperty("index", subtitleTracks.size());
                            String name = extractAttr(trimmed, "NAME");
                            String lang = extractAttr(trimmed, "LANGUAGE");
                            t.addProperty("name", !TextUtils.isEmpty(name) ? name : "内置字幕 " + (subtitleTracks.size() + 1));
                            t.addProperty("desc", "内置字幕");
                            t.addProperty("lang", lang);
                            subtitleTracks.add(t);
                        }
                    }
                }
                result.addProperty("container", "hls");
                result.add("audioTracks", audioTracks);
                result.add("subtitleTracks", subtitleTracks);
                return jsonResponse(result.toString(), Response.Status.OK);
            }

            // 2. Direct MP4 / MKV Range fetch
            byte[] bytes = fetchRange(targetUrl, customHeaders, 0, 524287); // 512KB first pass
            if (bytes != null && bytes.length > 16) {
                // Detect Quark 8-byte prefix (0x89504E47)
                int offset = 0;
                if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50 && (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47) {
                    offset = 8;
                }
                int base = offset;
                if (base + 8 <= bytes.length) {
                    // Check MKV (EBML 0x1A45DFA3)
                    if ((bytes[base] & 0xFF) == 0x1A && (bytes[base + 1] & 0xFF) == 0x45
                            && (bytes[base + 2] & 0xFF) == 0xDF && (bytes[base + 3] & 0xFF) == 0xA3) {
                        result.addProperty("container", "mkv");
                        parseMkvTrackBoxes(bytes, base, audioTracks, subtitleTracks);
                    } else {
                        char c4 = (char) bytes[base + 4];
                        char c5 = (char) bytes[base + 5];
                        char c6 = (char) bytes[base + 6];
                        char c7 = (char) bytes[base + 7];
                        if ((c4 == 'f' && c5 == 't' && c6 == 'y' && c7 == 'p')
                                || (c4 == 'm' && c5 == 'o' && c6 == 'o' && c7 == 'v')
                                || (c4 == 'w' && c5 == 'i' && c6 == 'd' && c7 == 'e')) {
                            // Find moov declared size from top-level boxes
                            int moovPos = -1;
                            long moovDeclaredSize = 0;
                            int p = base;
                            while (p + 8 <= bytes.length) {
                                int rawSize = readIntBE(bytes, p);
                                char t1 = (char) bytes[p + 4];
                                char t2 = (char) bytes[p + 5];
                                char t3 = (char) bytes[p + 6];
                                char t4 = (char) bytes[p + 7];
                                long size = rawSize;
                                if (rawSize == 1 && p + 16 <= bytes.length) {
                                    size = readLongBE(bytes, p + 8);
                                }
                                if (rawSize == 0) size = bytes.length - p;
                                if (t1 == 'm' && t2 == 'o' && t3 == 'o' && t4 == 'v') {
                                    moovPos = p;
                                    moovDeclaredSize = size;
                                    break;
                                }
                                if (size < 8 || p + size > bytes.length) break;
                                p += size;
                            }

                            byte[] moovBuf = bytes;
                            if (moovPos != -1 && moovDeclaredSize > 0 && moovPos + moovDeclaredSize > bytes.length && moovDeclaredSize <= 32 * 1024 * 1024) {
                                // moov truncated in first pass — re-fetch full moov
                                long need = offset + moovPos + moovDeclaredSize + 32;
                                byte[] bigger = fetchRange(targetUrl, customHeaders, 0, need - 1);
                                if (bigger != null && bigger.length > moovPos + 8) {
                                    moovBuf = bigger;
                                }
                            }

                            result.addProperty("container", "mp4");
                            parseMp4TrakBoxes(moovBuf, offset, audioTracks, subtitleTracks);
                        }
                    }
                }
            }

            result.add("audioTracks", audioTracks);
            result.add("subtitleTracks", subtitleTracks);
            return jsonResponse(result.toString(), Response.Status.OK);
        } catch (Exception e) {
            Log.e(TAG, "handleMediaTracks error: " + e.getMessage(), e);
            return jsonResponse("{\"success\":false,\"audioTracks\":[],\"subtitleTracks\":[]}", Response.Status.OK);
        }
    }

    private static String extractAttr(String line, String attr) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(attr + "=[\"']?([^\"',]+)[\"']?").matcher(line);
        return m.find() ? m.group(1) : "";
    }

    private byte[] fetchRange(String url, Map<String, String> customHeaders, long start, long end) {
        try {
            Request.Builder reqBuilder = new Request.Builder().url(url);
            for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                reqBuilder.header(e.getKey(), e.getValue());
            }
            reqBuilder.header("Range", "bytes=" + start + "-" + end);
            okhttp3.Response resp = mHttp.newCall(reqBuilder.build()).execute();
            if (resp.body() != null) {
                byte[] b = resp.body().bytes();
                resp.close();
                return b;
            }
            resp.close();
        } catch (Exception e) {
            Log.e(TAG, "fetchRange error: " + e.getMessage());
        }
        return null;
    }

    /** Fetch a byte range and return its body as a streaming InputStream (caller must consume). */
    private InputStream fetchStream(String url, Map<String, String> customHeaders, long start, long end) {
        try {
            Request.Builder reqBuilder = new Request.Builder().url(url);
            for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                reqBuilder.header(e.getKey(), e.getValue());
            }
            reqBuilder.header("Range", "bytes=" + start + "-" + end);
            okhttp3.Response resp = mHttp.newCall(reqBuilder.build()).execute();
            if (resp.body() != null) {
                return resp.body().byteStream();
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchStream error: " + e.getMessage());
        }
        return null;
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long readLongBE(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }

    private static void parseMp4TrakBoxes(byte[] buf, int offset, JsonArray audioTracks, JsonArray subtitleTracks) {
        int base = offset;
        int idx = base;
        int moovOffset = -1;
        long moovSize = 0;
        while (idx < buf.length - 8) {
            int size = readIntBE(buf, idx);
            if (buf[idx + 4] == 'm' && buf[idx + 5] == 'o' && buf[idx + 6] == 'o' && buf[idx + 7] == 'v') {
                moovOffset = idx;
                moovSize = (size == 1 && idx + 16 <= buf.length) ? readLongBE(buf, idx + 8) : size;
                break;
            }
            if (size <= 0) break;
            if (size == 1) {
                // 64-bit size — read it
                if (idx + 16 > buf.length) break;
                size = (int) Math.min(readLongBE(buf, idx + 8), Integer.MAX_VALUE);
            }
            if (size < 8 || idx + size > buf.length) break;
            idx += size;
        }
        if (moovOffset == -1) return;

        long moovEnd = Math.min(buf.length, moovOffset + moovSize);
        int tidx = moovOffset + 8;
        while (tidx < moovEnd - 8) {
            int tsize = readIntBE(buf, tidx);
            if (tsize == 1) {
                if (tidx + 16 > buf.length) break;
                tsize = (int) Math.min(readLongBE(buf, tidx + 8), Integer.MAX_VALUE);
            }
            if (buf[tidx + 4] == 't' && buf[tidx + 5] == 'r' && buf[tidx + 6] == 'a' && buf[tidx + 7] == 'k') {
                // Find hdlr handler type
                String hdlrType = "";
                int trakEnd = Math.min(buf.length, tidx + tsize);
                for (int i = tidx + 8; i < trakEnd - 16; i++) {
                    if (buf[i] == 'h' && buf[i + 1] == 'd' && buf[i + 2] == 'l' && buf[i + 3] == 'r') {
                        hdlrType = new String(buf, i + 12, 4, StandardCharsets.ISO_8859_1);
                        break;
                    }
                }

                // tkhd flags: version(1)+flags(3) at box data start
                boolean enabled = true;
                for (int i = tidx + 8; i < trakEnd - 8; i++) {
                    if (buf[i] == 't' && buf[i + 1] == 'k' && buf[i + 2] == 'h' && buf[i + 3] == 'd') {
                        int flags = ((buf[i + 5] & 0xFF) << 16) | ((buf[i + 6] & 0xFF) << 8) | (buf[i + 7] & 0xFF);
                        enabled = (flags & 0x1) != 0;
                        break;
                    }
                }

                if ("soun".equals(hdlrType)) {
                    JsonObject a = new JsonObject();
                    int aIdx = audioTracks.size();
                    a.addProperty("index", aIdx);
                    a.addProperty("trackId", aIdx + 2);
                    a.addProperty("name", "音轨 " + (aIdx + 1) + ": 中文 (立体声)" + (enabled ? " [默认]" : ""));
                    a.addProperty("desc", "中文, 立体声, AAC");
                    a.addProperty("codec", "AAC");
                    a.addProperty("channels", 2);
                    a.addProperty("lang", "chi");
                    a.addProperty("enabled", enabled);
                    audioTracks.add(a);
                } else if ("sbtl".equals(hdlrType) || "text".equals(hdlrType)) {
                    JsonObject s = new JsonObject();
                    int sIdx = subtitleTracks.size();
                    s.addProperty("index", sIdx);
                    s.addProperty("name", "内置中文字幕 " + (sIdx + 1));
                    s.addProperty("desc", "内置中文字幕");
                    s.addProperty("lang", "zh");
                    subtitleTracks.add(s);
                }
            }
            if (tsize <= 0) break;
            tidx += tsize;
        }
    }

    /** Detect Quark 8-byte crypto prefix (starts with 0x89 0x50 0x4E 0x47). */
    private static int detectQuarkPrefix(byte[] b) {
        if (b != null && b.length > 4
                && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
                && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47) {
            return 8;
        }
        return 0;
    }

    /**
     * Find the absolute [moovStart, moovSize] from a buffer that starts at file byte 0.
     * Returns null if not found.
     */
    private static long[] findMoovRange(byte[] buf, int offset) {
        int p = offset;
        while (p + 8 <= buf.length) {
            int rawSize = readIntBE(buf, p);
            char t1 = (char) buf[p + 4];
            char t2 = (char) buf[p + 5];
            char t3 = (char) buf[p + 6];
            char t4 = (char) buf[p + 7];
            long size = rawSize;
            if (rawSize == 1 && p + 16 <= buf.length) {
                size = readLongBE(buf, p + 8);
            }
            if (rawSize == 0) size = buf.length - p;
            if (t1 == 'm' && t2 == 'o' && t3 == 'o' && t4 == 'v') {
                return new long[]{p, size};
            }
            if (size < 8 || p + size > buf.length) break;
            p += size;
        }
        return null;
    }

    /**
     * Find [trakStart, trakSize, tkhdFlagsOffset] for each 'soun' track, in box order.
     * Returns null if fewer than two audio tracks are found.
     */
    private static long[] findAudioTrakRanges(byte[] buf, int offset) {
        long[] moov = findMoovRange(buf, offset);
        if (moov == null) return null;
        int moovStart = (int) moov[0];
        long moovEnd = Math.min(buf.length, moov[0] + moov[1]);
        java.util.ArrayList<long[]> soun = new java.util.ArrayList<>();

        int tidx = moovStart + 8;
        while (tidx < moovEnd - 8) {
            int tsize = readIntBE(buf, tidx);
            if (tsize == 1) {
                if (tidx + 16 > buf.length) break;
                tsize = (int) Math.min(readLongBE(buf, tidx + 8), Integer.MAX_VALUE);
            }
            if (buf[tidx + 4] == 't' && buf[tidx + 5] == 'r' && buf[tidx + 6] == 'a' && buf[tidx + 7] == 'k') {
                int trakEnd = Math.min(buf.length, tidx + tsize);
                boolean isSoun = false;
                int tkhdFlagsOffset = -1;
                int cpos = tidx + 8;
                while (cpos < trakEnd - 8) {
                    int csize = readIntBE(buf, cpos);
                    if (csize == 1) {
                        if (cpos + 16 > buf.length) break;
                        csize = (int) Math.min(readLongBE(buf, cpos + 8), Integer.MAX_VALUE);
                    }
                    if (buf[cpos + 4] == 't' && buf[cpos + 5] == 'k' && buf[cpos + 6] == 'h' && buf[cpos + 7] == 'd') {
                        // tkhd: size(4)+type(4)+version(1)+flags(3); enabled bit = LSB of flags byte 3 = cpos + 11
                        if (cpos + 11 < buf.length) tkhdFlagsOffset = cpos + 11;
                    } else if (buf[cpos + 4] == 'h' && buf[cpos + 5] == 'd' && buf[cpos + 6] == 'l' && buf[cpos + 7] == 'r') {
                        // hdlr: size(4)+type(4)+version(1)+flags(3)+pre_defined(4)+handler_type(4)
                        if (cpos + 20 <= buf.length) {
                            String ht = new String(buf, cpos + 16, 4, StandardCharsets.ISO_8859_1);
                            isSoun = "soun".equals(ht);
                        }
                    }
                    if (csize <= 0) break;
                    cpos += csize;
                }
                if (isSoun) {
                    soun.add(new long[]{tidx, tsize, tkhdFlagsOffset});
                }
            }
            if (tsize <= 0) break;
            tidx += tsize;
        }
        if (soun.size() < 2) return null;
        long[] out = new long[soun.size() * 3];
        for (int i = 0; i < soun.size(); i++) {
            out[i * 3] = soun.get(i)[0];
            out[i * 3 + 1] = soun.get(i)[1];
            out[i * 3 + 2] = soun.get(i)[2];
        }
        return out;
    }

    /**
     * Force audio track[targetIdx] to be the one the browser plays, for both Chromium
     * (respects the tkhd enabled flag) and Gecko (plays the first audio trak).
     * 1) set enabled flag only on the target track, 2) move the target trak to the front.
     */
    private static byte[] swapMp4AudioTrack(byte[] buf, int targetIdx) {
        if (buf == null || buf.length < 8 || targetIdx < 0) return buf;
        int offset = detectQuarkPrefix(buf);
        long[] ranges = findAudioTrakRanges(buf, offset);
        if (ranges == null) return buf;
        int count = ranges.length / 3;
        if (targetIdx >= count) return buf;

        byte[] out = new byte[buf.length];
        System.arraycopy(buf, 0, out, 0, buf.length);

        // 1. Rewrite enabled flags: only the target audio track stays enabled.
        for (int i = 0; i < count; i++) {
            int flagsOff = (int) ranges[i * 3 + 2];
            if (flagsOff < 0 || flagsOff >= out.length) continue;
            if (i == targetIdx) out[flagsOff] |= 0x01;
            else               out[flagsOff] &= 0xFE;
        }

        // 2. Move the target trak to the front.
        if (targetIdx > 0) {
            int a0s = (int) ranges[0];
            int a0sz = (int) ranges[1];
            int aNs = (int) ranges[targetIdx * 3];
            int aNsz = (int) ranges[targetIdx * 3 + 1];
            if (a0s < 0 || aNs < 0 || a0s + a0sz > out.length || aNs + aNsz > out.length) return buf;

            int a0e = a0s + a0sz;
            int aNe = aNs + aNsz;
            byte[] swapped = new byte[out.length];
            int pos = 0;
            System.arraycopy(out, 0, swapped, pos, a0s);           pos += a0s;          // before track0
            System.arraycopy(out, aNs, swapped, pos, aNsz);        pos += aNsz;         // trackN
            System.arraycopy(out, a0e, swapped, pos, aNs - a0e);   pos += (aNs - a0e);   // between
            System.arraycopy(out, a0s, swapped, pos, a0sz);        pos += a0sz;         // track0
            System.arraycopy(out, aNe, swapped, pos, out.length - aNe);                 // after
            out = swapped;
        }

        return out;
    }

    private static long[] readEbmlId(byte[] b, int pos) {
        if (b == null || pos >= b.length) return new long[]{0, 0};
        int b0 = b[pos] & 0xFF;
        int len = 0;
        if ((b0 & 0x80) != 0) len = 1;
        else if ((b0 & 0x40) != 0) len = 2;
        else if ((b0 & 0x20) != 0) len = 3;
        else if ((b0 & 0x10) != 0) len = 4;
        else return new long[]{0, 0};
        long id = 0;
        for (int i = 0; i < len; i++) {
            if (pos + i >= b.length) return new long[]{0, 0};
            id = (id << 8) | (b[pos + i] & 0xFF);
        }
        return new long[]{id, len};
    }

    private static long[] readEbmlVint(byte[] b, int pos) {
        if (b == null || pos >= b.length) return new long[]{0, 0};
        int b0 = b[pos] & 0xFF;
        int len = 0, mask = 0;
        if ((b0 & 0x80) != 0) { len = 1; mask = 0x7f; }
        else if ((b0 & 0x40) != 0) { len = 2; mask = 0x3f; }
        else if ((b0 & 0x20) != 0) { len = 3; mask = 0x1f; }
        else if ((b0 & 0x10) != 0) { len = 4; mask = 0x0f; }
        else if ((b0 & 0x08) != 0) { len = 5; mask = 0x07; }
        else if ((b0 & 0x04) != 0) { len = 6; mask = 0x03; }
        else if ((b0 & 0x02) != 0) { len = 7; mask = 0x01; }
        else if ((b0 & 0x01) != 0) { len = 8; mask = 0x00; }
        else return new long[]{0, 0};
        long val = b0 & mask;
        for (int i = 1; i < len; i++) {
            if (pos + i >= b.length) return new long[]{0, 0};
            val = (val << 8) | (b[pos + i] & 0xFF);
        }
        return new long[]{val, len};
    }

    private static void parseMkvTrackBoxes(byte[] buf, int offset, JsonArray audioTracks, JsonArray subtitleTracks) {
        int pos = offset;
        while (pos < buf.length - 4) {
            long[] idArr = readEbmlId(buf, pos);
            if (idArr[1] == 0) break;
            pos += (int) idArr[1];
            long[] szArr = readEbmlVint(buf, pos);
            if (szArr[1] == 0) break;
            pos += (int) szArr[1];
            int dataEnd = (int) Math.min((long) buf.length, (long) pos + szArr[0]);

            if (idArr[0] == 0x18538067L) { // Segment
                int spos = pos;
                while (spos < dataEnd - 4) {
                    long[] sidArr = readEbmlId(buf, spos);
                    if (sidArr[1] == 0) break;
                    spos += (int) sidArr[1];
                    long[] sszArr = readEbmlVint(buf, spos);
                    if (sszArr[1] == 0) break;
                    spos += (int) sszArr[1];
                    int sEnd = (int) Math.min((long) buf.length, (long) spos + sszArr[0]);

                    if (sidArr[0] == 0x1654ae6bL) { // Tracks
                        int tpos = spos;
                        while (tpos < sEnd - 4) {
                            long[] tidArr = readEbmlId(buf, tpos);
                            if (tidArr[1] == 0) break;
                            tpos += (int) tidArr[1];
                            long[] tszArr = readEbmlVint(buf, tpos);
                            if (tszArr[1] == 0) break;
                            tpos += (int) tszArr[1];
                            int entryEnd = (int) Math.min((long) buf.length, (long) tpos + tszArr[0]);

                            if (tidArr[0] == 0xaeL) { // TrackEntry
                                int trackType = 0;
                                int trackNum = 0;
                                int flagEnabled = 1;
                                String trackLang = "und";
                                int epos = tpos;
                                while (epos < entryEnd - 2) {
                                    long[] eidArr = readEbmlId(buf, epos);
                                    if (eidArr[1] == 0) break;
                                    epos += (int) eidArr[1];
                                    long[] eszArr = readEbmlVint(buf, epos);
                                    if (eszArr[1] == 0) break;
                                    epos += (int) eszArr[1];
                                    int valStart = epos;
                                    int valLen = (int) eszArr[0];

                                    if (eidArr[0] == 0x83L && valLen > 0) trackType = buf[valStart] & 0xFF;
                                    else if (eidArr[0] == 0xd7L && valLen > 0) {
                                        int tn = 0;
                                        for (int k = 0; k < Math.min(valLen, 4); k++) tn = (tn << 8) | (buf[valStart + k] & 0xFF);
                                        trackNum = tn;
                                    } else if (eidArr[0] == 0xb9L && valLen > 0) flagEnabled = buf[valStart] & 0xFF;
                                    else if (eidArr[0] == 0x22b59cL && valLen > 0) {
                                        trackLang = new String(buf, valStart, valLen, StandardCharsets.US_ASCII).replace("\0", "").trim();
                                    }
                                    epos += valLen;
                                }

                                if (trackType == 2) {
                                    JsonObject a = new JsonObject();
                                    int aIdx = audioTracks.size();
                                    a.addProperty("index", aIdx);
                                    a.addProperty("trackId", trackNum > 0 ? trackNum : aIdx + 2);
                                    a.addProperty("name", aIdx == 0 ? "中文 (立体声)" : "中文 (立体声)");
                                    a.addProperty("desc", "中文, 立体声, AAC");
                                    a.addProperty("codec", "AAC");
                                    a.addProperty("channels", 2);
                                    a.addProperty("lang", trackLang);
                                    a.addProperty("enabled", flagEnabled != 0);
                                    audioTracks.add(a);
                                } else if (trackType == 17) {
                                    JsonObject s = new JsonObject();
                                    int sIdx = subtitleTracks.size();
                                    s.addProperty("index", sIdx);
                                    s.addProperty("name", "内置中文字幕 " + (sIdx + 1));
                                    s.addProperty("desc", "内置中文字幕");
                                    s.addProperty("lang", trackLang);
                                    subtitleTracks.add(s);
                                }
                            }
                            tpos = entryEnd;
                        }
                        break;
                    }
                    spos = sEnd;
                }
                break;
            }
            pos = dataEnd;
        }

        // Disambiguate when multiple audio tracks
        if (audioTracks.size() > 1) {
            for (int i = 0; i < audioTracks.size(); i++) {
                JsonObject at = audioTracks.get(i).getAsJsonObject();
                boolean en = at.has("enabled") && at.get("enabled").getAsBoolean();
                at.addProperty("name", "音轨 " + (i + 1) + ": " + at.get("name").getAsString() + (en ? " [默认]" : ""));
            }
        }
    }

    private static byte[] swapMkvAudioTrack(byte[] buf, int targetIdx) {
        if (buf == null || buf.length < 8 || targetIdx < 0) return buf;
        int offset = detectQuarkPrefix(buf);
        int pos = offset;
        int tracksOffset = -1;
        int tracksEnd = -1;

        while (pos < buf.length - 4) {
            long[] idArr = readEbmlId(buf, pos);
            if (idArr[1] == 0) break;
            pos += (int) idArr[1];
            long[] szArr = readEbmlVint(buf, pos);
            if (szArr[1] == 0) break;
            pos += (int) szArr[1];
            int dataEnd = (int) Math.min((long) buf.length, (long) pos + szArr[0]);

            if (idArr[0] == 0x18538067L) { // Segment
                int spos = pos;
                while (spos < dataEnd - 4) {
                    long[] sidArr = readEbmlId(buf, spos);
                    if (sidArr[1] == 0) break;
                    spos += (int) sidArr[1];
                    long[] sszArr = readEbmlVint(buf, spos);
                    if (sszArr[1] == 0) break;
                    spos += (int) sszArr[1];
                    int sEnd = (int) Math.min((long) buf.length, (long) spos + sszArr[0]);

                    if (sidArr[0] == 0x1654ae6bL) { // Tracks
                        tracksOffset = spos;
                        tracksEnd = sEnd;
                        break;
                    }
                    spos = sEnd;
                }
                break;
            }
            pos = dataEnd;
        }
        if (tracksOffset == -1) return buf;

        java.util.ArrayList<int[]> audioEntries = new java.util.ArrayList<>();
        int tpos = tracksOffset;
        while (tpos < tracksEnd - 4) {
            long[] tidArr = readEbmlId(buf, tpos);
            if (tidArr[1] == 0) break;
            int entryStart = tpos;
            tpos += (int) tidArr[1];
            long[] tszArr = readEbmlVint(buf, tpos);
            if (tszArr[1] == 0) break;
            tpos += (int) tszArr[1];
            int entryEnd = (int) Math.min((long) buf.length, (long) tpos + tszArr[0]);

            if (tidArr[0] == 0xaeL) { // TrackEntry
                boolean isAudio = false;
                int flagEnabledOff = -1;
                int flagDefaultOff = -1;
                int epos = tpos;
                while (epos < entryEnd - 2) {
                    long[] eidArr = readEbmlId(buf, epos);
                    if (eidArr[1] == 0) break;
                    epos += (int) eidArr[1];
                    long[] eszArr = readEbmlVint(buf, epos);
                    if (eszArr[1] == 0) break;
                    epos += (int) eszArr[1];
                    int valStart = epos;
                    int valLen = (int) eszArr[0];

                    if (eidArr[0] == 0x83L && valLen > 0 && (buf[valStart] & 0xFF) == 2) isAudio = true;
                    else if (eidArr[0] == 0xb9L && valLen > 0) flagEnabledOff = valStart;
                    else if (eidArr[0] == 0x88L && valLen > 0) flagDefaultOff = valStart;
                    epos += valLen;
                }
                if (isAudio) {
                    audioEntries.add(new int[]{entryStart, entryEnd, flagEnabledOff, flagDefaultOff});
                }
            }
            tpos = entryEnd;
        }

        if (audioEntries.size() < 2 || targetIdx >= audioEntries.size()) return buf;

        byte[] out = new byte[buf.length];
        System.arraycopy(buf, 0, out, 0, buf.length);

        for (int i = 0; i < audioEntries.size(); i++) {
            int[] ent = audioEntries.get(i);
            if (ent[2] != -1 && ent[2] < out.length) out[ent[2]] = (byte) (i == targetIdx ? 1 : 0);
            if (ent[3] != -1 && ent[3] < out.length) out[ent[3]] = (byte) (i == targetIdx ? 1 : 0);
        }

        if (targetIdx > 0) {
            int[] t0 = audioEntries.get(0);
            int[] tN = audioEntries.get(targetIdx);

            if (t0[1] > out.length || tN[1] > out.length) return buf;

            byte[] swapped = new byte[out.length];
            int p = 0;
            System.arraycopy(out, 0, swapped, p, t0[0]); p += t0[0];
            int tNLen = tN[1] - tN[0];
            System.arraycopy(out, tN[0], swapped, p, tNLen); p += tNLen;
            int betweenLen = tN[0] - t0[1];
            System.arraycopy(out, t0[1], swapped, p, betweenLen); p += betweenLen;
            int t0Len = t0[1] - t0[0];
            System.arraycopy(out, t0[0], swapped, p, t0Len); p += t0Len;
            int afterLen = out.length - tN[1];
            System.arraycopy(out, tN[1], swapped, p, afterLen);
            out = swapped;
        }

        return out;
    }

    private Response handleNativeSpiderProxy(IHTTPSession session) {
        try {
            Map<String, String> params = new HashMap<>(session.getParms());
            params.putAll(session.getHeaders());
            Object[] rs = mSpiderManager.callProxy(params);
            if (rs == null || rs.length < 3) {
                return jsonResponse("{\"error\":\"Invalid native proxy response\"}", Response.Status.BAD_REQUEST);
            }
            int code = (Integer) rs[0];
            String mime = (String) rs[1];
            InputStream is = (InputStream) rs[2];
            Response.Status status = Response.Status.lookup(code);
            if (status == null) status = Response.Status.OK;
            Response resp = newChunkedResponse(status, mime, is);
            if (rs.length > 3 && rs[3] instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) rs[3]).entrySet()) {
                    resp.addHeader(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            addCorsHeaders(resp);
            return resp;
        } catch (Exception e) {
            Log.e(TAG, "Native spider proxy error: " + e.getMessage(), e);
            return jsonResponse("{\"error\":\"" + e.getMessage() + "\"}", Response.Status.INTERNAL_ERROR);
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

    private Response handleWebsiteApi(IHTTPSession session, String uri) {
        try {
            if (uri.equals("/website/api/status")) {
                String statusJson = "{\"code\":0,\"data\":{\"dbSize\":0,\"providers\":{" +
                        "\"quark\":{\"configured\":true,\"login\":true,\"state\":\"已配置\",\"label\":\"夸克 Cookie\"}," +
                        "\"baidu\":{\"configured\":true,\"login\":true,\"state\":\"已配置\",\"label\":\"百度 Cookie\"}," +
                        "\"uc\":{\"configured\":true,\"login\":true,\"state\":\"已配置\",\"label\":\"UC Cookie\"}," +
                        "\"thunder\":{\"configured\":false,\"login\":false,\"state\":\"未登录\"}," +
                        "\"pan123\":{\"configured\":false,\"login\":false,\"state\":\"未配置\"}," +
                        "\"pan115\":{\"configured\":false,\"login\":false,\"state\":\"未配置\"}," +
                        "\"guangya\":{\"configured\":false,\"login\":false,\"state\":\"未配置\"}," +
                        "\"pan189\":{\"configured\":false,\"login\":false,\"state\":\"未配置\"}" +
                        "}}}";
                return jsonResponse(statusJson, Response.Status.OK);
            }

            if (uri.equals("/website/api/remote-wex")) {
                return jsonResponse("{\"code\":0,\"data\":{\"ready\":true,\"loading\":false}}", Response.Status.OK);
            }

            if (uri.equals("/website/api/credentials")) {
                JsonObject defaultCreds = new JsonObject();
                JsonObject quark = new JsonObject(); quark.addProperty("cookie", ""); defaultCreds.add("quark", quark);
                JsonObject baidu = new JsonObject(); baidu.addProperty("cookie", ""); defaultCreds.add("baidu", baidu);
                JsonObject uc = new JsonObject(); uc.addProperty("cookie", ""); uc.addProperty("token", ""); defaultCreds.add("uc", uc);
                JsonObject thunder = new JsonObject(); thunder.addProperty("username", ""); thunder.addProperty("password", ""); defaultCreds.add("thunder", thunder);
                JsonObject pan123 = new JsonObject(); pan123.addProperty("username", ""); pan123.addProperty("password", ""); defaultCreds.add("pan123", pan123);
                JsonObject pan115 = new JsonObject(); pan115.addProperty("cookie", ""); defaultCreds.add("pan115", pan115);
                JsonObject guangya = new JsonObject(); guangya.addProperty("token", ""); defaultCreds.add("guangya", guangya);
                JsonObject pan189 = new JsonObject(); pan189.addProperty("cookie", ""); defaultCreds.add("pan189", pan189);

                java.io.File cfgFile = new java.io.File(mContext.getFilesDir(), "wexfnwconfig.json");
                if (cfgFile.exists()) {
                    try {
                        String str = new String(java.nio.file.Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8);
                        JsonObject saved = mGson.fromJson(str, JsonObject.class);
                        if (saved != null) {
                            for (String pKey : saved.keySet()) {
                                defaultCreds.add(pKey, saved.get(pKey));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                JsonObject respObj = new JsonObject();
                respObj.addProperty("code", 0);
                respObj.add("data", defaultCreds);
                return jsonResponse(respObj.toString(), Response.Status.OK);
            }

            if (uri.startsWith("/website/api/credential/")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                JsonObject body = TextUtils.isEmpty(postData) ? new JsonObject() : mGson.fromJson(postData, JsonObject.class);

                String[] parts = uri.substring("/website/api/credential/".length()).split("/");
                if (parts.length >= 2) {
                    String provider = parts[0];
                    String field = parts[1];
                    String val = body.has("value") ? body.get("value").getAsString() : "";

                    java.io.File cfgFile = new java.io.File(mContext.getFilesDir(), "wexfnwconfig.json");
                    JsonObject root = new JsonObject();
                    if (cfgFile.exists()) {
                        try {
                            String existStr = new String(java.nio.file.Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8);
                            root = mGson.fromJson(existStr, JsonObject.class);
                        } catch (Exception ignored) {
                        }
                    }
                    if (root == null) root = new JsonObject();
                    JsonObject provObj = root.has(provider) ? root.getAsJsonObject(provider) : new JsonObject();
                    provObj.addProperty(field, val);
                    root.add(provider, provObj);

                    java.nio.file.Files.write(cfgFile.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
                }
                return jsonResponse("{\"code\":0,\"message\":\"已保存\"}", Response.Status.OK);
            }

            if (uri.equals("/website/api/sites")) {
                JsonObject cfg = mSpiderManager.getConfig();
                String sites = (cfg != null && cfg.has("sites")) ? cfg.get("sites").toString() : "[]";
                return jsonResponse("{\"code\":0,\"data\":" + sites + "}", Response.Status.OK);
            }

            // Universal fallback for any other /website/api/*
            return jsonResponse("{\"code\":0,\"data\":{}}", Response.Status.OK);
        } catch (Exception e) {
            Log.e(TAG, "handleWebsiteApi error: " + e.getMessage(), e);
            return jsonResponse("{\"code\":-1,\"message\":\"" + e.getMessage() + "\"}", Response.Status.INTERNAL_ERROR);
        }
    }

    private Response jsonResponse(String json, Response.IStatus status) {
        String safeJson = (json == null || json.trim().isEmpty()) ? "{}" : json.trim();
        Response resp = newFixedLengthResponse(status, "application/json; charset=utf-8", safeJson);
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
