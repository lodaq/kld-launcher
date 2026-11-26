package app.kld.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.role.RoleManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF000000, TEXT = 0xFFFFFFFF, SEC = 0xFF8A8A8A, DIV = 0xFF222222;
    private static final int DOCK_MAX = 5, DOCK_CELLS = DOCK_MAX + 1, MAX_SHORTCUTS = 5;
    private static final int SCREEN_MAIN = 0, SCREEN_SETTINGS = 1, SCREEN_HIDDEN = 2, SCREEN_SORT_DOCK = 3,
            SCREEN_APP_MENU = 4, SCREEN_APP_LIMITS = 5, SCREEN_APP_LIMIT = 6, SCREEN_RENAME = 7;
    private static final int REQ_HOME = 1, REQ_OWNER = 2;
    private static final int[] LIMIT_MINS = {0, 1, 5, 10, 15, 30, 45, 60, 90, 120, 180, 240};
    private static final String GEAR_KEY = "_gear";

    static final class App { String key, label; ComponentName cn; UserHandle user; int n; }
    private static final class RowTag {
        String key, timeText;
        Drawable icon;
        TextView name, time;
        int nameColor = TEXT;
        float alpha = 1f;
    }

    private final HashMap<String, App> byKey = new HashMap<>();
    private final HashSet<String> pkgs = new HashSet<>();
    private final ArrayList<App> shown = new ArrayList<>();   // the App list (not docked, not hidden)
    private final ArrayList<String> dock = new ArrayList<>(); // docked keys, no gaps
    private final HashSet<String> hidden = new HashSet<>();
    private final HashMap<String, Integer> appLimits = new HashMap<>();
    private final HashMap<String, Long> usageMs = new HashMap<>();
    private final HashMap<String, String> rightLabels = new HashMap<>();
    private final HashSet<String> blockedKeys = new HashSet<>();
    private final ImageView[] cells = new ImageView[DOCK_CELLS];
    private final LruCache<String, Drawable> dockCache = new LruCache<>(DOCK_MAX + 4);
    private SharedPreferences counts, labels, customLabels, prefs, limits;
    private PackageManager pm;
    private ActivityManager am;
    private AppOpsManager aom;
    private UsageStatsManager usm;
    private LauncherApps la;
    private UserManager um;
    private Handler mainHandler;
    private LauncherApps.Callback pkgCb;
    private LruCache<String, Drawable> iconCache; // non-null only while Show icons is On
    private Drawable iconPlaceholder;
    private LinearLayout main;
    private ListView list;
    private BaseAdapter adapter, appLimitsAdapter;
    private boolean icons, showUsage, pendingUsagePerm, pendingLimitsPerm, clones, singleApp, dirty, reload,
            sortFromSettings, limitFromAppMenu, hasSyncedUsage;
    private App pendingLimitApp, limitApp, renameApp;
    private Runnable pendingOwnerAction;
    private String loadedLoc = "";
    private int screen, gearPos, listIconPx, dockIconPx;
    private volatile int iconGen, usageGen, usageWorkingGen = -1;
    private long lastUsageUptime, lastUsageDayStart;
    private float density;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        am = getSystemService(ActivityManager.class);
        aom = getSystemService(AppOpsManager.class);
        usm = getSystemService(UsageStatsManager.class);
        la = getSystemService(LauncherApps.class);
        um = getSystemService(UserManager.class);
        mainHandler = new Handler(Looper.getMainLooper());
        density = getResources().getDisplayMetrics().density;
        listIconPx = dp(32);
        dockIconPx = dp(40);
        iconPlaceholder = new ColorDrawable(0);
        iconPlaceholder.setBounds(0, 0, listIconPx, listIconPx);
        counts = getSharedPreferences("c", 0);
        labels = getSharedPreferences("l", 0);
        customLabels = getSharedPreferences("names", 0);
        prefs = getSharedPreferences("s", 0); // keys: "icons", "usage", "clones", "single", "dock", "gear", "hidden"
        limits = getSharedPreferences("lim", 0); // app key -> daily limit in minutes
        icons = prefs.getBoolean("icons", false);
        showUsage = prefs.getBoolean("usage", false);
        clones = prefs.getBoolean("clones", false);
        singleApp = prefs.getBoolean("single", false);
        gearPos = Math.max(0, Math.min(DOCK_MAX, prefs.getInt("gear", 0)));
        hidden.addAll(prefs.getStringSet("hidden", Collections.emptySet()));
        for (Map.Entry<String, ?> e : limits.getAll().entrySet()) {
            if (e.getValue() instanceof Integer) {
                int mins = (Integer) e.getValue();
                if (mins > 0) appLimits.put(e.getKey(), mins);
            }
        }
        main = buildMain();
        setContentView(main);
        if (la != null) {
            pkgCb = new LauncherApps.Callback() {
                public void onPackageRemoved(String p, UserHandle u) { invalidatePkg(p); }
                public void onPackageAdded(String p, UserHandle u) { invalidatePkg(p); }
                public void onPackageChanged(String p, UserHandle u) { invalidatePkg(p); }
                public void onPackagesAvailable(String[] ps, UserHandle u, boolean r) {
                    for (String p : ps) invalidatePkg(p);
                }
                public void onPackagesUnavailable(String[] ps, UserHandle u, boolean r) {
                    for (String p : ps) invalidatePkg(p);
                }
            };
            try { la.registerCallback(pkgCb); } catch (Exception ignored) { pkgCb = null; }
        }
        if (b == null) askDefaultHome();
    }

    @Override protected void onStart() {
        super.onStart();
        if (screen == SCREEN_MAIN || byKey.isEmpty()) syncIfNeeded();
    }

    @Override protected void onResume() {
        super.onResume();
        if (screen == SCREEN_SETTINGS && pendingUsagePerm) {
            pendingUsagePerm = false;
            if (hasUsagePermission()) {
                showUsage = true;
                lastUsageUptime = 0;
                prefs.edit().putBoolean("usage", true).apply();
                showSettings();
            }
        } else if (pendingLimitsPerm) {
            pendingLimitsPerm = false;
            App target = pendingLimitApp;
            pendingLimitApp = null;
            if (hasUsagePermission()) {
                lastUsageUptime = 0;
                syncUsage();
                if (target != null) showAppLimit(target, limitFromAppMenu);
                else showAppLimits();
            }
        } else if (screen == SCREEN_MAIN) {
            syncIfNeeded();
        } else if (screen == SCREEN_APP_LIMITS) {
            syncUsage();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && screen == SCREEN_MAIN && (reload || dirty)) syncIfNeeded();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            ++iconGen;
            if (iconCache != null) {
                iconCache = null;
                for (int i = 0, c = list.getChildCount(); i < c; i++) {
                    RowTag rt = (RowTag) list.getChildAt(i).getTag();
                    if (rt != null) {
                        rt.icon = null;
                        rt.name.setCompoundDrawablesRelative(null, null, null, null);
                    }
                }
            }
            if (level >= TRIM_MEMORY_COMPLETE) {
                ++usageGen;
                clearUsageState();
                dockCache.evictAll();
                for (ImageView cell : cells) {
                    if (!GEAR_KEY.equals(cell.getTag())) {
                        cell.setImageDrawable(null);
                        cell.setTag(null);
                    }
                }
                dirty = true;
            }
        }
    }

    @Override protected void onDestroy() {
        ++iconGen;
        ++usageGen;
        if (la != null && pkgCb != null) {
            try { la.unregisterCallback(pkgCb); } catch (Exception ignored) { }
        }
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        iconCache = null;
        dockCache.evictAll();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (screen != SCREEN_MAIN) showMain();
        list.setSelection(0);
    }

    @Override public void onBackPressed() {
        if (screen == SCREEN_HIDDEN || screen == SCREEN_APP_LIMITS) showSettings();
        else if (screen == SCREEN_RENAME) {
            hideKeyboard();
            if (renameApp != null) showAppMenu(renameApp);
            else showMain();
        } else if (screen == SCREEN_APP_LIMIT) {
            if (limitFromAppMenu && limitApp != null) showAppMenu(limitApp);
            else showAppLimits();
        } else if (screen == SCREEN_SORT_DOCK) {
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
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OWNER) {
            Runnable action = pendingOwnerAction;
            pendingOwnerAction = null;
            if (resultCode == RESULT_OK && action != null) action.run();
        }
    }

    // ---- screens ----

    private LinearLayout buildMain() {
        final int pad = dp(16);
        LinearLayout m = column();
        list = plainList();
        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                LinearLayout r = (LinearLayout) v;
                RowTag rt;
                if (r == null) {
                    r = new LinearLayout(MainActivity.this);
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setPadding(pad, pad, pad, pad);

                    TextView name = new TextView(MainActivity.this);
                    name.setTextSize(20);
                    name.setTextColor(TEXT);
                    name.setSingleLine(true);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    name.setCompoundDrawablePadding(pad);
                    r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

                    TextView time = new TextView(MainActivity.this);
                    time.setTextSize(14);
                    time.setTextColor(SEC);
                    time.setSingleLine(true);
                    time.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                    time.setPaddingRelative(pad, 0, 0, 0);
                    time.setVisibility(View.GONE);
                    r.addView(time);

                    rt = new RowTag();
                    rt.name = name;
                    rt.time = time;
                    r.setTag(rt);
                } else {
                    rt = (RowTag) r.getTag();
                }
                App a = shown.get(i);
                rt.key = a.key;
                rt.name.setText(a.label);
                boolean blocked = blockedKeys.contains(a.key);
                int targetColor = blocked ? SEC : TEXT;
                if (rt.nameColor != targetColor) {
                    rt.nameColor = targetColor;
                    rt.name.setTextColor(targetColor);
                }
                float targetAlpha = blocked ? 0.5f : 1f;
                if (rt.alpha != targetAlpha) {
                    rt.alpha = targetAlpha;
                    r.setAlpha(targetAlpha);
                }
                Drawable target = null;
                if (iconCache != null) {
                    Drawable d = iconCache.get(a.key);
                    target = d != null ? d : iconPlaceholder;
                }
                if (rt.icon != target) {
                    rt.icon = target;
                    rt.name.setCompoundDrawablesRelative(target, null, null, null);
                }
                String tt = rightLabels.get(a.key);
                int vis = tt != null ? View.VISIBLE : View.GONE;
                if (rt.time.getVisibility() != vis) rt.time.setVisibility(vis);
                if (tt != null && !tt.equals(rt.timeText)) {
                    rt.timeText = tt;
                    rt.time.setText(tt);
                }
                return r;
            }
        };
        list.setAdapter(adapter);
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
        appLimitsAdapter = null;
        limitApp = null;
        renameApp = null;
        pendingOwnerAction = null;
        setContentView(main);
        if (reload) {
            reload = dirty = false;
            load();
            bind();
        } else if (dirty) {
            dirty = false;
            bind();
        } else if (icons != (iconCache != null)) {
            if (icons) {
                iconCache = new LruCache<>(Math.max(128, byKey.size() + 32));
                adapter.notifyDataSetChanged();
                preloadIcons();
            } else {
                ++iconGen;
                iconCache = null;
                adapter.notifyDataSetChanged();
            }
        }
        syncUsage();
    }

    private void showSettings() {
        screen = SCREEN_SETTINGS;
        appLimitsAdapter = null;
        limitApp = null;
        LinearLayout s = column();
        s.addView(row("Settings", "", null));
        s.addView(rule());
        s.addView(row("Show icons", icons ? "On" : "Off", v -> {
            icons = !icons;
            prefs.edit().putBoolean("icons", icons).apply();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(icons ? "On" : "Off");
        }));
        s.addView(rule());
        s.addView(row("Show time today", (showUsage && hasUsagePermission()) ? "On" : "Off", v -> {
            if (!hasUsagePermission()) {
                pendingUsagePerm = true;
                openUsageAccessSettings();
                return;
            }
            showUsage = !showUsage;
            pendingUsagePerm = false;
            clearUsageState();
            dirty = true;
            ++usageGen;
            prefs.edit().putBoolean("usage", showUsage).apply();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(showUsage ? "On" : "Off");
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
        s.addView(row("App limits", appLimitsSettingsValue(), v -> verifyOwner(() -> {
            if (!hasUsagePermission()) {
                pendingLimitsPerm = true;
                pendingLimitApp = null;
                openUsageAccessSettings();
            } else {
                showAppLimits();
            }
        })));
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
        reloadIfNeeded();
        final ArrayList<App> all = new ArrayList<>(byKey.values());
        Collections.sort(all, (x, y) -> {
            boolean hx = hidden.contains(x.key), hy = hidden.contains(y.key);
            return hx != hy ? (hx ? -1 : 1) : x.label.compareToIgnoreCase(y.label);
        });
        LinearLayout s = column();
        s.addView(row("Hidden apps", "", null));
        s.addView(rule());
        ListView hl = plainList();
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
        reloadIfNeeded();
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

    private void showAppLimits() {
        screen = SCREEN_APP_LIMITS;
        limitApp = null;
        reloadIfNeeded();
        final ArrayList<App> all = new ArrayList<>();
        for (App a : byKey.values()) {
            if (a.user == null) all.add(a);
        }
        Collections.sort(all, (x, y) -> {
            boolean lx = appLimits.containsKey(x.key), ly = appLimits.containsKey(y.key);
            return lx != ly ? (lx ? -1 : 1) : x.label.compareToIgnoreCase(y.label);
        });
        final int pad = dp(16);
        LinearLayout s = column();
        s.addView(row("App limits", "", null));
        s.addView(rule());
        ListView lv = plainList();
        appLimitsAdapter = new BaseAdapter() {
            public int getCount() { return all.size(); }
            public Object getItem(int i) { return all.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                LinearLayout r = v != null ? (LinearLayout) v : (LinearLayout) row("", "", null);
                App a = all.get(i);
                TextView name = (TextView) r.getChildAt(0);
                TextView val = (TextView) r.getChildAt(1);
                name.setText(a.label);
                String txt = appLimitRowValue(a);
                val.setText(txt);
                val.setPaddingRelative(txt.isEmpty() ? 0 : pad, 0, 0, 0);
                return r;
            }
        };
        lv.setAdapter(appLimitsAdapter);
        lv.setOnItemClickListener((p, v, i, id) -> showAppLimit(all.get(i), false));
        s.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(s);
        syncUsage();
    }

    private void showAppLimit(final App a, final boolean fromAppMenu) {
        screen = SCREEN_APP_LIMIT;
        limitApp = a;
        limitFromAppMenu = fromAppMenu;
        appLimitsAdapter = null;
        int cur = limitMinutes(a);
        String status = "";
        if (blockedKeys.contains(a.key)) status = "Blocked";
        else if (hasUsagePermission()) {
            long used = usageMsVal(a.cn.getPackageName());
            if (used >= 1000L) status = "Used " + formatDuration(used);
        }
        LinearLayout s = column();
        s.addView(row(a.label, status, null));
        s.addView(rule());

        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        for (final int mins : LIMIT_MINS) {
            String label = mins == 0 ? "Off" : formatMinutes(mins);
            String val = cur == mins ? "Current" : "";
            items.addView(row(label, val, v -> {
                setAppLimit(a, mins);
                if (fromAppMenu) showAppMenu(a);
                else showAppLimits();
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

    private void showAppMenu(final App a) {
        screen = SCREEN_APP_MENU;
        appLimitsAdapter = null;
        limitApp = null;
        renameApp = null;
        LinearLayout s = column();
        s.addView(row(a.label, "", null));
        s.addView(rule());

        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);

        if (Build.VERSION.SDK_INT >= 25 && la != null && !isBlocked(a)) {
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

        if (a.user == null) {
            items.addView(row("Daily limit", appLimitMenuValue(a), v -> verifyOwner(() -> {
                if (!hasUsagePermission()) {
                    pendingLimitsPerm = true;
                    pendingLimitApp = a;
                    limitFromAppMenu = true;
                    openUsageAccessSettings();
                } else {
                    showAppLimit(a, true);
                }
            })));
            items.addView(rule());
        }

        items.addView(row("Rename", customLabels.contains(a.key) ? "Custom" : "", v -> showRename(a)));
        items.addView(rule());

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

    private void showRename(final App a) {
        screen = SCREEN_RENAME;
        renameApp = a;
        int pad = dp(16);
        LinearLayout s = column();
        s.addView(row("Rename", a.label, null));
        s.addView(rule());

        final EditText et = new EditText(this);
        et.setBackground(null);
        et.setTextColor(TEXT);
        et.setHintTextColor(SEC);
        et.setTextSize(20);
        et.setSingleLine(true);
        et.setMinimumHeight(dp(48));
        et.setPadding(pad, pad, pad, pad);
        String sys = labels.getString(a.key, null);
        et.setHint(sys != null ? sys : a.label);
        et.setText(a.label);
        et.setSelection(et.getText().length());
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveRename(a, et.getText().toString());
                return true;
            }
            return false;
        });
        s.addView(et, new LinearLayout.LayoutParams(-1, -2));
        s.addView(rule());

        s.addView(row("Save", "", v -> saveRename(a, et.getText().toString())));
        s.addView(rule());
        if (customLabels.contains(a.key)) {
            s.addView(row("Reset to default", "", v -> resetRename(a)));
            s.addView(rule());
        }
        setContentView(s);
        et.requestFocus();
        et.post(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void saveRename(App a, String raw) {
        hideKeyboard();
        String next = raw == null ? "" : raw.trim();
        if (next.isEmpty()) return;
        customLabels.edit().putString(a.key, next).apply();
        a.label = next;
        dirty = true;
        showAppMenu(a);
    }

    private void resetRename(App a) {
        hideKeyboard();
        customLabels.edit().remove(a.key).apply();
        String sys = labels.getString(a.key, null);
        if (sys != null) a.label = sys;
        else reload = true;
        dirty = true;
        showAppMenu(a);
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
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
                    try { la.startShortcut(si, null, null); } catch (Exception ignored) { }
                    closePrevious(a.cn.getPackageName());
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
        ++iconGen;
        if (icons) {
            int cap = Math.max(128, byKey.size() + 32);
            if (iconCache == null) iconCache = new LruCache<>(cap);
            else if (iconCache.maxSize() < cap) iconCache.resize(cap);
        } else {
            iconCache = null;
        }
        refreshLimitState();
        adapter.notifyDataSetChanged();
        bindDock();
        preloadIcons();
    }

    private void bindDock() {
        int p = dp(8);
        for (int i = 0; i < DOCK_CELLS; i++) {
            if (i == gearPos) {
                if (!GEAR_KEY.equals(cells[i].getTag())) {
                    cells[i].setTag(GEAR_KEY);
                    cells[i].setScaleType(ImageView.ScaleType.CENTER);
                    cells[i].setPadding(0, 0, 0, 0);
                    cells[i].setImageResource(R.drawable.ic_settings);
                    cells[i].setContentDescription("Settings");
                }
                if (cells[i].getImageAlpha() != 255) cells[i].setImageAlpha(255);
                continue;
            }
            int slot = slotFor(i);
            App a = slot < dock.size() ? byKey.get(dock.get(slot)) : null;
            String k = a == null ? null : a.key;
            Drawable d = k == null ? null : dockCache.get(k);
            boolean blocked = a != null && blockedKeys.contains(k);
            String desc = a == null ? null : (blocked ? a.label + ", blocked" : a.label);
            if (!Objects.equals(k, cells[i].getTag()) || GEAR_KEY.equals(cells[i].getTag())
                    || (d != null && cells[i].getDrawable() != d)
                    || !Objects.equals(desc, cells[i].getContentDescription())) {
                cells[i].setTag(k);
                cells[i].setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                cells[i].setPadding(p, p, p, p);
                cells[i].setImageDrawable(d);
                cells[i].setContentDescription(desc);
            }
            int targetAlpha = blocked ? 90 : 255;
            if (cells[i].getImageAlpha() != targetAlpha) cells[i].setImageAlpha(targetAlpha);
        }
    }

    private void load() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        // Cached names are only valid for the language they were loaded in.
        String loc = getResources().getConfiguration().getLocales().get(0).toLanguageTag();
        loadedLoc = loc;
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
            String sysLabel = labels.getString(app.key, null);
            if (sysLabel == null) {
                sysLabel = r.loadLabel(pm).toString();
                if (ed == null) ed = labels.edit();
                ed.putString(app.key, sysLabel);
            }
            String custom = customLabels.getString(app.key, null);
            app.label = (custom != null && !custom.isEmpty()) ? custom : sysLabel;
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
                        String sysLabel = labels.getString(app.key, null);
                        if (sysLabel == null) {
                            CharSequence raw = info.getLabel();
                            String base = raw == null ? cn.getClassName() : raw.toString();
                            String badged = pm.getUserBadgedLabel(base, u).toString();
                            sysLabel = badged.equals(base) ? base + " (Clone)" : badged;
                            if (ed == null) ed = labels.edit();
                            ed.putString(app.key, sysLabel);
                        }
                        String custom = customLabels.getString(app.key, null);
                        app.label = (custom != null && !custom.isEmpty()) ? custom : sysLabel;
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

    private void syncIfNeeded() {
        String loc = getResources().getConfiguration().getLocales().get(0).toLanguageTag();
        if (!loc.equals(loadedLoc)) reload = true;
        if (byKey.isEmpty() || reload) {
            reload = dirty = false;
            load();
            bind();
        } else if (dirty) {
            dirty = false;
            bind();
        } else if (icons && iconCache == null) {
            ++iconGen;
            iconCache = new LruCache<>(Math.max(128, byKey.size() + 32));
            adapter.notifyDataSetChanged();
            preloadIcons();
        } else {
            preloadIcons();
        }
        syncUsage();
    }

    private void invalidatePkg(String pkg) {
        if (pkg == null) return;
        SharedPreferences.Editor ed = null;
        for (App a : byKey.values()) {
            if (pkg.equals(a.cn.getPackageName())) {
                if (iconCache != null) iconCache.remove(a.key);
                dockCache.remove(a.key);
                if (labels.contains(a.key)) {
                    if (ed == null) ed = labels.edit();
                    ed.remove(a.key);
                }
            }
        }
        if (ed != null) ed.apply();
        reload = true;
    }

    private void closePrevious(String keepPkg) {
        if (!singleApp || am == null) return;
        final ArrayList<String> targets = new ArrayList<>(pkgs);
        new Thread(() -> {
            for (String p : targets) {
                if (!p.equals(keepPkg)) {
                    try { am.killBackgroundProcesses(p); } catch (Exception ignored) { }
                }
            }
        }, "kld-trim").start();
    }

    private void launch(App a) {
        if (a == null || isBlocked(a)) return;
        try {
            if (a.user != null && la != null) {
                la.startMainActivity(a.cn, a.user, null, null);
            } else {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(a.cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            }
            counts.edit().putInt(a.key, ++a.n).apply();
            if (!dock.contains(a.key)) dirty = true;
            if (showUsage || !appLimits.isEmpty()) lastUsageUptime = 0;
            closePrevious(a.cn.getPackageName());
        } catch (Exception ignored) { }
    }

    // ---- icons ----

    private volatile int iconWorkingGen = -1;

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

    private Drawable rasterize(Drawable d, int px) {
        if (d == null) return null;
        try {
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            int w = px, h = px;
            if (iw > 0 && ih > 0 && iw != ih) {
                if (iw > ih) h = Math.max(1, px * ih / iw);
                else w = Math.max(1, px * iw / ih);
            }
            int dx = (px - w) / 2, dy = (px - h) / 2;
            Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(dx, dy, dx + w, dy + h);
            d.draw(c);
            BitmapDrawable bd = new BitmapDrawable(getResources(), bmp);
            bd.setBounds(0, 0, px, px);
            return bd;
        } catch (Throwable ignored) {
            return d;
        }
    }

    private void preloadIcons() {
        final int gen = iconGen;
        if (iconWorkingGen == gen) return;

        final ArrayList<App> dockTodo = new ArrayList<>(dock.size());
        for (String k : dock) {
            App a = byKey.get(k);
            if (a != null && dockCache.get(k) == null) dockTodo.add(a);
        }

        final LruCache<String, Drawable> lCache = iconCache;
        final ArrayList<App> listTodo = new ArrayList<>(lCache == null ? 0 : shown.size());
        if (lCache != null && !shown.isEmpty()) {
            int n = shown.size();
            int first = list.getFirstVisiblePosition();
            int last = list.getLastVisiblePosition();
            if (first < 0 || last < first) {
                first = 0;
                last = Math.min(n - 1, 20);
            } else {
                first = Math.max(0, first);
                last = Math.min(n - 1, last + 4);
            }
            for (int i = first; i <= last; i++) {
                App a = shown.get(i);
                if (lCache.get(a.key) == null) listTodo.add(a);
            }
            for (int i = 0; i < n; i++) {
                if (i >= first && i <= last) continue;
                App a = shown.get(i);
                if (lCache.get(a.key) == null) listTodo.add(a);
            }
        }

        if (dockTodo.isEmpty() && listTodo.isEmpty()) return;

        iconWorkingGen = gen;
        final int listPx = listIconPx, dockPx = dockIconPx;
        Thread w = new Thread(() -> {
            try {
                for (App a : dockTodo) {
                    if (iconGen != gen) return;
                    if (dockCache.get(a.key) != null) continue;
                    Drawable d = rasterize(icon(a), dockPx);
                    if (d != null && iconGen == gen) dockCache.put(a.key, d);
                }
                if (!dockTodo.isEmpty() && iconGen == gen) {
                    mainHandler.post(() -> {
                        if (iconGen == gen) bindDock();
                    });
                }
                if (lCache == null || listTodo.isEmpty()) return;
                boolean uiPending = false;
                long lastPost = 0;
                for (int i = 0, total = listTodo.size(); i < total; i++) {
                    if (iconGen != gen || iconCache != lCache) return;
                    App a = listTodo.get(i);
                    if (lCache.get(a.key) != null) continue;
                    Drawable d = rasterize(icon(a), listPx);
                    if (d != null && iconGen == gen && iconCache == lCache) {
                        lCache.put(a.key, d);
                        uiPending = true;
                    }
                    long now = SystemClock.uptimeMillis();
                    if (uiPending && (i == total - 1 || now - lastPost >= 24)) {
                        uiPending = false;
                        lastPost = now;
                        mainHandler.post(() -> {
                            if (iconGen == gen && iconCache == lCache) refreshVisibleListIcons();
                        });
                    }
                }
            } finally {
                if (iconWorkingGen == gen) iconWorkingGen = -1;
            }
        }, "kld-icons");
        w.setPriority(Thread.NORM_PRIORITY - 1);
        w.start();
    }

    private void refreshVisibleListIcons() {
        if (iconCache == null) return;
        for (int i = 0, c = list.getChildCount(); i < c; i++) {
            RowTag rt = (RowTag) list.getChildAt(i).getTag();
            if (rt != null && rt.icon == iconPlaceholder) {
                Drawable d = iconCache.get(rt.key);
                if (d != null) {
                    rt.icon = d;
                    rt.name.setCompoundDrawablesRelative(d, null, null, null);
                }
            }
        }
    }

    // ---- usage & limits ----

    private void verifyOwner(final Runnable onVerified) {
        final KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isDeviceSecure()) {
            onVerified.run();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                        .setTitle("App limits")
                        .setSubtitle("Verify you are the owner");
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else {
                    b.setDeviceCredentialAllowed(true);
                }
                b.build().authenticate(new CancellationSignal(), getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                onVerified.run();
                            }
                            @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
                                        || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
                                        || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                                    confirmDeviceCredential(km, onVerified);
                                }
                            }
                        });
                return;
            } catch (Exception ignored) { }
        }
        confirmDeviceCredential(km, onVerified);
    }

    @SuppressWarnings("deprecation")
    private void confirmDeviceCredential(KeyguardManager km, Runnable onVerified) {
        try {
            Intent intent = km.createConfirmDeviceCredentialIntent("App limits", "Verify you are the owner");
            if (intent != null) {
                pendingOwnerAction = onVerified;
                startActivityForResult(intent, REQ_OWNER);
                return;
            }
        } catch (Exception ignored) { }
        onVerified.run();
    }

    private int limitMinutes(App a) {
        Integer m = a == null || a.user != null ? null : appLimits.get(a.key);
        return m == null ? 0 : m;
    }

    private long usageMsVal(String pkg) {
        Long ms = usageMs.get(pkg);
        return ms == null ? 0L : ms;
    }

    private boolean isBlocked(App a) {
        if (a == null || a.user != null || !blockedKeys.contains(a.key)) return false;
        return lastUsageDayStart == 0 || lastUsageDayStart == dayStartMs();
    }

    private void setAppLimit(App a, int mins) {
        if (mins <= 0) {
            appLimits.remove(a.key);
            limits.edit().remove(a.key).apply();
        } else {
            appLimits.put(a.key, mins);
            limits.edit().putInt(a.key, mins).apply();
        }
        refreshLimitState(hasUsagePermission());
        dirty = true;
        if (mins > 0 && lastUsageUptime == 0) syncUsage();
    }

    private String appLimitsSettingsValue() {
        int active = 0;
        for (App a : byKey.values()) {
            if (a.user == null && appLimits.containsKey(a.key)) active++;
        }
        if (active > 0 && !hasUsagePermission()) return "No access";
        return String.valueOf(active);
    }

    private String appLimitRowValue(App a) {
        int mins = limitMinutes(a);
        if (mins <= 0) return "";
        if (blockedKeys.contains(a.key)) return "Blocked (" + formatMinutes(mins) + ")";
        String r = rightLabels.get(a.key);
        return r != null ? r : formatMinutes(mins);
    }

    private String appLimitMenuValue(App a) {
        int mins = limitMinutes(a);
        if (mins <= 0) return "Off";
        if (blockedKeys.contains(a.key)) return "Blocked (" + formatMinutes(mins) + ")";
        return formatMinutes(mins);
    }

    private void refreshLimitState() {
        refreshLimitState(hasUsagePermission());
    }

    private void refreshLimitState(boolean perm) {
        blockedKeys.clear();
        rightLabels.clear();
        for (App a : byKey.values()) {
            if (a.user != null) continue;
            String pkg = a.cn.getPackageName();
            int mins = limitMinutes(a);
            if (mins > 0) {
                if (perm) {
                    long used = usageMsVal(pkg);
                    if (used >= mins * 60_000L) {
                        blockedKeys.add(a.key);
                        rightLabels.put(a.key, "Blocked");
                    } else {
                        String u = formatDuration(used);
                        rightLabels.put(a.key, (u != null ? u : "0m") + " / " + formatMinutes(mins));
                    }
                } else {
                    rightLabels.put(a.key, "No access");
                }
            } else if (showUsage && perm) {
                String u = formatDuration(usageMsVal(pkg));
                if (u != null) rightLabels.put(a.key, u);
            }
        }
    }

    private void clearUsageState() {
        lastUsageUptime = 0;
        lastUsageDayStart = 0;
        hasSyncedUsage = false;
        usageMs.clear();
        rightLabels.clear();
        blockedKeys.clear();
    }

    private void notifyUsageViews() {
        adapter.notifyDataSetChanged();
        bindDock();
        if (appLimitsAdapter != null) appLimitsAdapter.notifyDataSetChanged();
    }

    private static long dayStartMs() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private boolean hasUsagePermission() {
        if (aom == null) return false;
        try {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                    : aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT
                    && checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED);
        } catch (Exception e) {
            return false;
        }
    }

    private void openUsageAccessSettings() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
                return;
            } catch (Exception ignored) { }
        }
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception ignored) { }
    }

    private void syncUsage() {
        long today = dayStartMs();
        if (lastUsageDayStart != 0 && lastUsageDayStart != today) {
            clearUsageState();
            refreshLimitState();
            notifyUsageViews();
        }
        boolean perm = hasUsagePermission();
        if ((!showUsage && appLimits.isEmpty()) || !perm) {
            ++usageGen;
            if (!usageMs.isEmpty() || !rightLabels.isEmpty() || !blockedKeys.isEmpty()
                    || lastUsageUptime != 0 || lastUsageDayStart != 0) {
                clearUsageState();
                refreshLimitState(perm);
                notifyUsageViews();
            }
            return;
        }
        if (usm == null || usageWorkingGen == usageGen) return;
        if (lastUsageUptime != 0 && SystemClock.uptimeMillis() - lastUsageUptime < 1000L) return;
        final int gen = ++usageGen;
        final long queryDayStart = today;
        usageWorkingGen = gen;
        Thread w = new Thread(() -> {
            HashMap<String, Long> next = null;
            try {
                next = queryTodayUsage(queryDayStart);
            } finally {
                final HashMap<String, Long> result = next;
                mainHandler.post(() -> {
                    if (usageWorkingGen == gen) usageWorkingGen = -1;
                    if (gen != usageGen || (!showUsage && appLimits.isEmpty())) return;
                    lastUsageUptime = SystemClock.uptimeMillis();
                    lastUsageDayStart = queryDayStart;
                    if (result != null && (!hasSyncedUsage || !result.equals(usageMs))) {
                        hasSyncedUsage = true;
                        usageMs.clear();
                        usageMs.putAll(result);
                        refreshLimitState(true);
                        notifyUsageViews();
                    }
                });
            }
        }, "kld-usage");
        w.setPriority(Thread.NORM_PRIORITY - 1);
        w.start();
    }

    private HashMap<String, Long> queryTodayUsage(long dayStart) {
        HashMap<String, Long> out = new HashMap<>();
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        UsageEvents events;
        try {
            events = usm.queryEvents(Math.max(0L, dayStart - 12L * 3600_000L), now);
        } catch (Exception e) {
            return out;
        }
        if (events == null) return out;

        // Per-package state: [0] = total ms today, [1] = active start ts, [2] = active activity count.
        HashMap<String, long[]> state = new HashMap<>();
        UsageEvents.Event ev = new UsageEvents.Event();
        String selfPkg = getPackageName();
        int active = 0;
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(ev)) break;
            int type = ev.getEventType();
            long ts = ev.getTimeStamp();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                String pkg = ev.getPackageName();
                if (pkg == null || pkg.equals(selfPkg)) continue;
                long[] s = state.get(pkg);
                if (s == null) {
                    s = new long[3];
                    state.put(pkg, s);
                }
                if (s[2]++ == 0) {
                    s[1] = ts;
                    active++;
                }
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                String pkg = ev.getPackageName();
                if (pkg == null) continue;
                long[] s = state.get(pkg);
                if (s != null && s[2] > 0 && --s[2] == 0) {
                    long start = Math.max(s[1], dayStart);
                    long end = Math.min(ts, now);
                    if (end > start) s[0] += end - start;
                    active--;
                }
            } else if (active > 0 && Build.VERSION.SDK_INT >= 29 && (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                    || type == UsageEvents.Event.KEYGUARD_SHOWN
                    || type == UsageEvents.Event.DEVICE_SHUTDOWN)) {
                for (long[] s : state.values()) {
                    if (s[2] > 0) {
                        long start = Math.max(s[1], dayStart);
                        long end = Math.min(ts, now);
                        if (end > start) s[0] += end - start;
                        s[2] = 0;
                    }
                }
                active = 0;
            } else if (active > 0 && Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.DEVICE_STARTUP) {
                for (long[] s : state.values()) s[2] = 0;
                active = 0;
            }
        }
        for (Map.Entry<String, long[]> e : state.entrySet()) {
            if (e.getValue()[0] > 0L) out.put(e.getKey(), e.getValue()[0]);
        }
        return out;
    }

    private static String formatDuration(long ms) {
        if (ms < 1000L) return null;
        long mins = ms / 60_000L;
        return mins == 0L ? "<1m" : formatMinutes(mins);
    }

    private static String formatMinutes(long mins) {
        long hours = mins / 60L, rem = mins % 60L;
        if (hours == 0L) return mins + "m";
        return rem == 0L ? hours + "h" : hours + "h " + rem + "m";
    }

    // ---- small view helpers ----

    private void reloadIfNeeded() {
        if (reload) {
            reload = false;
            load();
            bind();
        }
    }

    private ListView plainList() {
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setSelector(android.R.color.transparent);
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return lv;
    }

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
