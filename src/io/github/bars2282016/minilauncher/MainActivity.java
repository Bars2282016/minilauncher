package io.github.bars2282016.minilauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Минимальный домашний экран: часы, поиск, сетка приложений.
 * Никаких сторонних библиотек — только фреймворк, поэтому APK крошечный.
 */
public class MainActivity extends Activity {

    private static final long CLOCK_INTERVAL_MS = 20000L;

    private GridView grid;
    private EditText search;
    private TextView clockView;
    private TextView emptyView;

    private final List<AppEntry> allApps = new ArrayList<AppEntry>();
    private final List<AppEntry> visibleApps = new ArrayList<AppEntry>();
    private AppAdapter adapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private boolean receiverRegistered = false;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, CLOCK_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver packagesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        grid = (GridView) findViewById(R.id.grid);
        search = (EditText) findViewById(R.id.search);
        clockView = (TextView) findViewById(R.id.clock);
        emptyView = (TextView) findViewById(R.id.empty);

        adapter = new AppAdapter();
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < visibleApps.size()) {
                    launch(visibleApps.get(position));
                }
            }
        });

        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < visibleApps.size()) {
                    showAppMenu(visibleApps.get(position));
                    return true;
                }
                return false;
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(s == null ? "" : s.toString());
            }
        });

        updateClock();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);

        // Пока лаунчер был на паузе, приёмник отписан — приложения могли
        // установить/удалить (например, из Play). Перечитываем список.
        if (!allApps.isEmpty()) {
            loadApps();
        }

        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            registerReceiver(packagesReceiver, filter);
            receiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        if (receiverRegistered) {
            unregisterReceiver(packagesReceiver);
            receiverRegistered = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (search.getText().length() > 0) {
            search.setText("");
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
        // На домашнем экране «назад» ничего не делает.
    }

    private void updateClock() {
        if (clockView != null) {
            clockView.setText(clockFormat.format(new Date()));
        }
    }

    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> loaded = queryApps();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        allApps.clear();
                        allApps.addAll(loaded);
                        applyFilter(search.getText().toString());
                    }
                });
            }
        }, "app-scan").start();
    }

    private List<AppEntry> queryApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
        List<AppEntry> result = new ArrayList<AppEntry>(resolved.size());
        String self = getPackageName();

        for (int i = 0; i < resolved.size(); i++) {
            ResolveInfo info = resolved.get(i);
            if (info == null || info.activityInfo == null) {
                continue;
            }
            String pkg = info.activityInfo.packageName;
            if (self.equals(pkg)) {
                continue;
            }
            CharSequence rawLabel = info.loadLabel(pm);
            String label = rawLabel == null ? pkg : rawLabel.toString().trim();
            if (label.length() == 0) {
                label = pkg;
            }
            ComponentName component = new ComponentName(pkg, info.activityInfo.name);
            result.add(new AppEntry(label, pkg, component, info.loadIcon(pm)));
        }

        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry left, AppEntry right) {
                return left.label.compareToIgnoreCase(right.label);
            }
        });
        return result;
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        visibleApps.clear();
        for (int i = 0; i < allApps.size(); i++) {
            AppEntry entry = allApps.get(i);
            if (needle.length() == 0
                    || entry.label.toLowerCase(Locale.getDefault()).contains(needle)
                    || entry.packageName.toLowerCase(Locale.getDefault()).contains(needle)) {
                visibleApps.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(visibleApps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void launch(AppEntry entry) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(entry.componentName);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException first) {
            Intent fallback = getPackageManager().getLaunchIntentForPackage(entry.packageName);
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(fallback);
                    return;
                } catch (ActivityNotFoundException ignored) {
                    // ниже покажем сообщение
                }
            }
            Toast.makeText(this, R.string.cannot_launch, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppMenu(final AppEntry entry) {
        String[] items = new String[]{
                getString(R.string.action_open),
                getString(R.string.action_info),
                getString(R.string.action_uninstall)
        };
        new AlertDialog.Builder(this)
                .setTitle(entry.label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            launch(entry);
                        } else if (which == 1) {
                            openAppInfo(entry.packageName);
                        } else {
                            uninstall(entry.packageName);
                        }
                    }
                })
                .show();
    }

    private void openAppInfo(String packageName) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.cannot_launch, Toast.LENGTH_SHORT).show();
        }
    }

    private void uninstall(String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.cannot_uninstall, Toast.LENGTH_SHORT).show();
        }
    }

    private final class AppAdapter extends BaseAdapter {

        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override
        public int getCount() {
            return visibleApps.size();
        }

        @Override
        public Object getItem(int position) {
            return visibleApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.item_app, parent, false);
            }
            AppEntry entry = visibleApps.get(position);
            ImageView icon = (ImageView) view.findViewById(R.id.item_icon);
            TextView label = (TextView) view.findViewById(R.id.item_label);
            icon.setImageDrawable(entry.icon);
            label.setText(entry.label);
            return view;
        }
    }
}
