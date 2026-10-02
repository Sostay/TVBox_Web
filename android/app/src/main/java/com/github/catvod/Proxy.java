package com.github.catvod;

public class Proxy {

    private static int port = 8999;

    public static void set(int p) {
        port = p;
    }

    public static int getPort() {
        return port;
    }

    public static String getUrl(boolean local) {
        return "http://127.0.0.1:" + getPort() + "/proxy";
    }
}
