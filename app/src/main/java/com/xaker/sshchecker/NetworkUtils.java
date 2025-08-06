package com.xaker.sshchecker;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

public class NetworkUtils {

    public static String getGatewayIp(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            if (dhcpInfo != null) {
                int gateway = dhcpInfo.gateway;
                return formatIpAddress(gateway);
            }
        }
        return "Нет данных";
    }

    private static String formatIpAddress(int ip) {
        return (ip & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                ((ip >> 24) & 0xFF);
    }
}
