package com.omnistream;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private Db db;
    private LinearLayout list;
    private final Handler ui = new Handler();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private List<Host> hosts;
    private final Map<Long, TextView> statusViews = new HashMap<Long, TextView>();
    private final Map<Long, TextView> msgViews = new HashMap<Long, TextView>();
    private final Map<Long, Button> actionButtons = new HashMap<Long, Button>();
    private final Map<Long, Boolean> waking = new HashMap<Long, Boolean>();
    private boolean resumed;

    private final Runnable pinger = new Runnable() {
        public void run() {
            if (!resumed) return;
            final List<Host> snapshot = hosts;
            if (snapshot != null) for (final Host h : snapshot) io.execute(new Runnable() {
                public void run() {
                    h.ping = Discovery.probe(h.type, h.ip, h.port, 1500);
                    ui.post(new Runnable() { public void run() { renderStatus(h); } });
                }
            });
            ui.postDelayed(this, 4000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new Db(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "OmniStream", 28, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = Ui.button(this, "\u2699", Ui.CARD, Ui.TEXT);
        settings.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showSettings(); } });
        header.addView(settings, new LinearLayout.LayoutParams(-2, Ui.dp(this, 44)));
        Button scanB = Ui.button(this, "Scan", Ui.CARD, Ui.ACCENT);
        scanB.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { scan(); } });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
        slp.leftMargin = Ui.dp(this, 8);
        header.addView(scanB, slp);
        Button add = Ui.button(this, "+ Add", Ui.ACCENT, Ui.BG);
        add.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { editHost(null); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
        lp.leftMargin = Ui.dp(this, 8);
        header.addView(add, lp);
        root.addView(header);

        TextView sub = Ui.text(this, "PC (Moonlight) + Xbox · Wake-on-LAN", 13, Ui.DIM);
        sub.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 12));
        root.addView(sub);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        reload();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; reload(); ui.removeCallbacks(pinger); ui.post(pinger); }
    @Override protected void onPause() { super.onPause(); resumed = false; ui.removeCallbacks(pinger); }

    private void reload() {
        hosts = db.all();
        list.removeAllViews();
        statusViews.clear(); msgViews.clear(); actionButtons.clear();
        if (hosts.isEmpty()) {
            TextView e = Ui.text(this, "No hosts yet.\n\nTap \"+ Add\" to save a gaming PC or Xbox.\nYou'll need its IP address, and its MAC address for Wake-on-LAN.", 15, Ui.DIM);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 80), 0, 0);
            list.addView(e);
        }
        for (Host h : hosts) list.addView(card(h));
    }

    private View card(final Host h) {
        int p = Ui.dp(this, 16);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(this, Ui.CARD, 18));
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = Ui.dp(this, 12);
        c.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = Ui.text(this, h.isXbox() ? "XBOX" : "PC", 12, Ui.BG);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(this, h.isXbox() ? Ui.XBOX : Ui.ACCENT, 10));
        top.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView name = Ui.text(this, h.name, 19, Ui.TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(name);
        info.addView(Ui.text(this, h.ip + (h.isXbox() && !h.liveId.isEmpty() ? "  ·  ID " + h.liveId : h.mac.isEmpty() ? "  ·  no MAC (WOL off)" : "  ·  " + h.mac.toUpperCase()), 12, Ui.DIM));
        TextView status = Ui.text(this, "● Checking…", 13, Ui.DIM);
        info.addView(status);
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(top);

        TextView msg = Ui.text(this, "", 13, Ui.AMBER);
        msg.setVisibility(View.GONE);
        msg.setPadding(0, Ui.dp(this, 10), 0, 0);
        c.addView(msg);

        Button go = Ui.button(this, "Wake & Connect", Ui.ACCENT, Ui.BG);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        blp.topMargin = Ui.dp(this, 12);
        c.addView(go, blp);
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { wakeAndConnect(h); } });
        c.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { hostMenu(h); return true; } });

        statusViews.put(h.id, status); msgViews.put(h.id, msg); actionButtons.put(h.id, go);
        renderStatus(h);
        return c;
    }

    private void renderStatus(Host h) {
        TextView s = statusViews.get(h.id);
        Button b = actionButtons.get(h.id);
        if (s == null || b == null) return;
        if (h.ping >= 0) { s.setText("● Online · " + h.ping + " ms"); s.setTextColor(Ui.ACCENT); }
        else if (h.ping == -1) { s.setText("● Offline / asleep"); s.setTextColor(Ui.RED); }
        if (!Boolean.TRUE.equals(waking.get(h.id))) b.setText(h.ping >= 0 ? "Connect" : "Wake & Connect");
    }

    private void showMsg(final Host h, final String text, final int color) {
        ui.post(new Runnable() { public void run() {
            TextView m = msgViews.get(h.id);
            if (m == null) return;
            m.setVisibility(text == null ? View.GONE : View.VISIBLE);
            m.setText(text); m.setTextColor(color);
        }});
    }

    /** Orchestration: ping → (WOL → poll until awake) → open stream session. */
    private void wakeAndConnect(final Host h) {
        if (Boolean.TRUE.equals(waking.get(h.id))) return;
        waking.put(h.id, true);
        actionButtons.get(h.id).setText("Working…");
        io.execute(new Runnable() { public void run() {
            try {
                showMsg(h, "Checking host…", Ui.DIM);
                int ms = Discovery.probe(h.type, h.ip, h.port, 1500);
                if (ms < 0) {
                    if (!canWake(h)) { showMsg(h, "Host unreachable and no MAC address saved, so it can't be woken. Long-press the card to add one.", Ui.RED); return; }
                    showMsg(h, h.isXbox() && !h.liveId.isEmpty() ? "Console asleep, sending Xbox power-on…" : "Host unreachable, sending Wake-on-LAN packet…", Ui.AMBER);
                    wake(h);
                    for (int i = 1; i <= 45 && ms < 0; i++) {
                        Thread.sleep(2000);
                        if (i % 5 == 0) wake(h);
                        showMsg(h, "Waiting for " + h.name + " to wake… " + (i * 2) + "s", Ui.AMBER);
                        ms = Discovery.probe(h.type, h.ip, h.port, 1000);
                    }
                    if (ms < 0) { showMsg(h, "Didn't wake after 90s. Check: same Wi-Fi network, MAC is correct, Wake-on-LAN enabled in BIOS / Xbox set to Sleep (not Energy saving).", Ui.RED); return; }
                }
                h.ping = ms;
                db.touch(h.id);
                showMsg(h, null, Ui.DIM);
                ui.post(new Runnable() { public void run() {
                    Intent i = new Intent();
                    i.setClassName(getPackageName(), h.isXbox() ? "com.omnistream.XboxActivity" : "com.limelight.PcView");
                    i.putExtra("name", h.name); i.putExtra("ip", h.ip); i.putExtra("type", h.type); i.putExtra("port", h.port);
                    startActivity(i);
                }});
            } catch (Exception e) {
                showMsg(h, "Error: " + e.getMessage(), Ui.RED);
            } finally {
                waking.put(h.id, false);
                ui.post(new Runnable() { public void run() { renderStatus(h); } });
            }
        }});
    }

    private void hostMenu(final Host h) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(h.name)
            .setItems(new String[]{"Edit", "Send wake signal only", "Delete"}, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (w == 0) editHost(h);
                    else if (w == 1) io.execute(new Runnable() { public void run() {
                        try { wake(h); showMsg(h, "Magic packet sent.", Ui.ACCENT); }
                        catch (Exception e) { showMsg(h, "Error: " + e.getMessage(), Ui.RED); }
                    }});
                    else { db.delete(h.id); reload(); }
                }
            }).show();
    }

    private EditText field(LinearLayout parent, String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(value); e.setInputType(type); e.setSingleLine(true);
        e.setTextColor(Ui.TEXT); e.setHintTextColor(Ui.DIM);
        parent.addView(e);
        return e;
    }

    private void editHost(final Host existing) {
        final Host h = existing != null ? existing : new Host();
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        final RadioGroup type = new RadioGroup(this);
        type.setOrientation(LinearLayout.HORIZONTAL);
        final RadioButton pc = new RadioButton(this); pc.setText("Gaming PC"); pc.setId(1);
        final RadioButton xb = new RadioButton(this); xb.setText("Xbox"); xb.setId(2);
        type.addView(pc); type.addView(xb);
        type.check(h.isXbox() ? 2 : 1);
        f.addView(type);
        final EditText name = field(f, "Name (e.g. Living room PC)", h.name, InputType.TYPE_CLASS_TEXT);
        final EditText ip = field(f, "IP address (e.g. 192.168.1.20)", h.ip, InputType.TYPE_CLASS_TEXT);
        final EditText mac = field(f, h.isXbox() && !h.liveId.isEmpty() ? "MAC (optional, Xbox ID found)" : "MAC address (for Wake-on-LAN)", h.mac, InputType.TYPE_CLASS_TEXT);
        final EditText port = field(f, "Port", existing == null ? "" : "" + h.port, InputType.TYPE_CLASS_NUMBER);
        port.setHint("Port (blank = 47989 PC / 5050 Xbox)");

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(h.id == 0 ? "Add host" : "Edit host").setView(f)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    h.type = type.getCheckedRadioButtonId() == 2 ? "XBOX" : "PC";
                    h.name = name.getText().toString().trim();
                    if (h.name.isEmpty()) h.name = h.isXbox() ? "Xbox" : "Gaming PC";
                    h.ip = ip.getText().toString().trim();
                    h.mac = mac.getText().toString().trim();
                    String ps = port.getText().toString().trim();
                    h.port = ps.isEmpty() ? (h.isXbox() ? 5050 : 47989) : Integer.parseInt(ps);
                    if (h.ip.isEmpty()) { Toast.makeText(MainActivity.this, "IP address is required", Toast.LENGTH_LONG).show(); return; }
                    if (!h.mac.isEmpty()) {
                        try { Net.magicPacket(h.mac); }
                        catch (Exception e) { Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
                    }
                    db.save(h);
                    reload();
                    ui.removeCallbacks(pinger); ui.post(pinger);
                }
            }).show();
    }

    private Spinner spinner(LinearLayout f, String label, String[] items, String current) {
        f.addView(Ui.text(this, label, 13, Ui.DIM));
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items));
        for (int i = 0; i < items.length; i++) if (items[i].equals(current)) s.setSelection(i);
        f.addView(s);
        return s;
    }

    private void showSettings() {
        final SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        final Spinner res = spinner(f, "Resolution", new String[]{"720p", "1080p", "1440p", "4K"}, sp.getString("res", "1080p"));
        final Spinner fps = spinner(f, "Frame rate", new String[]{"30", "60", "90", "120"}, sp.getString("fps", "60"));
        final Spinner codec = spinner(f, "Video codec", new String[]{"H.264", "HEVC", "AV1"}, sp.getString("codec", "HEVC"));
        final Spinner dec = spinner(f, "Decoder", new String[]{"Hardware (MediaCodec)", "Software"}, sp.getString("decoder", "Hardware (MediaCodec)"));
        final Spinner audio = spinner(f, "Audio", new String[]{"Stereo", "5.1 Surround", "7.1 Surround"}, sp.getString("audio", "Stereo"));
        f.addView(Ui.text(this, "Bitrate (Mbps)", 13, Ui.DIM));
        final EditText br = field(f, "20", "" + sp.getInt("bitrate", 20), InputType.TYPE_CLASS_NUMBER);
        final CheckBox pacing = new CheckBox(this);
        pacing.setText("Network packet pacing");
        pacing.setChecked(sp.getBoolean("pacing", false));
        f.addView(pacing);
        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Stream settings").setView(sv)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    int b = 20;
                    try { b = Math.max(1, Math.min(150, Integer.parseInt(br.getText().toString()))); } catch (Exception ignored) {}
                    sp.edit().putString("res", (String) res.getSelectedItem()).putString("fps", (String) fps.getSelectedItem())
                        .putString("codec", (String) codec.getSelectedItem()).putString("decoder", (String) dec.getSelectedItem())
                        .putString("audio", (String) audio.getSelectedItem()).putInt("bitrate", b)
                        .putBoolean("pacing", pacing.isChecked()).apply();
                }
            }).show();
    }

    private static boolean canWake(Host h) { return !h.mac.isEmpty() || (h.isXbox() && !h.liveId.isEmpty()); }

    /** Xbox: SmartGlass power-on via Live ID; anything with a MAC: Wake-on-LAN magic packet. */
    private static void wake(Host h) throws Exception {
        if (!canWake(h)) throw new IllegalStateException("No MAC address or Xbox ID saved");
        if (h.isXbox() && !h.liveId.isEmpty()) Discovery.xboxPowerOn(h.liveId, h.ip);
        if (!h.mac.isEmpty()) Net.wake(h.mac, h.ip);
    }

    /** Finds Sunshine PCs and Xbox consoles on the local network; tap one to add it pre-filled. */
    private void scan() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        final TextView state = Ui.text(this, "Searching this Wi-Fi network… (about 15s)", 14, Ui.DIM);
        f.addView(state);
        final ProgressBar bar = new ProgressBar(this);
        f.addView(bar);
        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Discover devices").setView(sv).setNegativeButton("Close", null).show();
        final LinearLayout list2 = f;
        final java.util.Set<String> saved = new java.util.HashSet<String>();
        for (Host x : hosts) saved.add(x.ip);
        if (Discovery.subnetPrefix(this) == null) state.setText("Not on Wi-Fi: PC scan skipped. Looking for Xbox only…");
        Discovery.scan(this, new Discovery.Listener() {
            public void found(final Host h) { ui.post(new Runnable() { public void run() {
                boolean already = saved.contains(h.ip);
                Button b = Ui.button(MainActivity.this, (h.isXbox() ? "XBOX  ·  " : "PC  ·  ") + h.name + "\n" + h.ip + (already ? "  (already saved)" : ""),
                        Ui.CARD, h.isXbox() ? Ui.XBOX : Ui.ACCENT);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setPadding(Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 10));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = Ui.dp(MainActivity.this, 8);
                b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                    dlg.dismiss();
                    if (!h.isXbox() && h.mac.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Sunshine didn't share its MAC. Add it to enable Wake-on-LAN (optional).", Toast.LENGTH_LONG).show();
                        editHost(h);
                    } else { db.save(h); reload(); ui.removeCallbacks(pinger); ui.post(pinger);
                        Toast.makeText(MainActivity.this, "Added " + h.name, Toast.LENGTH_SHORT).show(); }
                }});
                list2.addView(b, lp);
            }}); }
            public void done() { ui.post(new Runnable() { public void run() {
                bar.setVisibility(View.GONE);
                state.setText(list2.getChildCount() > 2 ? "Done. Tap a device to add it."
                    : "Nothing found. Make sure your phone is on the same Wi-Fi, Sunshine is running, and the Xbox has remote features enabled. You can still use \"+ Add\".");
            }}); }
        });
    }
}
