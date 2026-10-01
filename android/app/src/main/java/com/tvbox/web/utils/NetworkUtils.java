package com.tvbox.web.utils;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {

    /**
     * Get the most appropriate LAN IPv4 address (prioritizing Wi-Fi and 192.168.x.x)
     */
    public static String getLocalIpAddress(Context context) {
        // 1. Try WifiManager directly if context provided
        if (context != null) {
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.isWifiEnabled()) {
                    WifiInfo winfo = wm.getConnectionInfo();
                    if (winfo != null) {
                        int ipInt = winfo.getIpAddress();
                        if (ipInt != 0) {
                            String wifiIp = Formatter.formatIpAddress(ipInt);
                            if (!"0.0.0.0".equals(wifiIp) && !"127.0.0.1".equals(wifiIp)) {
                                return wifiIp;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Iterate NetworkInterfaces with scoring algorithm
        List<IpCandidate> candidates = new ArrayList<>();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName().toLowerCase();

                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("127.")) continue;

                    int score = calculateInterfaceScore(name, ip);
                    candidates.add(new IpCandidate(ip, name, score));
                }
            }
        } catch (Exception ignored) {
        }

        if (!candidates.isEmpty()) {
            // Sort by score descending
            Collections.sort(candidates, (a, b) -> Integer.compare(b.score, a.score));
            return candidates.get(0).ip;
        }

        return "127.0.0.1";
    }

    public static String getLocalIpAddress() {
        return getLocalIpAddress(null);
    }

    /**
     * Get all active IPv4 addresses for diagnostics
     */
    public static List<String> getAllIpAddresses() {
        List<String> list = new ArrayList<>();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName();
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        list.add(name + ": " + addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private static int calculateInterfaceScore(String name, String ip) {
        int score = 0;

        // Interface name priority
        if (name.contains("wlan")) {
            score += 100; // Wi-Fi is top priority for home LAN
        } else if (name.contains("eth") || name.contains("en")) {
            score += 90;  // Ethernet / Wired
        } else if (name.contains("ap") || name.contains("rndis")) {
            score += 70;  // Hotspot / USB tethering
        } else if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp")) {
            score -= 50;  // Cellular mobile data (often 10.x.x.x, unreachable by LAN)
        } else if (name.contains("tun") || name.contains("tap") || name.contains("dummy") || name.contains("p2p")) {
            score -= 80;  // VPN / Virtual adapters
        }

        // IP address range priority
        if (ip.startsWith("192.168.")) {
            score += 60;  // Standard home router subnet
        } else if (ip.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) {
            score += 40;  // Private Class B subnet
        } else if (ip.startsWith("10.")) {
            score += 10;  // Private Class A subnet (frequently used by cellular carriers)
        }

        return score;
    }

    private static class IpCandidate {
        final String ip;
        final String ifaceName;
        final int score;

        IpCandidate(String ip, String ifaceName, int score) {
            this.ip = ip;
            this.ifaceName = ifaceName;
            this.score = score;
        }
    }
}
