package com.omnistream;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LAN discovery: Sunshine/GameStream PCs (port 47989 + /serverinfo) and Xbox consoles (SmartGlass UDP 5050). */
final class Discovery {
    interface Listener { void found(Host h); void done(); }

    // Xbox SmartGlass discovery request: type 0xDD00, payload len 10, version 0, flags 0, client type 8 (Android), min ver 0, max ver 2
    private static final byte[] XBOX_DISCOVER = {(byte) 0xDD, 0x00, 0x00, 0x0A, 0x00, 0x00, 0, 0, 0, 0, 0, 0x08, 0, 0, 0, 0x02};

    static String subnetPrefix(Context c) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ip = wm == null ? 0 : wm.getConnectionInfo().getIpAddress();
        if (ip == 0) return null;
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + ".";
    }

    /** Runs both scans on background threads; listener callbacks arrive on background threads. */
    static void scan(final Context c, final Listener l) {
        new Thread(new Runnable() { public void run() {
            final Set<String> seen = new HashSet<String>();
            final String prefix = subnetPrefix(c);
            // Multicast lock is optional (not permitted on Android 14+ in this build); never let it stop the scan
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                lock = wm.createMulticastLock("omni");
                lock.acquire();
            } catch (Throwable ignored) { lock = null; }
            try {
                // 1) Xbox broadcast discovery (fast)
                DatagramSocket s = new DatagramSocket();
                try {
                    s.setBroadcast(true); s.setSoTimeout(250);
                    for (int n = 0; n < 3; n++) {
                        send(s, XBOX_DISCOVER, "255.255.255.255");
                        if (prefix != null) send(s, XBOX_DISCOVER, prefix + "255");
                    }
                    byte[] buf = new byte[4096];
                    long end = System.currentTimeMillis() + 2500;
                    while (System.currentTimeMillis() < end) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                        String ip = p.getAddress().getHostAddress();
                        Host h = parseXbox(buf, p.getLength(), ip);
                        if (h != null && add(seen, ip)) l.found(h);
                    }
                } finally { s.close(); }

                // 2) Sunshine / GameStream PCs: probe port 47989 on every address in the /24
                if (prefix != null) {
                    ExecutorService pool = Executors.newFixedThreadPool(48);
                    for (int i = 1; i < 255; i++) {
                        final String ip = prefix + i;
                        pool.execute(new Runnable() { public void run() { try { probePc(ip, seen, l); } catch (Throwable ignored) {} } });
                    }
                    pool.shutdown();
                    pool.awaitTermination(20, TimeUnit.SECONDS);
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignored) {}
                l.done();
            }
        }}).start();
    }

    private static void probePc(String ip, Set<String> seen, Listener l) {
                            if (!open(ip, 47989, 500)) return;
                            Host h = new Host();
                            h.type = "PC"; h.ip = ip; h.port = 47989; h.name = "Sunshine PC (" + ip + ")";
                            String xml = http(ip, 47989, "/serverinfo?uniqueid=0123456789ABCDEF&uuid=" + UUID.randomUUID());
                            if (xml != null) {
                                String name = tag(xml, "hostname"), mac = tag(xml, "mac");
                                if (name != null && !name.isEmpty()) h.name = name;
                                if (mac != null && !mac.replaceAll("[0:-]", "").isEmpty()) h.mac = mac.toUpperCase();
                            }
                            synchronized (seen) { if (!seen.add(ip)) return; }
                            l.found(h);
    }

    private static boolean add(Set<String> seen, String ip) { synchronized (seen) { return seen.add(ip); } }

    private static void send(DatagramSocket s, byte[] d, String ip) {
        try { s.send(new DatagramPacket(d, d.length, InetAddress.getByName(ip), 5050)); } catch (Exception ignored) {}
    }

    private static String sgString(ByteBuffer b) throws Exception {
        int n = b.getShort() & 0xffff;
        byte[] s = new byte[n]; b.get(s); b.get(); // trailing NUL
        return new String(s, "UTF-8");
    }

    /** Parses a 0xDD01 discovery response: name, uuid, and the console Live ID from the certificate CN. */
    static Host parseXbox(byte[] d, int len, String ip) {
        try {
            ByteBuffer b = ByteBuffer.wrap(d, 0, len);
            if ((b.getShort() & 0xffff) != 0xDD01) return null;
            b.getShort(); b.getShort(); b.getInt(); b.getShort(); // len, version, flags, client type
            Host h = new Host();
            h.type = "XBOX"; h.ip = ip; h.port = 5050;
            h.name = sgString(b);
            sgString(b); // uuid
            b.getInt();  // last error
            byte[] cert = new byte[b.getShort() & 0xffff];
            b.get(cert);
            X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
            Matcher m = Pattern.compile("CN=([^,]+)").matcher(x.getSubjectX500Principal().getName());
            if (m.find()) h.liveId = m.group(1);
            return h;
        } catch (Exception e) {
            return null;
        }
    }

    /** SmartGlass power-on (0xDD02) — wakes a console in Sleep mode using its Live ID. */
    static void xboxPowerOn(String liveId, String ip) throws Exception {
        byte[] id = liveId.getBytes("UTF-8");
        int payload = 2 + id.length + 1;
        ByteBuffer b = ByteBuffer.allocate(6 + payload);
        b.putShort((short) 0xDD02).putShort((short) payload).putShort((short) 0).putShort((short) id.length).put(id).put((byte) 0);
        byte[] pkt = b.array();
        DatagramSocket s = new DatagramSocket();
        try {
            s.setBroadcast(true);
            for (int n = 0; n < 5; n++) { send(s, pkt, ip); send(s, pkt, "255.255.255.255"); Thread.sleep(100); }
        } finally { s.close(); }
    }

    /** Xbox is awake if it answers a unicast discovery request. */
    static int xboxProbe(String ip, int timeoutMs) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(timeoutMs);
            long t = System.nanoTime();
            send(s, XBOX_DISCOVER, ip);
            byte[] buf = new byte[4096];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return (buf[0] & 0xff) == 0xDD && (buf[1] & 0xff) == 0x01 ? (int) ((System.nanoTime() - t) / 1000000) : -1;
        } catch (Exception e) {
            return -1;
        } finally { if (s != null) s.close(); }
    }

    /** Status check for any host type. */
    static int probe(String type, String ip, int port, int timeoutMs) {
        if ("XBOX".equals(type)) {
            int ms = xboxProbe(ip, timeoutMs);
            return ms >= 0 ? ms : Net.ping(ip, port, Math.min(timeoutMs, 500));
        }
        return Net.ping(ip, port, timeoutMs);
    }

    static boolean open(String ip, int port, int timeoutMs) {
        Socket s = new Socket();
        try { s.connect(new InetSocketAddress(ip, port), timeoutMs); return true; }
        catch (Exception e) { return false; }
        finally { try { s.close(); } catch (Exception ignored) {} }
    }

    /** Minimal raw-socket HTTP GET (avoids cleartext-HTTP restrictions on Android 9+). */
    static String http(String ip, int port, String path) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), 1500);
            s.setSoTimeout(2000);
            s.getOutputStream().write(("GET " + path + " HTTP/1.0\r\nHost: " + ip + ":" + port + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            InputStream in = s.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally { try { s.close(); } catch (Exception ignored) {} }
    }

    static String tag(String xml, String t) {
        int a = xml.indexOf("<" + t + ">"), b = xml.indexOf("</" + t + ">");
        return a < 0 || b < a ? null : xml.substring(a + t.length() + 2, b).trim();
    }
}
