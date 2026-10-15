package com.dex.note10;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Finds the Note 10 by looking for ADB on port 5551 or 5555, fastest option first:
 *   1) the last IP where it was found (saved across app restarts)
 *   2) the /24 subnets this phone is really connected to
 *   3) common home ranges as a fallback
 * Each step stops as soon as one device answers instead of waiting for the whole sweep.
 * Does NOT do ADB model verification — ScrcpyClient tries the candidate directly.
 */
public class DeviceScanner {
    private static final String TAG = "DeviceScanner";
    // Preferred port first; 5555 is the ADB default the Note 10 falls back to after a reboot.
    public static final int[] ADB_PORTS = {5551, 5555};
    private static final int LAST_IP_TIMEOUT_MS = 800;
    // On a LAN an open port answers in a few ms; 400ms is plenty and keeps sweeps short.
    private static final int CONNECT_TIMEOUT_MS = 400;
    private static final int THREAD_COUNT = 254;
    private static final String PREFS = "note10_scanner";
    private static final String KEY_LAST_IP = "last_ip";

    private static final String[] FALLBACK_SUBNETS = {
            "192.168.0", "192.168.1", "192.168.18", "192.168.2", "192.168.3",
            "192.168.4", "192.168.8", "192.168.10", "192.168.100", "10.0.0", "10.0.1"
    };

    public interface ScanListener {
        void onProgress(String message);
    }

    /**
     * Returns the IP of the first device found with port 5551 open (empty list if none).
     */
    public static List<String> scan(Context context, ScanListener listener) {
        List<String> result = new ArrayList<>();

        // 1) Last known IP
        String last = getLastIp(context);
        if (last != null) {
            progress(listener, "Probando ultima IP conocida " + last + "...");
            if (findAdbPort(last, LAST_IP_TIMEOUT_MS) > 0) {
                Log.i(TAG, "Found at last known IP " + last);
                result.add(last);
                return result;
            }
        }

        // 2) Subnets the phone is actually on
        Set<String> local = new LinkedHashSet<>(getLocalSubnets());
        String wifi = getSubnet(context);
        if (wifi != null) local.add(wifi);

        String found = null;
        if (!local.isEmpty()) {
            progress(listener, "Escaneando red " + join(local) + " ...");
            found = sweep(local, last);
        }

        // 3) Common ranges, only if not found on the local network
        if (found == null) {
            Set<String> rest = new LinkedHashSet<>();
            for (String s : FALLBACK_SUBNETS) if (!local.contains(s)) rest.add(s);
            progress(listener, "No esta en tu red, buscando en " + rest.size() + " redes mas...");
            found = sweep(rest, last);
        }

        if (found == null) {
            progress(listener, "Ningun dispositivo con puerto 5551 o 5555 abierto");
            return result;
        }

        saveLastIp(context, found);
        progress(listener, "Encontrado: " + found);
        result.add(found);
        return result;
    }

    /**
     * Probes every host in the given subnets in parallel and returns the first IP that answers,
     * cancelling the rest. Hosts near the last known address go first.
     */
    private static String sweep(Set<String> subnets, String lastIp) {
        int lastOctet = -1;
        if (lastIp != null) {
            try { lastOctet = Integer.parseInt(lastIp.substring(lastIp.lastIndexOf('.') + 1)); }
            catch (Exception ignored) {}
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CompletionService<String> cs = new ExecutorCompletionService<>(pool);
        int submitted = 0;
        try {
            for (String subnet : subnets) {
                for (int host : hostOrder(lastOctet)) {
                    final String ip = subnet + "." + host;
                    cs.submit(() -> findAdbPort(ip, CONNECT_TIMEOUT_MS) > 0 ? ip : null);
                    submitted++;
                }
            }
            for (int i = 0; i < submitted; i++) {
                Future<String> f = cs.poll(30, TimeUnit.SECONDS);
                if (f == null) break;
                String ip = f.get();
                if (ip != null) {
                    Log.i(TAG, "ADB open: " + ip);
                    return ip;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "sweep error", e);
        } finally {
            pool.shutdownNow();
        }
        return null;
    }

    /** 1..254, starting from the last known host and moving outward. */
    private static List<Integer> hostOrder(int center) {
        List<Integer> order = new ArrayList<>(254);
        if (center < 1 || center > 254) {
            for (int i = 1; i <= 254; i++) order.add(i);
            return order;
        }
        order.add(center);
        for (int d = 1; d < 254; d++) {
            if (center - d >= 1) order.add(center - d);
            if (center + d <= 254) order.add(center + d);
        }
        return order;
    }

    /** Returns the first ADB port open on this IP (5551, then 5555), or -1 if none. */
    public static int findAdbPort(String ip, int timeoutMs) {
        for (int port : ADB_PORTS) {
            if (isPortOpen(ip, port, timeoutMs)) return port;
        }
        return -1;
    }

    private static boolean isPortOpen(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getLastIp(Context context) {
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_IP, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveLastIp(Context context, String ip) {
        try {
            SharedPreferences p = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putString(KEY_LAST_IP, ip).apply();
        } catch (Exception ignored) {}
    }

    private static void progress(ScanListener listener, String msg) {
        if (listener != null) listener.onProgress(msg);
    }

    private static String join(Set<String> subnets) {
        StringBuilder sb = new StringBuilder();
        for (String s : subnets) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s).append(".x");
        }
        return sb.toString();
    }

    /** Derives the /24 prefix of every non-loopback IPv4 address on this device. */
    private static Set<String> getLocalSubnets() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                // Skip mobile data interfaces; the Note 10 is never reachable there
                String name = ni.getName();
                if (name != null && (name.startsWith("rmnet") || name.startsWith("ccmni"))) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();   // e.g. 192.168.0.153
                        int lastDot = ip.lastIndexOf('.');
                        if (lastDot > 0) out.add(ip.substring(0, lastDot));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getLocalSubnets error", e);
        }
        Log.i(TAG, "Local subnets: " + out);
        return out;
    }

    private static String getSubnet(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return null;
            int ip = wifi.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            // Android returns IP little-endian
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF);
        } catch (Exception e) {
            Log.e(TAG, "getSubnet error", e);
            return null;
        }
    }
}
