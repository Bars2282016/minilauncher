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
import android.provider.AlarmClock;
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

import java.text.Collator;
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
    private boolean scanning = false;
    private boolean rescanQueued = false;

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
            String action = intent == null ? null : intent.getAction();
            // «Заменено» уже приходит вместе с ADDED/REMOVED — второй скан не нужен.
            if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                return;
            }
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

        clockView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openClock();
            }
        });

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

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        registerReceiver(packagesReceiver, filter);
        receiverRegistered = true;

        updateClock();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(packagesReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Повторное «Домой» на уже открытом лаунчере: показываем полный список.
        if (search.getText().length() > 0) {
            search.setText("");
            hideKeyboard();
        }
    }

    @Override
    public void onBackPressed() {
        if (search.getText().length() > 0) {
            search.setText("");
            hideKeyboard();
            return;
        }
        hideKeyboard();
        // На домашнем экране «назад» ничего не делает.
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && search.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private void updateClock() {
        if (clockView != null) {
            clockView.setText(clockFormat.format(new Date()));
        }
    }

    /**
     * Перечитывает список в фоне. Одновременно работает только один скан:
     * повторный вызов ставит задачу в очередь, а не запускает второй поток.
     */
    private void loadApps() {
        if (scanning) {
            rescanQueued = true;
            return;
        }
        scanning = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<AppEntry> loaded = null;
                try {
                    loaded = queryApps();
                } catch (Throwable ignored) {
                    // Ниже сообщим пользователю, что список прочитать не удалось.
                }
                final List<AppEntry> result = loaded;

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (result != null) {
                            allApps.clear();
                            allApps.addAll(result);
                            applyFilter(search.getText().toString());
                        } else {
                            Toast.makeText(MainActivity.this, R.string.cannot_load, Toast.LENGTH_SHORT).show();
                        }
                        if (rescanQueued) {
                            rescanQueued = false;
                            loadApps();
                        }
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
            if (pkg == null || self.equals(pkg)) {
                continue;
            }
            try {
                CharSequence rawLabel = info.loadLabel(pm);
                String label = rawLabel == null ? pkg : rawLabel.toString().trim();
                if (label.length() == 0) {
                    label = pkg;
                }
                ComponentName component = new ComponentName(pkg, info.activityInfo.name);
                result.add(new AppEntry(label, pkg, component, info.loadIcon(pm)));
            } catch (Throwable ignored) {
                // Битый пакет или иконка: пропускаем только его, остальные остаются.
            }
        }

        // Сортировка по правилам русской локали: «Ё» идёт после «Е», а не перед «А».
        final Collator collator = Collator.getInstance(new Locale("ru", "RU"));
        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry left, AppEntry right) {
                int byLabel = collator.compare(left.label, right.label);
                if (byLabel != 0) {
                    return byLabel;
                }
                return left.packageName.compareToIgnoreCase(right.packageName);
            }
        });
        return result;
    }

    private void applyFilter(String query) {
        // Locale.ROOT, а не Locale.getDefault(): иначе в турецкой локали
        // «I» превращается в «ı» и поиск по латинице перестаёт работать.
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visibleApps.clear();
        for (int i = 0; i < allApps.size(); i++) {
            AppEntry entry = allApps.get(i);
            if (needle.length() == 0
                    || entry.label.toLowerCase(Locale.ROOT).contains(needle)
                    || entry.packageName.toLowerCase(Locale.ROOT).contains(needle)) {
                visibleApps.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
        if (visibleApps.isEmpty()) {
            emptyView.setText(allApps.isEmpty() ? R.string.empty_all : R.string.empty_filter);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void openClock() {
        Intent showAlarms = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        showAlarms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (showAlarms.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(showAlarms);
                return;
            } catch (SecurityException ignored) {
                // Some vendor clock apps demand SET_ALARM even for SHOW_ALARMS.
            } catch (ActivityNotFoundException ignored) {
                // Пробуем следующий вариант.
            }
        }

        Intent setAlarm = new Intent(AlarmClock.ACTION_SET_ALARM);
        setAlarm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(setAlarm);
        } catch (SecurityException ignored) {
            Toast.makeText(this, R.string.cannot_open_clock, Toast.LENGTH_SHORT).show();
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.cannot_open_clock, Toast.LENGTH_SHORT).show();
        }
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

    private static final class ViewHolder {
        ImageView icon;
        TextView label;
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
            ViewHolder holder;
            if (view == null) {
                view = inflater.inflate(R.layout.item_app, parent, false);
                holder = new ViewHolder();
                holder.icon = (ImageView) view.findViewById(R.id.item_icon);
                holder.label = (TextView) view.findViewById(R.id.item_label);
                view.setTag(holder);
            } else {
                holder = (ViewHolder) view.getTag();
            }
            AppEntry entry = visibleApps.get(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            return view;
        }
    }
}
