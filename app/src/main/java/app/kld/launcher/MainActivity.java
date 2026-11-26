package app.kld.launcher;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF000000, TEXT = 0xFFFFFFFF, SEC = 0xFF8A8A8A, DIV = 0xFF222222;
    private static final int DOCK_MAX = 4;

    static final class App { String key, label; ComponentName cn; int n; }

    private final HashMap<String, App> byKey = new HashMap<>();
    private final ArrayList<App> shown = new ArrayList<>();   // the App list (not docked)
    private final ArrayList<String> dock = new ArrayList<>(); // docked keys, no gaps
    private final ImageView[] slots = new ImageView[DOCK_MAX];
    private SharedPreferences counts, labels, prefs;
    private PackageManager pm;
    private LruCache<String, Drawable> iconCache; // non-null only while Show icons is On and visible
    private LinearLayout main;
    private ListView list;
    private BaseAdapter adapter;
    private boolean icons, inSettings;
    private float density;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        density = getResources().getDisplayMetrics().density;
        counts = getSharedPreferences("c", 0);
        labels = getSharedPreferences("l", 0);
        prefs = getSharedPreferences("s", 0); // keys: "icons", "dock"
        icons = prefs.getBoolean("icons", false);
        main = buildMain();
        setContentView(main);
        if (b == null) askDefaultHome();
    }

    @Override protected void onStart() {
        super.onStart();
        load();
        bind();
    }

    @Override protected void onStop() {
        super.onStop();
        for (ImageView s : slots) {
            s.setImageDrawable(null);
            s.setTag(null);
        }
        iconCache = null;
        for (int i = 0, c = list.getChildCount(); i < c; i++) {
            ((TextView) list.getChildAt(i)).setCompoundDrawablesRelative(null, null, null, null);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (inSettings) showMain();
        list.setSelection(0);
    }

    @Override public void onBackPressed() { if (inSettings) showMain(); }

    // Asks to become the default Home app. Does nothing once it is the default.
    private void askDefaultHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1);
                }
            } else {
                ResolveInfo r = pm.resolveActivity(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
                if (r == null || r.activityInfo == null || !getPackageName().equals(r.activityInfo.packageName)) {
                    startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                }
            }
        } catch (Exception ignored) { }
    }

    // ---- screens ----

    private LinearLayout buildMain() {
        final int pad = dp(16);
        LinearLayout m = column();
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                TextView t = (TextView) v;
                if (t == null) {
                    t = new TextView(MainActivity.this);
                    t.setTextSize(20);
                    t.setTextColor(TEXT);
                    t.setSingleLine(true);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    t.setPadding(pad, pad, pad, pad);
                    t.setCompoundDrawablePadding(pad);
                }
                App a = shown.get(i);
                t.setText(a.label);
                t.setCompoundDrawablesRelative(iconCache == null ? null : listIcon(a), null, null, null);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setRecyclerListener(v -> ((TextView) v).setCompoundDrawablesRelative(null, null, null, null));
        list.setOnItemClickListener((p, v, i, id) -> launch(shown.get(i)));
        list.setOnItemLongClickListener((p, v, i, id) -> { startDrag(v, shown.get(i).key); return true; });
        list.setOnDragListener(target(-1));
        m.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        m.addView(rule());

        LinearLayout bar = new LinearLayout(this);
        bar.setOnDragListener(target(DOCK_MAX));
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setScaleType(ImageView.ScaleType.CENTER);
        gear.setContentDescription("Settings");
        gear.setOnClickListener(v -> showSettings());
        gear.setOnDragListener(target(DOCK_MAX));
        bar.addView(gear, new LinearLayout.LayoutParams(0, -1, 1));
        for (int i = 0; i < DOCK_MAX; i++) {
            final int slot = i;
            ImageView s = new ImageView(this);
            s.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            s.setPadding(dp(8), dp(8), dp(8), dp(8));
            s.setOnClickListener(v -> { if (slot < dock.size()) launch(byKey.get(dock.get(slot))); });
            s.setOnLongClickListener(v -> { if (slot < dock.size()) startDrag(v, dock.get(slot)); return true; });
            s.setOnDragListener(target(slot));
            slots[i] = s;
            bar.addView(s, new LinearLayout.LayoutParams(0, -1, 1));
        }
        m.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));
        return m;
    }

    private void showMain() {
        inSettings = false;
        setContentView(main);
        if (icons != (iconCache != null)) {
            iconCache = icons ? new LruCache<>(32) : null;
            adapter.notifyDataSetChanged();
        }
    }

    private void showSettings() {
        inSettings = true;
        LinearLayout s = column();
        s.addView(row("Settings", "", null));
        s.addView(rule());
        s.addView(row("Show icons", icons ? "On" : "Off", v -> {
            icons = !icons;
            prefs.edit().putBoolean("icons", icons).apply();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(icons ? "On" : "Off");
        }));
        s.addView(rule());
        s.addView(row("Manage apps", "", v -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)); }
            catch (Exception ignored) { }
        }));
        s.addView(rule());
        setContentView(s);
    }

    // ---- data ----

    private void bind() {
        shown.clear();
        for (App app : byKey.values()) if (!dock.contains(app.key)) shown.add(app);
        Collections.sort(shown, (x, y) -> x.n != y.n ? y.n - x.n : x.label.compareToIgnoreCase(y.label));
        if (icons && iconCache == null) iconCache = new LruCache<>(32);
        else if (!icons) iconCache = null;
        adapter.notifyDataSetChanged();
        bindDock();
    }

    private void bindDock() {
        for (int i = 0; i < DOCK_MAX; i++) {
            App a = i < dock.size() ? byKey.get(dock.get(i)) : null;
            String k = a == null ? null : a.key;
            if (!Objects.equals(k, slots[i].getTag())) {
                slots[i].setTag(k);
                slots[i].setImageDrawable(a == null ? null : icon(a.cn));
                slots[i].setContentDescription(a == null ? null : a.label);
            }
        }
    }

    private void load() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        // Cached names are only valid for the language they were loaded in.
        String loc = getResources().getConfiguration().getLocales().get(0).toLanguageTag();
        if (!loc.equals(labels.getString("_loc", ""))) labels.edit().clear().putString("_loc", loc).apply();
        SharedPreferences.Editor ed = null;
        byKey.clear();
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            ActivityInfo a = r.activityInfo;
            if (a.packageName.equals(getPackageName())) continue;
            App app = new App();
            app.cn = new ComponentName(a.packageName, a.name);
            app.key = app.cn.flattenToShortString();
            app.label = labels.getString(app.key, null);
            if (app.label == null) {
                app.label = r.loadLabel(pm).toString();
                if (ed == null) ed = labels.edit();
                ed.putString(app.key, app.label);
            }
            app.n = counts.getInt(app.key, 0);
            byKey.put(app.key, app);
        }
        if (ed != null) ed.apply();

        dock.clear();
        String saved = prefs.getString("dock", "");
        for (String k : saved.split(",")) {
            if (byKey.containsKey(k) && !dock.contains(k) && dock.size() < DOCK_MAX) dock.add(k);
        }
        String joined = TextUtils.join(",", dock);
        if (!joined.equals(saved)) prefs.edit().putString("dock", joined).apply(); // uninstalled apps drop out
    }

    private void launch(App a) {
        if (a == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(a.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            counts.edit().putInt(a.key, ++a.n).apply();
        } catch (Exception ignored) { }
    }

    // ---- drag and drop ----

    private void startDrag(View v, String key) {
        v.startDragAndDrop(ClipData.newPlainText("k", key), new View.DragShadowBuilder(v), key, 0);
    }

    // t = -1: App list. 0..DOCK_MAX-1: that Dock slot. DOCK_MAX: Settings button (free area).
    private View.OnDragListener target(final int t) {
        return (v, e) -> {
            if (e.getAction() == DragEvent.ACTION_DROP && e.getLocalState() instanceof String) {
                drop((String) e.getLocalState(), t);
            }
            return true; // accept every drag so we receive the drop
        };
    }

    private void drop(String key, int t) {
        if (!byKey.containsKey(key)) return;
        int from = dock.indexOf(key);
        boolean listChanged = true;
        if (t < 0) {                                   // onto the App list: undock
            if (from < 0) return;
            dock.remove(from);
        } else if (t < dock.size()) {                  // onto an occupied slot: swap
            if (from == t) return;
            if (from >= 0) { Collections.swap(dock, from, t); listChanged = false; }
            else dock.set(t, key);                     // displaced App returns to the list
        } else if (from < 0 && dock.size() < DOCK_MAX) { // free area: leftmost free slot
            dock.add(key);
        } else {
            return;
        }
        prefs.edit().putString("dock", TextUtils.join(",", dock)).apply();
        if (listChanged) bind(); else bindDock();
    }

    // ---- icons ----

    private Drawable icon(ComponentName cn) {
        try { return pm.getActivityIcon(cn); } catch (Exception e) { return null; }
    }

    private Drawable listIcon(App a) {
        Drawable d = iconCache.get(a.key);
        if (d == null && (d = icon(a.cn)) != null) {
            d.setBounds(0, 0, dp(32), dp(32));
            iconCache.put(a.key, d);
        }
        return d;
    }

    // ---- small view helpers ----

    private int dp(int v) { return (int) (v * density + 0.5f); }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(BG);
        c.setOnApplyWindowInsetsListener((v, w) -> {
            v.setPadding(w.getSystemWindowInsetLeft(), w.getSystemWindowInsetTop(),
                    w.getSystemWindowInsetRight(), w.getSystemWindowInsetBottom());
            return w;
        });
        return c;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(DIV);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return v;
    }

    private View row(String label, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setMinimumHeight(dp(48));
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView l = new TextView(this);
        l.setTextSize(18);
        l.setTextColor(TEXT);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = new TextView(this);
        val.setTextSize(14);
        val.setTextColor(SEC);
        val.setText(value);
        r.addView(val);
        if (click != null) r.setOnClickListener(click);
        return r;
    }
}
