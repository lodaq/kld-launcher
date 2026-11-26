package app.kld.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF000000, TEXT = 0xFFFFFFFF, SEC = 0xFF8A8A8A, DIV = 0xFF222222;
    private static final int DOCK_MAX = 5, DOCK_CELLS = DOCK_MAX + 1, MAX_SHORTCUTS = 5;
    private static final int SCREEN_MAIN = 0, SCREEN_SETTINGS = 1, SCREEN_HIDDEN = 2, SCREEN_SORT_DOCK = 3, SCREEN_APP_MENU = 4;
    private static final String GEAR_KEY = "_gear";

    static final class App { String key, label; ComponentName cn; UserHandle user; int n; }

    private final HashMap<String, App> byKey = new HashMap<>();
    private final HashSet<String> pkgs = new HashSet<>();
    private final ArrayList<App> shown = new ArrayList<>();   // the App list (not docked, not hidden)
    private final ArrayList<String> dock = new ArrayList<>(); // docked keys, no gaps
    private final HashSet<String> hidden = new HashSet<>();
    private final ImageView[] cells = new ImageView[DOCK_CELLS];
    private SharedPreferences counts, labels, prefs;
    private PackageManager pm;
    private ActivityManager am;
    private LauncherApps la;
    private UserManager um;
    private LruCache<String, Drawable> iconCache; // non-null only while Show icons is On and visible
    private LinearLayout main;
    private ListView list;
    private BaseAdapter adapter;
    private boolean icons, clones, singleApp, dirty, reload, sortFromSettings;
    private int screen, gearPos;
    private float density;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        am = getSystemService(ActivityManager.class);
        la = getSystemService(LauncherApps.class);
        um = getSystemService(UserManager.class);
        density = getResources().getDisplayMetrics().density;
        counts = getSharedPreferences("c", 0);
        labels = getSharedPreferences("l", 0);
        prefs = getSharedPreferences("s", 0); // keys: "icons", "clones", "single", "dock", "gear", "hidden"
        icons = prefs.getBoolean("icons", false);
        clones = prefs.getBoolean("clones", false);
        singleApp = prefs.getBoolean("single", false);
        gearPos = Math.max(0, Math.min(DOCK_MAX, prefs.getInt("gear", 0)));
        hidden.addAll(prefs.getStringSet("hidden", Collections.emptySet()));
        main = buildMain();
        setContentView(main);
        if (b == null) askDefaultHome();
    }

    @Override protected void onStart() {
        super.onStart();
        load();
        bind();
        dirty = reload = false;
    }

    @Override protected void onStop() {
        super.onStop();
        for (ImageView c : cells) {
            c.setImageDrawable(null);
            c.setTag(null);
        }
        iconCache = null;
        for (int i = 0, c = list.getChildCount(); i < c; i++) {
            ((TextView) list.getChildAt(i)).setCompoundDrawablesRelative(null, null, null, null);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (screen != SCREEN_MAIN) showMain();
        list.setSelection(0);
    }

    @Override public void onBackPressed() {
        if (screen == SCREEN_HIDDEN) showSettings();
        else if (screen == SCREEN_SORT_DOCK) {
            if (sortFromSettings) showSettings();
            else showMain();
        } else if (screen == SCREEN_SETTINGS || screen == SCREEN_APP_MENU) {
            showMain();
        }
    }

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
        list.setOnItemLongClickListener((p, v, i, id) -> { showAppMenu(shown.get(i)); return true; });
        m.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        m.addView(rule());

        LinearLayout bar = new LinearLayout(this);
        for (int i = 0; i < DOCK_CELLS; i++) {
            final int cell = i;
            ImageView s = new ImageView(this);
            s.setOnClickListener(v -> {
                if (cell == gearPos) showSettings();
                else {
                    int slot = slotFor(cell);
                    if (slot < dock.size()) launch(byKey.get(dock.get(slot)));
                }
            });
            s.setOnLongClickListener(v -> {
                if (cell == gearPos) {
                    showSortDock(false);
                    return true;
                }
                int slot = slotFor(cell);
                if (slot < dock.size()) {
                    App a = byKey.get(dock.get(slot));
                    if (a != null) showAppMenu(a);
                    return true;
                }
                return false;
            });
            cells[i] = s;
            bar.addView(s, new LinearLayout.LayoutParams(0, -1, 1));
        }
        m.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));
        return m;
    }

    private void showMain() {
        screen = SCREEN_MAIN;
        setContentView(main);
        if (reload) {
            reload = dirty = false;
            load();
            bind();
        } else if (dirty) {
            dirty = false;
            bind();
        } else if (icons != (iconCache != null)) {
            iconCache = icons ? new LruCache<>(32) : null;
            adapter.notifyDataSetChanged();
        }
    }

    private void showSettings() {
        screen = SCREEN_SETTINGS;
        LinearLayout s = column();
        s.addView(row("Settings", "", null));
        s.addView(rule());
        s.addView(row("Show icons", icons ? "On" : "Off", v -> {
            icons = !icons;
            prefs.edit().putBoolean("icons", icons).apply();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(icons ? "On" : "Off");
        }));
        s.addView(rule());
        s.addView(row("Show clones", clones ? "On" : "Off", v -> {
            clones = !clones;
            reload = true;
            prefs.edit().putBoolean("clones", clones).apply();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(clones ? "On" : "Off");
        }));
        s.addView(rule());
        s.addView(row("Close previous app", singleApp ? "On" : "Off", v -> {
            singleApp = !singleApp;
            prefs.edit().putBoolean("single", singleApp).apply();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(singleApp ? "On" : "Off");
        }));
        s.addView(rule());
        s.addView(row("Hidden apps", String.valueOf(hidden.size()), v -> showHidden()));
        s.addView(rule());
        s.addView(row("Sort dock", "", v -> showSortDock(true)));
        s.addView(rule());
        s.addView(row("Manage apps", "", v -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)); }
            catch (Exception ignored) { }
        }));
        s.addView(rule());
        setContentView(s);
    }

    private void showHidden() {
        screen = SCREEN_HIDDEN;
        if (reload) {
            reload = false;
            load();
            bind();
        }
        final ArrayList<App> all = new ArrayList<>(byKey.values());
        Collections.sort(all, (x, y) -> {
            boolean hx = hidden.contains(x.key), hy = hidden.contains(y.key);
            return hx != hy ? (hx ? -1 : 1) : x.label.compareToIgnoreCase(y.label);
        });
        LinearLayout s = column();
        s.addView(row("Hidden apps", "", null));
        s.addView(rule());
        ListView hl = new ListView(this);
        hl.setDivider(null);
        hl.setSelector(android.R.color.transparent);
        hl.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hl.setAdapter(new BaseAdapter() {
            public int getCount() { return all.size(); }
            public Object getItem(int i) { return all.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                LinearLayout r = v != null ? (LinearLayout) v : (LinearLayout) row("", "", null);
                App a = all.get(i);
                ((TextView) r.getChildAt(0)).setText(a.label);
                ((TextView) r.getChildAt(1)).setText(hidden.contains(a.key) ? "Hidden" : "");
                return r;
            }
        });
        hl.setOnItemClickListener((p, v, i, id) -> {
            App a = all.get(i);
            boolean nowHidden;
            if (hidden.contains(a.key)) {
                hidden.remove(a.key);
                nowHidden = false;
            } else {
                hidden.add(a.key);
                nowHidden = true;
                if (dock.remove(a.key)) saveDock();
            }
            prefs.edit().putStringSet("hidden", new HashSet<>(hidden)).apply();
            dirty = true;
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(nowHidden ? "Hidden" : "");
        });
        s.addView(hl, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(s);
    }

    private void showSortDock(boolean fromSettings) {
        screen = SCREEN_SORT_DOCK;
        sortFromSettings = fromSettings;
        if (reload) {
            reload = false;
            load();
            bind();
        }
        LinearLayout s = column();
        s.addView(row("Sort dock", "", null));
        s.addView(rule());
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        refreshSortDock(body);
        s.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(s);
    }

    private void refreshSortDock(LinearLayout body) {
        body.removeAllViews();
        int pad = dp(16), btnPad = dp(12);
        for (int i = 0; i < DOCK_CELLS; i++) {
            final int cell = i;
            boolean isGear = cell == gearPos;
            int slot = slotFor(cell);
            App a = !isGear && slot < dock.size() ? byKey.get(dock.get(slot)) : null;

            LinearLayout r = new LinearLayout(this);
            r.setMinimumHeight(dp(48));
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(pad, pad, btnPad, pad);

            TextView label = new TextView(this);
            label.setTextSize(18);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            if (isGear) {
                label.setTextColor(TEXT);
                label.setText("Settings");
            } else if (a != null) {
                label.setTextColor(TEXT);
                label.setText(a.label);
            } else {
                label.setTextColor(SEC);
                label.setText("Empty");
            }
            r.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

            if (isGear || a != null) {
                boolean canLeft = cell > 0;
                boolean canRight = isGear ? cell < DOCK_CELLS - 1
                        : (cell + 1 == gearPos || (cell + 1 < DOCK_CELLS && slotFor(cell + 1) < dock.size()));
                if (canLeft) {
                    r.addView(actionButton("Up", btnPad, v -> {
                        moveDockCell(cell, -1);
                        refreshSortDock(body);
                    }));
                }
                if (canRight) {
                    r.addView(actionButton("Down", btnPad, v -> {
                        moveDockCell(cell, 1);
                        refreshSortDock(body);
                    }));
                }
            }
            body.addView(r);
            body.addView(rule());
        }
    }

    private TextView actionButton(String text, int padH, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(SEC);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(48));
        b.setPadding(padH, 0, padH, 0);
        b.setOnClickListener(click);
        return b;
    }

    private void moveDockCell(int cell, int delta) {
        int next = cell + delta;
        if (next < 0 || next >= DOCK_CELLS) return;
        if (cell == gearPos) {
            gearPos = next;
            prefs.edit().putInt("gear", gearPos).apply();
        } else if (next == gearPos) {
            gearPos = cell;
            prefs.edit().putInt("gear", gearPos).apply();
        } else {
            int s1 = slotFor(cell), s2 = slotFor(next);
            if (s1 < dock.size() && s2 < dock.size()) {
                Collections.swap(dock, s1, s2);
                saveDock();
            } else {
                return;
            }
        }
        dirty = true;
    }

    private void showAppMenu(final App a) {
        screen = SCREEN_APP_MENU;
        LinearLayout s = column();
        s.addView(row(a.label, "", null));
        s.addView(rule());

        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);

        if (Build.VERSION.SDK_INT >= 25 && la != null) {
            addShortcuts(items, a);
        }

        int dockIdx = dock.indexOf(a.key);
        if (dockIdx >= 0) {
            if (dockIdx > 0) {
                items.addView(row("Move left", "", v -> {
                    Collections.swap(dock, dockIdx, dockIdx - 1);
                    saveDock();
                    dirty = true;
                    showMain();
                }));
                items.addView(rule());
            }
            if (dockIdx < dock.size() - 1) {
                items.addView(row("Move right", "", v -> {
                    Collections.swap(dock, dockIdx, dockIdx + 1);
                    saveDock();
                    dirty = true;
                    showMain();
                }));
                items.addView(rule());
            }
            items.addView(row("Unpin from dock", "", v -> {
                dock.remove(dockIdx);
                saveDock();
                dirty = true;
                showMain();
            }));
            items.addView(rule());
        } else if (dock.size() < DOCK_MAX) {
            items.addView(row("Pin to dock", "", v -> {
                dock.add(a.key);
                saveDock();
                dirty = true;
                showMain();
            }));
            items.addView(rule());
        } else {
            items.addView(row("Pin to dock", "Dock full", null));
            items.addView(rule());
        }

        items.addView(row("App info", "", v -> {
            try {
                if (a.user != null && la != null) {
                    la.startAppDetailsActivity(a.cn, a.user, null, null);
                } else {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", a.cn.getPackageName(), null)));
                }
            } catch (Exception ignored) { }
        }));
        items.addView(rule());

        if (a.user == null && isRemovable(a.cn.getPackageName())) {
            items.addView(row("Uninstall", "", v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE,
                            Uri.fromParts("package", a.cn.getPackageName(), null)));
                } catch (Exception ignored) { }
            }));
            items.addView(rule());
        }

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(items);
        s.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(s);
    }

    private void addShortcuts(LinearLayout items, App a) {
        if (Build.VERSION.SDK_INT < 25 || la == null) return;
        try {
            if (!la.hasShortcutHostPermission()) return;
            UserHandle uh = a.user != null ? a.user : Process.myUserHandle();
            int flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery()
                    .setPackage(a.cn.getPackageName())
                    .setActivity(a.cn)
                    .setQueryFlags(flags);
            List<ShortcutInfo> raw = la.getShortcuts(q, uh);
            if (raw == null || raw.isEmpty()) {
                q.setActivity(null);
                raw = la.getShortcuts(q, uh);
            }
            if (raw == null || raw.isEmpty()) return;
            ArrayList<ShortcutInfo> enabled = new ArrayList<>();
            for (ShortcutInfo si : raw) {
                if (si.isEnabled()) enabled.add(si);
            }
            if (enabled.isEmpty()) return;
            Collections.sort(enabled, (x, y) -> {
                boolean mx = x.isDeclaredInManifest(), my = y.isDeclaredInManifest();
                return mx != my ? (mx ? -1 : 1) : Integer.compare(x.getRank(), y.getRank());
            });
            int count = Math.min(MAX_SHORTCUTS, enabled.size());
            int dpi = getResources().getDisplayMetrics().densityDpi;
            int iconPx = dp(24);
            for (int i = 0; i < count; i++) {
                final ShortcutInfo si = enabled.get(i);
                CharSequence lbl = si.getShortLabel();
                if (TextUtils.isEmpty(lbl)) lbl = si.getLongLabel();
                if (TextUtils.isEmpty(lbl)) continue;
                View r = row(lbl.toString(), "", v -> {
                    closePrevious(a.cn.getPackageName());
                    try { la.startShortcut(si, null, null); } catch (Exception ignored) { }
                    showMain();
                });
                if (icons) {
                    Drawable d = la.getShortcutIconDrawable(si, dpi);
                    if (d != null) {
                        d.setBounds(0, 0, iconPx, iconPx);
                        TextView tv = (TextView) ((LinearLayout) r).getChildAt(0);
                        tv.setCompoundDrawablePadding(dp(12));
                        tv.setCompoundDrawablesRelative(d, null, null, null);
                    }
                }
                items.addView(r);
                items.addView(rule());
            }
        } catch (Exception ignored) { }
    }

    private boolean isRemovable(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- data ----

    private int slotFor(int cell) { return cell < gearPos ? cell : cell - 1; }

    private void saveDock() {
        prefs.edit().putString("dock", TextUtils.join(",", dock)).apply();
    }

    private void bind() {
        shown.clear();
        for (App app : byKey.values()) {
            if (!dock.contains(app.key) && !hidden.contains(app.key)) shown.add(app);
        }
        Collections.sort(shown, (x, y) -> x.n != y.n ? y.n - x.n : x.label.compareToIgnoreCase(y.label));
        if (icons && iconCache == null) iconCache = new LruCache<>(32);
        else if (!icons) iconCache = null;
        adapter.notifyDataSetChanged();
        bindDock();
    }

    private void bindDock() {
        String[] oldKeys = new String[DOCK_CELLS];
        Drawable[] oldDrawables = new Drawable[DOCK_CELLS];
        for (int i = 0; i < DOCK_CELLS; i++) {
            String t = (String) cells[i].getTag();
            oldKeys[i] = t;
            if (!GEAR_KEY.equals(t)) oldDrawables[i] = cells[i].getDrawable();
        }
        for (int i = 0; i < DOCK_CELLS; i++) {
            if (i == gearPos) {
                if (!GEAR_KEY.equals(oldKeys[i])) {
                    cells[i].setTag(GEAR_KEY);
                    cells[i].setScaleType(ImageView.ScaleType.CENTER);
                    cells[i].setPadding(0, 0, 0, 0);
                    cells[i].setImageResource(R.drawable.ic_settings);
                    cells[i].setContentDescription("Settings");
                }
                continue;
            }
            int slot = slotFor(i);
            App a = slot < dock.size() ? byKey.get(dock.get(slot)) : null;
            String k = a == null ? null : a.key;
            if (!Objects.equals(k, oldKeys[i]) || GEAR_KEY.equals(oldKeys[i])) {
                Drawable d = null;
                if (a != null) {
                    for (int j = 0; j < DOCK_CELLS; j++) {
                        if (k.equals(oldKeys[j]) && oldDrawables[j] != null) {
                            d = oldDrawables[j];
                            oldDrawables[j] = null;
                            break;
                        }
                    }
                    if (d == null) d = icon(a);
                }
                int p = dp(8);
                cells[i].setTag(k);
                cells[i].setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                cells[i].setPadding(p, p, p, p);
                cells[i].setImageDrawable(d);
                cells[i].setContentDescription(a == null ? null : a.label);
            } else if (k != null) {
                oldDrawables[i] = null;
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
        pkgs.clear();
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            ActivityInfo a = r.activityInfo;
            if (a.packageName.equals(getPackageName())) continue;
            pkgs.add(a.packageName);
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

        if (clones && la != null && um != null) {
            UserHandle me = Process.myUserHandle();
            try {
                for (UserHandle u : Build.VERSION.SDK_INT >= 26 ? la.getProfiles() : um.getUserProfiles()) {
                    if (u.equals(me)) continue;
                    long sn = um.getSerialNumberForUser(u);
                    for (LauncherActivityInfo info : la.getActivityList(null, u)) {
                        ComponentName cn = info.getComponentName();
                        if (cn.getPackageName().equals(getPackageName())) continue;
                        App app = new App();
                        app.cn = cn;
                        app.user = u;
                        app.key = sn + "#" + cn.flattenToShortString();
                        app.label = labels.getString(app.key, null);
                        if (app.label == null) {
                            CharSequence raw = info.getLabel();
                            String base = raw == null ? cn.getClassName() : raw.toString();
                            String badged = pm.getUserBadgedLabel(base, u).toString();
                            app.label = badged.equals(base) ? base + " (Clone)" : badged;
                            if (ed == null) ed = labels.edit();
                            ed.putString(app.key, app.label);
                        }
                        app.n = counts.getInt(app.key, 0);
                        byKey.put(app.key, app);
                    }
                }
            } catch (Exception ignored) { }
        }
        if (ed != null) ed.apply();

        dock.clear();
        String saved = prefs.getString("dock", "");
        for (String k : saved.split(",")) {
            if (byKey.containsKey(k) && !hidden.contains(k) && !dock.contains(k) && dock.size() < DOCK_MAX) dock.add(k);
        }
        String joined = TextUtils.join(",", dock);
        if (!joined.equals(saved)) saveDock(); // uninstalled/hidden apps drop out
    }

    private void closePrevious(String keepPkg) {
        if (!singleApp || am == null) return;
        for (String p : pkgs) {
            if (!p.equals(keepPkg)) {
                try { am.killBackgroundProcesses(p); } catch (Exception ignored) { }
            }
        }
    }

    private void launch(App a) {
        if (a == null) return;
        try {
            closePrevious(a.cn.getPackageName());
            if (a.user != null && la != null) {
                la.startMainActivity(a.cn, a.user, null, null);
            } else {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(a.cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            }
            counts.edit().putInt(a.key, ++a.n).apply();
        } catch (Exception ignored) { }
    }

    // ---- icons ----

    private Drawable icon(App a) {
        try {
            if (a.user != null && la != null) {
                LauncherActivityInfo info = la.resolveActivity(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn),
                        a.user);
                return info != null ? info.getBadgedIcon(0) : null;
            }
            return pm.getActivityIcon(a.cn);
        } catch (Exception e) {
            return null;
        }
    }

    private Drawable listIcon(App a) {
        Drawable d = iconCache.get(a.key);
        if (d == null && (d = icon(a)) != null) {
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
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
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
