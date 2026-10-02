package com.github.catvod.net;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public class OkHttp {

    private static OkHttpClient sClient;

    public static synchronized OkHttpClient client() {
        if (sClient == null) {
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        return sClient;
    }
}
