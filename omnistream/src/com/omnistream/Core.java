package com.omnistream;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.TextView;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

/** Data model for a saved PC or Xbox. */
final class Host {
    long id; String name = "", type = "PC", ip = "", mac = "", liveId = ""; int port = 47989; long lastConnected;
    volatile int ping = -2; // -2 unknown, -1 offline, >=0 ms
    boolean isXbox() { return "XBOX".equals(type); }
}

/** Room-equivalent local storage (plain SQLite, no external libraries). */
final class Db extends SQLiteOpenHelper {
    Db(Context c) { super(c, "omni.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE hosts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, type TEXT, ip TEXT, mac TEXT, port INTEGER, lastConnected INTEGER, liveId TEXT DEFAULT '')");
    }
    @Override public void onUpgrade(SQLiteDatabase d, int a, int b) { if (a < 2) d.execSQL("ALTER TABLE hosts ADD COLUMN liveId TEXT DEFAULT ''"); }

    List<Host> all() {
        List<Host> out = new ArrayList<Host>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id,name,type,ip,mac,port,lastConnected,liveId FROM hosts ORDER BY lastConnected DESC, name", null);
        while (c.moveToNext()) {
            Host h = new Host();
            h.id = c.getLong(0); h.name = c.getString(1); h.type = c.getString(2); h.ip = c.getString(3);
            h.mac = c.getString(4); h.port = c.getInt(5); h.lastConnected = c.getLong(6); h.liveId = c.isNull(7) ? "" : c.getString(7);
            out.add(h);
        }
        c.close();
        return out;
    }
    void save(Host h) {
        ContentValues v = new ContentValues();
        v.put("name", h.name); v.put("type", h.type); v.put("ip", h.ip); v.put("mac", h.mac);
        v.put("port", h.port); v.put("lastConnected", h.lastConnected); v.put("liveId", h.liveId);
        if (h.id == 0) h.id = getWritableDatabase().insert("hosts", null, v);
        else getWritableDatabase().update("hosts", v, "id=?", new String[]{"" + h.id});
    }
    void delete(long id) { getWritableDatabase().delete("hosts", "id=?", new String[]{"" + id}); }
    void touch(long id) {
        ContentValues v = new ContentValues(); v.put("lastConnected", System.currentTimeMillis());
        getWritableDatabase().update("hosts", v, "id=?", new String[]{"" + id});
    }
}

/** Wake-on-LAN + reachability. All methods block: call from a background thread. */
final class Net {
    static byte[] magicPacket(String mac) {
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("MAC address must have 12 hex digits");
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        byte[] p = new byte[102];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
        for (int r = 0; r < 16; r++) System.arraycopy(m, 0, p, 6 + r * 6, 6);
        return p;
    }

    /** Sends the magic packet to 255.255.255.255 and the host's /24 broadcast, ports 9 and 7, three times. */
    static void wake(String mac, String ip) throws Exception {
        byte[] p = magicPacket(mac);
        List<InetAddress> targets = new ArrayList<InetAddress>();
        targets.add(InetAddress.getByName("255.255.255.255"));
        if (ip != null && ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"))
            targets.add(InetAddress.getByName(ip.substring(0, ip.lastIndexOf('.')) + ".255"));
        DatagramSocket s = new DatagramSocket();
        try {
            s.setBroadcast(true);
            for (int n = 0; n < 3; n++)
                for (InetAddress a : targets)
                    for (int port : new int[]{9, 7})
                        s.send(new DatagramPacket(p, p.length, a, port));
        } finally { s.close(); }
    }

    /** Round-trip in ms, or -1 if unreachable. A refused TCP connection still proves the host is awake. */
    static int ping(String ip, int port, int timeoutMs) {
        long t = System.nanoTime();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return (int) ((System.nanoTime() - t) / 1000000);
        } catch (ConnectException e) {
            String m = String.valueOf(e.getMessage());
            return m.contains("refused") || m.contains("ECONNREFUSED") ? (int) ((System.nanoTime() - t) / 1000000) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}

/** Small UI helpers for the dark OLED theme. */
final class Ui {
    static final int BG = Color.BLACK, CARD = 0xFF121417, ACCENT = 0xFF00E676, XBOX = 0xFF52B043,
            TEXT = 0xFFECEFF1, DIM = 0xFF8A939B, RED = 0xFFFF5252, AMBER = 0xFFFFC400;

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }
    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }
    static GradientDrawable round(Context c, int color, float r) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, r)); return g;
    }
    static Button button(Context c, String s, int bg, int fg) {
        Button b = new Button(c); b.setText(s); b.setTextColor(fg); b.setAllCaps(false); b.setTextSize(15);
        b.setBackground(round(c, bg, 24)); b.setPadding(dp(c, 18), 0, dp(c, 18), 0); return b;
    }
    /** Opens an installed app, or its Play Store page if missing. */
    static void openApp(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}
