package local.coocaa.litehome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native SDK21+ UI. No OEM HOME.CC registration, permissions, receivers or services. */
public final class LiteHomeActivity extends Activity {
    private static final String[] HIDDEN = {
        "com.tianci.movieplatform", "com.tianci.setting", "com.tianci.localmedia",
        "com.skyworth.miracast", "com.coocaa.mall", "com.tianci.eduplatform",
        "com.tianci.weather", "com.tianci.autoinstaller", "com.tianci.appstore",
        "com.coocaa.tvmanager", "com.tianci.user", "com.skyworth.smarthome_tv",
        "com.coocaa.sky.ccapi", "com.coocaa.skyoscts", "com.coocaa.homeshell",
        "com.skyworth.simplehomepage"
    };
    private static final String[] CORE = {
        "local.coocaa.litehome", "com.tianci.system", "com.tianci.ipc", "com.tianci.tv",
        "com.tianci.setting", "com.tianci.localmedia", "com.tianci.movieplatform",
        "com.mstar.tv.service", "com.mstar.android.pppoe", "com.android.providers.media",
        "com.android.settings", "com.android.packageinstaller", "com.android.systemui"
    };
    private static final String MANAGE = "desktop:manage";
    private static final int UNINSTALL = 40;
    private SharedPreferences preferences;
    private ScrollView scroll;
    private AlertDialog activeDialog;
    private final List<Tile> shortcuts = new ArrayList<Tile>();
    private final List<Tile> applications = new ArrayList<Tile>();
    private final List<Tile> visible = new ArrayList<Tile>();
    private final Map<String, View> buttons = new LinkedHashMap<String, View>();
    private String selectedKey;
    private int selectedPosition;
    private int id;

    private static final class Tile {
        final String key, label, packageName;
        final Intent intent;
        final boolean application, fixed;
        Tile(String key, String label, String packageName, Intent intent, boolean application, boolean fixed) {
            this.key = key; this.label = label; this.packageName = packageName;
            this.intent = intent; this.application = application; this.fixed = fixed;
        }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("desktop", MODE_PRIVATE);
        selectedKey = saved == null ? preferences.getString("focus", "shortcut:source") : saved.getString("focus", "shortcut:source");
        selectedPosition = saved == null ? preferences.getInt("focusPosition", 0) : saved.getInt("focusPosition", 0);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (activeDialog != null) activeDialog.dismiss();
        // onResume rebuilds after returning from another application. If already
        // visible, refresh for app changes while retaining the selected tile.
        if (hasWindowFocus()) render();
    }

    @Override protected void onPause() {
        preferences.edit().putString("focus", selectedKey).putInt("focusPosition", selectedPosition).apply();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("focus", selectedKey); state.putInt("focusPosition", selectedPosition);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        // This Activity is the visible desktop. Finishing it would expose an
        // older OEM task whose Home handler may keep the user there.
        // Dialogs keep their normal Back-to-dismiss behavior.
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0 && activeDialog == null) {
                Tile tile = findTile(selectedKey);
                if (MANAGE.equals(selectedKey) || tile == null) showDesktopManager();
                else showTileMenu(tile);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) return true;
        return super.onKeyUp(keyCode, event);
    }

    private boolean listed(String value, String[] list) {
        for (String name : list) if (name.equals(value)) return true;
        return false;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Intent component(String pkg, String cls) {
        return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls));
    }

    private void shortcut(String key, String label, String pkg, Intent intent, boolean fixed) {
        shortcuts.add(new Tile("shortcut:" + key, label, pkg, intent, false, fixed));
    }

    private Set<String> hiddenKeys() {
        // SharedPreferences owns the returned set; always edit a defensive copy.
        return new HashSet<String>(preferences.getStringSet("hidden", Collections.<String>emptySet()));
    }

    private void order(final List<Tile> tiles, String key) {
        final List<String> saved = Arrays.asList(preferences.getString(key, "").split(","));
        Collections.sort(tiles, new Comparator<Tile>() {
            @Override public int compare(Tile a, Tile b) {
                int ai = saved.indexOf(a.key), bi = saved.indexOf(b.key);
                if (ai < 0) ai = Integer.MAX_VALUE;
                if (bi < 0) bi = Integer.MAX_VALUE;
                // Stable sort keeps the default shortcut order and alphabetical
                // app order when neither entry occurs in saved preferences.
                return ai < bi ? -1 : ai == bi ? 0 : 1;
            }
        });
    }

    private void loadTiles() {
        shortcuts.clear(); applications.clear();
        shortcut("source", "输入源", "com.tianci.tv", new Intent("coocaa.intent.action.TV").setPackage("com.tianci.tv"), true);
        shortcut("settings", "系统设置", "com.tianci.setting", new Intent(Settings.ACTION_SETTINGS).setPackage("com.tianci.setting"), true);
        shortcut("media", "本地播放", "com.tianci.localmedia", component("com.tianci.localmedia", "com.tianci.localmedia.MainActivity"), false);
        shortcut("cast", "无线投屏", "com.skyworth.miracast", component("com.skyworth.miracast", "com.skyworth.skymiracast.SkyMiracastActivity"), false);
        // Original Home stays available to external recovery tools only.
        shortcut("network", "网络设置", "com.tianci.setting", new Intent(Settings.ACTION_WIFI_SETTINGS).setPackage("com.tianci.setting"), false);
        final PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> available = new ArrayList<ResolveInfo>(pm.queryIntentActivities(query, 0));
        Collections.sort(available, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                int byLabel = a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
                return byLabel != 0 ? byLabel : a.activityInfo.name.compareTo(b.activityInfo.name);
            }
        });
        Set<String> included = new HashSet<String>();
        for (ResolveInfo app : available) {
            String pkg = app.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || listed(pkg, HIDDEN) || !included.add(pkg)) continue;
            applications.add(new Tile("app:" + pkg, app.loadLabel(pm).toString(), pkg,
                component(pkg, app.activityInfo.name), true, false));
        }
        order(shortcuts, "order.shortcuts");
        order(applications, "order.apps");
    }

    private void render() {
        final String wanted = selectedKey;
        final int previousPosition = selectedPosition;
        final int previousY = scroll == null ? 0 : scroll.getScrollY();
        loadTiles();
        final Set<String> hidden = hiddenKeys();
        visible.clear(); buttons.clear(); id = 100;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(20, 24, 30));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(32), dp(20), dp(32), dp(24));
        scroll.addView(page);
        addHeading(page, "极简桌面", 28);
        addHeading(page, "方向键选择 · 确定键打开 · 菜单键管理", 16);
        LinearLayout shortcutGrid = grid(page);
        for (Tile tile : shortcuts) if (tile.fixed || !hidden.contains(tile.key)) addTile(shortcutGrid, tile);
        addHeading(page, "应用", 21);
        LinearLayout appGrid = grid(page);
        for (Tile tile : applications) if (!hidden.contains(tile.key)) addTile(appGrid, tile);
        if (appGrid.getChildCount() == 0) addHeading(page, "暂无显示的应用，可在桌面管理中恢复隐藏项目", 16);
        LinearLayout management = grid(page);
        Button manage = newButton(management, "桌面管理");
        buttons.put(MANAGE, manage);
        manage.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                if (focused) rememberFocus(MANAGE, visible.size());
            }
        });
        manage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showDesktopManager(); }
        });
        manage.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) { showDesktopManager(); return true; }
        });
        setContentView(scroll);
        final ScrollView currentScroll = scroll;
        currentScroll.post(new Runnable() {
            @Override public void run() {
                if (scroll != currentScroll) return;
                View target = buttons.get(wanted);
                if (target == null && !visible.isEmpty()) {
                    int index = Math.max(0, Math.min(previousPosition, visible.size() - 1));
                    target = buttons.get(visible.get(index).key);
                }
                if (target == null) target = buttons.get(MANAGE);
                currentScroll.scrollTo(0, previousY);
                if (target != null) target.requestFocus();
            }
        });
    }

    private void rememberFocus(String key, int position) {
        selectedKey = key; selectedPosition = position;
    }

    private Tile findTile(String key) {
        for (Tile tile : shortcuts) if (tile.key.equals(key)) return tile;
        for (Tile tile : applications) if (tile.key.equals(key)) return tile;
        return null;
    }

    private void open(Tile tile) {
        if ("shortcut:source".equals(tile.key)) {
            try {
                sendBroadcast(new Intent("com.android.sky.SendHotKey").putExtra("specialKey", 178));
                return;
            } catch (RuntimeException blocked) { /* Retain the original TV entry below. */ }
        }
        try { startActivity(new Intent(tile.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)); }
        catch (RuntimeException unavailable) { toast("入口暂不可用，请返回桌面后重试"); }
    }

    private boolean canUninstall(Tile tile) {
        if (!tile.application || listed(tile.packageName, CORE) || tile.packageName.equals(getPackageName())) return false;
        try {
            ApplicationInfo app = getPackageManager().getApplicationInfo(tile.packageName, 0);
            return app.uid >= 10000 &&
                (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (PackageManager.NameNotFoundException removed) { return false; }
    }

    private List<Tile> group(Tile tile) { return tile.application ? applications : shortcuts; }

    private Tile neighbor(Tile tile, int direction) {
        List<Tile> list = group(tile);
        Set<String> hidden = hiddenKeys();
        for (int i = list.indexOf(tile) + direction; i >= 0 && i < list.size(); i += direction) {
            Tile next = list.get(i);
            if (next.fixed || !hidden.contains(next.key)) return next;
        }
        return null;
    }

    private void move(Tile tile, int direction) {
        Tile next = neighbor(tile, direction);
        if (next == null) return;
        List<Tile> list = group(tile);
        Collections.swap(list, list.indexOf(tile), list.indexOf(next));
        List<String> keys = new ArrayList<String>();
        for (Tile entry : list) keys.add(entry.key);
        preferences.edit().putString(tile.application ? "order.apps" : "order.shortcuts", TextUtils.join(",", keys)).apply();
        selectedKey = tile.key;
        render();
    }

    private interface Pick { void chosen(int which); }

    private void menu(String title, List<String> labels, final Pick pick) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView text = (TextView) super.getView(position, convertView, parent);
                text.setTextSize(20);
                text.setTextColor(Color.rgb(235, 238, 243));
                text.setPadding(dp(24), dp(12), dp(24), dp(12));
                return text;
            }
        };
        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setAdapter(adapter, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { pick.chosen(which); }
            }).setNegativeButton("返回", null).create();
        consumeDialogMenuKey(dialog);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dismissed) {
                if (activeDialog == dialog) activeDialog = null;
            }
        });
        activeDialog = dialog;
        dialog.show();
    }

    private void showTileMenu(final Tile tile) {
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        addAction(labels, actions, "打开", new Runnable() { @Override public void run() { open(tile); } });
        if (tile.application) {
            addAction(labels, actions, "应用信息", new Runnable() {
                @Override public void run() { showApplicationInfo(tile); }
            });
        }
        if (neighbor(tile, -1) != null) addAction(labels, actions, "向前移一位", new Runnable() { @Override public void run() { move(tile, -1); } });
        if (neighbor(tile, 1) != null) addAction(labels, actions, "向后移一位", new Runnable() { @Override public void run() { move(tile, 1); } });
        if (!tile.fixed) addAction(labels, actions, "从桌面隐藏", new Runnable() {
            @Override public void run() {
                Set<String> hidden = hiddenKeys(); hidden.add(tile.key);
                preferences.edit().putStringSet("hidden", hidden).apply(); render();
                toast("已隐藏，可在桌面管理中恢复");
            }
        });
        if (canUninstall(tile)) addAction(labels, actions, "卸载应用…", new Runnable() {
            @Override public void run() {
                // Recheck flags immediately before handing off to the OS dialog.
                if (!canUninstall(tile)) { toast("此应用不可在桌面卸载"); return; }
                Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + tile.packageName));
                uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                try { startActivityForResult(uninstall, UNINSTALL); }
                catch (RuntimeException unavailable) { toast("这台电视未提供卸载确认页面，请打开应用信息"); }
            }
        });
        addAction(labels, actions, "桌面管理", new Runnable() { @Override public void run() { showDesktopManager(); } });
        menu(tile.label + (tile.fixed ? " · 固定入口" : ""), labels, new Pick() {
            @Override public void chosen(int which) { actions.get(which).run(); }
        });
    }

    private void addAction(List<String> labels, List<Runnable> actions, String label, Runnable action) {
        labels.add(label); actions.add(action);
    }

    private void showApplicationInfo(Tile tile) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + tile.packageName)));
            return;
        } catch (RuntimeException unavailable) { /* This OEM may not provide the standard page. */ }
        try {
            PackageManager pm = getPackageManager();
            PackageInfo info = pm.getPackageInfo(tile.packageName, 0);
            ApplicationInfo app = info.applicationInfo;
            String label = pm.getApplicationLabel(app).toString();
            String type = (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ? "系统应用（已更新）" :
                (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "系统应用" : "用户应用";
            String message = "名称：" + label + "\n版本：" + (info.versionName == null ? "未知" : info.versionName) +
                "（" + info.versionCode + "）\n类型：" + type + "\n安装文件（APK）：" + installationFileSize(app) +
                "\n仅统计APK安装文件，不含应用数据和缓存。\n包名：" + tile.packageName;
            final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("应用信息").setMessage(message).setNegativeButton("返回", null).create();
            consumeDialogMenuKey(dialog);
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface dismissed) {
                    if (activeDialog == dialog) activeDialog = null;
                }
            });
            activeDialog = dialog;
            dialog.show();
            TextView text = (TextView) dialog.findViewById(android.R.id.message);
            if (text != null) text.setTextSize(18);
        } catch (PackageManager.NameNotFoundException removed) {
            toast("此应用已卸载，请选择其他项目");
            render();
        } catch (RuntimeException unavailable) {
            toast("暂时无法读取此应用信息");
        }
    }

    private String installationFileSize(ApplicationInfo app) {
        Set<String> paths = new HashSet<String>();
        if (app.sourceDir != null) paths.add(app.sourceDir);
        if (app.splitSourceDirs != null) Collections.addAll(paths, app.splitSourceDirs);
        if (paths.isEmpty()) return "无法读取";
        long total = 0;
        for (String path : paths) {
            if (path == null) continue;
            File file = new File(path);
            if (!file.isFile() || !file.canRead()) return "无法读取";
            total += file.length();
        }
        return Formatter.formatFileSize(this, total);
    }

    private void consumeDialogMenuKey(AlertDialog dialog) {
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                // A MENU-down can open this window before MENU-up arrives. Consume
                // both halves here too, so the OEM unhandled-key path stays idle.
                return keyCode == KeyEvent.KEYCODE_MENU;
            }
        });
    }

    private void confirm(String title, String message, final Runnable action) {
        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { action.run(); }
            }).create();
        consumeDialogMenuKey(dialog);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dismissed) {
                if (activeDialog == dialog) activeDialog = null;
            }
        });
        activeDialog = dialog;
        dialog.show();
    }

    private void showDesktopManager() {
        menu("桌面管理", Arrays.asList("恢复隐藏项目", "恢复默认排序", "恢复默认桌面"), new Pick() {
            @Override public void chosen(int which) {
                if (which == 0) showHidden();
                else if (which == 1) confirm("恢复默认排序", "快捷入口恢复默认次序，应用按名称排列。", new Runnable() {
                    @Override public void run() {
                        preferences.edit().remove("order.apps").remove("order.shortcuts").apply(); render();
                    }
                });
                else confirm("恢复默认桌面", "恢复本桌面的显示和排序；应用与电视设置保持不变。", new Runnable() {
                    @Override public void run() {
                        preferences.edit().remove("hidden").remove("order.apps").remove("order.shortcuts").apply(); render();
                    }
                });
            }
        });
    }

    private void showHidden() {
        final Set<String> hidden = hiddenKeys();
        if (hidden.isEmpty()) { toast("没有隐藏项目"); return; }
        final List<Tile> entries = new ArrayList<Tile>();
        List<String> labels = new ArrayList<String>(); labels.add("恢复全部隐藏项目");
        for (Tile tile : shortcuts) if (!tile.fixed && hidden.contains(tile.key)) { entries.add(tile); labels.add(tile.label); }
        for (Tile tile : applications) if (hidden.contains(tile.key)) { entries.add(tile); labels.add(tile.label); }
        menu("恢复隐藏项目", labels, new Pick() {
            @Override public void chosen(int which) {
                if (which == 0) hidden.clear();
                else { Tile tile = entries.get(which - 1); hidden.remove(tile.key); selectedKey = tile.key; }
                preferences.edit().putStringSet("hidden", hidden).apply(); render();
            }
        });
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void addHeading(LinearLayout page, String text, int size) {
        TextView heading = new TextView(this);
        heading.setText(text); heading.setTextColor(Color.rgb(235, 238, 243)); heading.setTextSize(size);
        heading.setPadding(dp(6), dp(6), dp(6), dp(12)); page.addView(heading);
    }

    private LinearLayout grid(LinearLayout page) {
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        page.addView(grid, new LinearLayout.LayoutParams(-1, -2)); return grid;
    }

    private Button newButton(LinearLayout grid, String label) {
        LinearLayout row = grid.getChildCount() == 0 ? null : (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
        if (row == null || row.getChildCount() == 3) {
            row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        Button button = new Button(this);
        button.setId(id++); button.setText(label); button.setTextSize(19); button.setAllCaps(false); button.setFocusable(true);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, dp(68), 1f);
        layout.setMargins(dp(4), dp(4), dp(4), dp(4)); button.setLayoutParams(layout); row.addView(button);
        return button;
    }

    private void addTile(LinearLayout grid, final Tile tile) {
        final int position = visible.size(); visible.add(tile);
        Button button = newButton(grid, tile.label); buttons.put(tile.key, button);
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) { if (focused) rememberFocus(tile.key, position); }
        });
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { open(tile); }
        });
        button.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) { showTileMenu(tile); return true; }
        });
    }
}
