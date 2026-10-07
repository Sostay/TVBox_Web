package com.tvbox.web.engine;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SpiderManager {

    private static final String TAG = "SpiderManager";
    private static volatile SpiderManager sInstance;

    private final Context mContext;
    private final Gson mGson = new Gson();
    private final OkHttpClient mHttp = new OkHttpClient();
    private final Map<String, Object> mSpiderCache = new ConcurrentHashMap<>();
    private final Map<String, Method> mMethodCache = new ConcurrentHashMap<>();

    private DexClassLoader mClassLoader;
    private JsonObject mCurrentConfig;
    private String mSpiderUrl = "";
    private volatile String mLastSpiderError = null;
    private volatile Method mProxyMethod = null;

    private SpiderManager(Context context) {
        this.mContext = context.getApplicationContext();
    }

    public static SpiderManager get(Context context) {
        if (sInstance == null) {
            synchronized (SpiderManager.class) {
                if (sInstance == null) {
                    sInstance = new SpiderManager(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * Load a TVBox JSON configuration (e.g. user-specified JSON config)
     */
    public synchronized boolean loadConfig(String configJsonOrUrl) {
        try {
            String jsonStr;
            String baseUrl = null;
            if (configJsonOrUrl.startsWith("http://") || configJsonOrUrl.startsWith("https://")) {
                baseUrl = configJsonOrUrl;
                Request req = new Request.Builder()
                        .url(configJsonOrUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();
                try (Response resp = mHttp.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return false;
                    jsonStr = resp.body().string();
                }
            } else {
                jsonStr = configJsonOrUrl;
            }

            return loadConfigContent(baseUrl, jsonStr);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load config: " + e.getMessage(), e);
            return false;
        }
    }

    public synchronized boolean loadConfigContent(String baseUrl, String jsonStr) {
        try {
            if (jsonStr == null) return false;
            String cleanStr = jsonStr.trim();
            // Handle Base64 encoded configs
            if (!cleanStr.startsWith("{") && !cleanStr.startsWith("[")) {
                try {
                    byte[] decoded = android.util.Base64.decode(cleanStr, android.util.Base64.DEFAULT);
                    String decodedStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (decodedStr.startsWith("{") || decodedStr.startsWith("[")) {
                        cleanStr = decodedStr;
                    }
                } catch (Exception ignored) {
                }
            }

            mCurrentConfig = mGson.fromJson(cleanStr, JsonObject.class);
            if (mCurrentConfig == null) return false;

            if (mCurrentConfig.has("spider")) {
                String rawSpider = mCurrentConfig.get("spider").getAsString();
                String spiderDownloadUrl = rawSpider.split(";")[0].trim();
                // Resolve relative spider URL if necessary
                if (!spiderDownloadUrl.startsWith("http://") && !spiderDownloadUrl.startsWith("https://") && baseUrl != null) {
                    if (spiderDownloadUrl.startsWith("./")) spiderDownloadUrl = spiderDownloadUrl.substring(2);
                    int lastSlash = baseUrl.lastIndexOf('/');
                    if (lastSlash != -1) {
                        spiderDownloadUrl = baseUrl.substring(0, lastSlash + 1) + spiderDownloadUrl;
                    }
                }
                final String finalSpiderUrl = spiderDownloadUrl;
                initSpiderDex(finalSpiderUrl);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse config content: " + e.getMessage(), e);
            return false;
        }
    }

    public JsonObject getConfig() {
        return mCurrentConfig;
    }

    /**
     * Download and load the Spider Jar/Dex file via DexClassLoader
     */
    private synchronized void initSpiderDex(String spiderUrl) {
        if (spiderUrl.equals(mSpiderUrl) && mClassLoader != null) {
            return;
        }
        this.mSpiderUrl = spiderUrl;
        this.mSpiderCache.clear();
        this.mMethodCache.clear();
        this.mLastSpiderError = null;

        try {
            File dexDir = new File(mContext.getFilesDir(), "spiders");
            if (!dexDir.exists()) dexDir.mkdirs();

            String jarName = "spider_" + Integer.toHexString(spiderUrl.hashCode()) + ".jar";
            File jarFile = new File(dexDir, jarName);
            File optDir = new File(mContext.getCodeCacheDir(), "dex_opt");
            if (!optDir.exists()) optDir.mkdirs();

            File libDir = new File(mContext.getFilesDir(), "spider_libs");
            if (!libDir.exists()) libDir.mkdirs();

            // Download only if not exists or empty
            if (!jarFile.exists() || jarFile.length() < 1024) {
                Log.i(TAG, "Downloading spider jar from: " + spiderUrl);
                File tmpFile = new File(dexDir, jarName + ".tmp");
                if (tmpFile.exists()) tmpFile.delete();

                Request req = new Request.Builder()
                        .url(spiderUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();
                try (Response resp = mHttp.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        mLastSpiderError = "Spider下载失败: HTTP " + resp.code();
                        Log.e(TAG, mLastSpiderError);
                        return;
                    }
                    try (InputStream in = resp.body().byteStream();
                         FileOutputStream out = new FileOutputStream(tmpFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                    }
                }

                if (jarFile.exists()) {
                    jarFile.setWritable(true, false);
                    jarFile.delete();
                }
                tmpFile.renameTo(jarFile);
            }

            // Extract native libs (.so) and guard resources from the downloaded jar/zip
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jarFile)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".so")) {
                        File soOut = new File(libDir, new File(name).getName());
                        if (soOut.exists()) {
                            soOut.setWritable(true, false);
                        }
                        try (InputStream zis = zf.getInputStream(entry);
                             FileOutputStream zos = new FileOutputStream(soOut)) {
                            byte[] b = new byte[8192];
                            int l;
                            while ((l = zis.read(b)) != -1) {
                                zos.write(b, 0, l);
                            }
                        }
                        soOut.setReadOnly();
                    } else if (name.endsWith(".guard")) {
                        File guardOut = new File(mContext.getFilesDir(), new File(name).getName());
                        try (InputStream zis = zf.getInputStream(entry);
                             FileOutputStream zos = new FileOutputStream(guardOut)) {
                            byte[] b = new byte[8192];
                            int l;
                            while ((l = zis.read(b)) != -1) {
                                zos.write(b, 0, l);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Zip extraction warning: " + e.getMessage());
            }

            // Android 14+ enforces read-only dynamically loaded DEX files
            jarFile.setReadOnly();

            mClassLoader = new DexClassLoader(
                    jarFile.getAbsolutePath(),
                    optDir.getAbsolutePath(),
                    libDir.getAbsolutePath(),
                    mContext.getClassLoader()
            );
            Log.i(TAG, "DexClassLoader initialized successfully with libDir: " + libDir.getAbsolutePath());

            // Activate JNI and guard layers via com.github.catvod.spider.Init
            try {
                Class<?> initClz = mClassLoader.loadClass("com.github.catvod.spider.Init");
                Method initM = initClz.getMethod("init", Context.class);
                initM.invoke(null, mContext);
                Log.i(TAG, "Invoked com.github.catvod.spider.Init.init(context) successfully!");
            } catch (Throwable t) {
                Log.w(TAG, "Init.init(context) invoke warning: " + t.getMessage());
            }

            // Bind Proxy method if present
            try {
                Class<?> proxyClz = mClassLoader.loadClass("com.github.catvod.spider.Proxy");
                mProxyMethod = proxyClz.getMethod("proxy", Map.class);
                Log.i(TAG, "Bound com.github.catvod.spider.Proxy.proxy(Map) successfully!");
            } catch (Throwable t) {
                Log.w(TAG, "Proxy.proxy(Map) bind warning: " + t.getMessage());
            }
        } catch (Exception e) {
            mLastSpiderError = "DexClassLoader初始化异常: " + e.getMessage();
            Log.e(TAG, mLastSpiderError, e);
        }
    }

    /**
     * Get or create a Spider instance (e.g. csp_AiNewWoggGuard)
     */
    public Object getSpider(String apiClass, String ext) {
        if (mClassLoader == null) {
            Log.e(TAG, "mClassLoader is null, last error: " + mLastSpiderError);
            return null;
        }
        if (apiClass == null) return null;
        String cacheKey = apiClass + "#" + (ext == null ? "" : ext);
        if (mSpiderCache.containsKey(cacheKey)) {
            return mSpiderCache.get(cacheKey);
        }

        try {
            String spiderSimpleName = apiClass.startsWith("csp_") ? apiClass.substring(4) : apiClass;
            String fullClassName = "com.github.catvod.spider." + spiderSimpleName;

            // Strategy 1: Try Init.getSpider(name) from inner packer
            try {
                Class<?> initClz = mClassLoader.loadClass("com.github.catvod.spider.Init");
                Method getSpiderM = initClz.getMethod("getSpider", String.class);
                Object sp = getSpiderM.invoke(null, spiderSimpleName);
                if (sp == null) {
                    sp = getSpiderM.invoke(null, apiClass);
                }
                if (sp != null) {
                    try {
                        java.lang.reflect.Field field = sp.getClass().getField("siteKey");
                        field.set(sp, cacheKey.split("#")[0]);
                    } catch (Throwable ignored) {}

                    try {
                        Method initMethod = sp.getClass().getMethod("init", Context.class, String.class);
                        initMethod.invoke(sp, mContext, ext != null ? ext : "");
                    } catch (NoSuchMethodException e) {
                        try {
                            Method initMethod = sp.getClass().getMethod("init", Context.class);
                            initMethod.invoke(sp, mContext);
                        } catch (NoSuchMethodException ignored) {}
                    }
                    mSpiderCache.put(cacheKey, sp);
                    return sp;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Init.getSpider strategy note: " + t.getMessage());
            }

            // Strategy 2: Try inner ClassLoader from Init.loader()
            ClassLoader targetLoader = mClassLoader;
            try {
                Class<?> initClz = mClassLoader.loadClass("com.github.catvod.spider.Init");
                Method loaderM = initClz.getMethod("loader");
                Object l = loaderM.invoke(null);
                if (l instanceof ClassLoader) {
                    targetLoader = (ClassLoader) l;
                }
            } catch (Throwable ignored) {}

            Class<?> clazz = null;
            try {
                clazz = targetLoader.loadClass(fullClassName);
            } catch (ClassNotFoundException e) {
                try {
                    clazz = targetLoader.loadClass(apiClass);
                } catch (ClassNotFoundException e2) {
                    try {
                        clazz = mClassLoader.loadClass(fullClassName);
                    } catch (ClassNotFoundException e3) {
                        try {
                            clazz = mClassLoader.loadClass(apiClass);
                        } catch (ClassNotFoundException ignored) {}
                    }
                }
            }

            if (clazz == null) {
                mLastSpiderError = "未找到类: " + fullClassName;
                Log.w(TAG, mLastSpiderError);
                return null;
            }

            Object instance = clazz.getDeclaredConstructor().newInstance();

            // Set siteKey field if present
            try {
                java.lang.reflect.Field field = clazz.getField("siteKey");
                field.set(instance, cacheKey.split("#")[0]);
            } catch (Throwable ignored) {
            }

            // Invoke init(Context, String extend) or init(Context)
            try {
                Method initMethod = clazz.getMethod("init", Context.class, String.class);
                initMethod.invoke(instance, mContext, ext != null ? ext : "");
            } catch (NoSuchMethodException e) {
                try {
                    Method initMethod = clazz.getMethod("init", Context.class);
                    initMethod.invoke(instance, mContext);
                } catch (NoSuchMethodException ignored) {
                }
            }

            mSpiderCache.put(cacheKey, instance);
            return instance;
        } catch (Exception e) {
            mLastSpiderError = "实例化爬虫 " + apiClass + " 失败: " + e.getMessage();
            Log.e(TAG, mLastSpiderError, e);
            return null;
        }
    }

    public String getLastSpiderError() {
        return mLastSpiderError;
    }

    /**
     * Invoke Spider method dynamically
     */
    public String callHome(String apiClass, String ext) {
        Object spider = getSpider(apiClass, ext);
        if (spider == null) {
            String err = mLastSpiderError != null ? mLastSpiderError : ("Spider类未找到: " + apiClass);
            return "{\"error\":\"" + err + "\",\"list\":[]}";
        }
        try {
            Method m = spider.getClass().getMethod("homeContent", boolean.class);
            Object res = m.invoke(spider, true);
            String homeStr = (res != null) ? res.toString().trim() : "";
            if (homeStr.isEmpty() || !homeStr.startsWith("{")) {
                homeStr = "{\"list\":[],\"class\":[]}";
            }

            try {
                JsonObject obj = mGson.fromJson(homeStr, JsonObject.class);
                if (obj != null && (!obj.has("list") || obj.getAsJsonArray("list").size() == 0)) {
                    try {
                        Method mVideo = spider.getClass().getMethod("homeVideoContent");
                        Object resVideo = mVideo.invoke(spider);
                        if (resVideo != null) {
                            JsonObject vObj = mGson.fromJson(resVideo.toString(), JsonObject.class);
                            if (vObj != null && vObj.has("list") && vObj.getAsJsonArray("list").size() > 0) {
                                obj.add("list", vObj.get("list"));
                                homeStr = obj.toString();
                            }
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }

            return homeStr;
        } catch (Exception e) {
            Log.e(TAG, "callHome error: " + e.getMessage(), e);
            return "{\"error\":\"callHome异常: " + e.getMessage() + "\",\"list\":[],\"class\":[]}";
        }
    }

    public String callCategory(String apiClass, String ext, String tid, String pg) {
        return callCategory(apiClass, ext, tid, pg, null);
    }

    public String callCategory(String apiClass, String ext, String tid, String pg, HashMap<String, String> extend) {
        Object spider = getSpider(apiClass, ext);
        if (spider == null) return "{\"page\":1,\"pagecount\":1,\"limit\":20,\"total\":0,\"list\":[]}";
        try {
            Method m = spider.getClass().getMethod("categoryContent", String.class, String.class, boolean.class, HashMap.class);
            Object res = m.invoke(spider, tid, pg, true, extend != null ? extend : new HashMap<>());
            String catStr = (res != null) ? res.toString().trim() : "";
            if (catStr.isEmpty() || !catStr.startsWith("{")) {
                catStr = "{\"page\":1,\"pagecount\":1,\"limit\":20,\"total\":0,\"list\":[]}";
            }
            return catStr;
        } catch (Exception e) {
            Log.e(TAG, "callCategory error: " + e.getMessage(), e);
            return "{\"page\":1,\"pagecount\":1,\"limit\":20,\"total\":0,\"list\":[]}";
        }
    }

    public String callDetail(String apiClass, String ext, String vodId) {
        Object spider = getSpider(apiClass, ext);
        if (spider == null) return "{\"list\":[]}";
        try {
            List<String> ids = new ArrayList<>();
            ids.add(vodId);
            Method m = spider.getClass().getMethod("detailContent", List.class);
            Object res = m.invoke(spider, ids);
            String detailStr = (res != null) ? res.toString().trim() : "";
            if (detailStr.isEmpty() || !detailStr.startsWith("{")) {
                detailStr = "{\"list\":[]}";
            }
            return detailStr;
        } catch (Exception e) {
            Log.e(TAG, "callDetail error: " + e.getMessage(), e);
            return "{\"list\":[]}";
        }
    }

    public String callPlay(String apiClass, String ext, String flag, String id) {
        Object spider = getSpider(apiClass, ext);
        if (spider == null) return "{\"parse\":0,\"url\":\"\"}";
        try {
            Method m = spider.getClass().getMethod("playerContent", String.class, String.class, List.class);
            Object res = m.invoke(spider, flag, id, new ArrayList<>());
            String playStr = (res != null) ? res.toString().trim() : "";
            if (playStr.isEmpty() || !playStr.startsWith("{")) {
                playStr = "{\"parse\":0,\"url\":\"\"}";
            }
            return playStr;
        } catch (Exception e) {
            Log.e(TAG, "callPlay error: " + e.getMessage(), e);
            return "{\"parse\":0,\"url\":\"\"}";
        }
    }

    public String callSearch(String apiClass, String ext, String keyword, String pg) {
        Object spider = getSpider(apiClass, ext);
        if (spider == null) return "{\"page\":1,\"pagecount\":1,\"list\":[]}";
        try {
            Method m = spider.getClass().getMethod("searchContent", String.class, boolean.class, String.class);
            Object res = m.invoke(spider, keyword, false, pg);
            String searchStr = (res != null) ? res.toString().trim() : "";
            if (searchStr.isEmpty() || !searchStr.startsWith("{")) {
                searchStr = "{\"page\":1,\"pagecount\":1,\"list\":[]}";
            }
            return searchStr;
        } catch (Exception e) {
            Log.e(TAG, "callSearch error: " + e.getMessage(), e);
            return "{\"page\":1,\"pagecount\":1,\"list\":[]}";
        }
    }

    public Object[] callProxy(Map<String, String> params) {
        if (mProxyMethod == null) return null;
        try {
            return (Object[]) mProxyMethod.invoke(null, params);
        } catch (Exception e) {
            Log.e(TAG, "callProxy error: " + e.getMessage(), e);
            return null;
        }
    }
}
