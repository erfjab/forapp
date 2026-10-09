package app.forapp.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Fa;
import app.forapp.core.Forwarder;
import app.forapp.core.Prefs;
import app.forapp.core.Updater;

public final class SettingsActivity extends BaseActivity {
    private LinearLayout list;
    private int widgetsBefore = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        backHeader("تنظیمات");
        ScrollView sv = new ScrollView(this);
        list = Ui.col(this);
        sv.addView(list);
        body().addView(sv, Ui.frameMatch());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (widgetsBefore >= 0) {
            if (Widget.count(this) > widgetsBefore) Widget.confirmAdded(this);
            widgetsBefore = -1;
        }
        build();
    }

    private void build() {
        list.removeAllViews();
        Prefs p = Prefs.of(this);
        Db db = Db.get(this);

        add(Ui.section(this, "سرویس"));
        Switch on = Ui.toggle(this, p.enabled());
        on.setOnCheckedChangeListener((v, c) -> p.enabled(c));
        add(Ui.row(this, "فوروارد خودکار", "پیامک‌های بانکی را می‌خواند و می‌فرستد", on));

        PowerManager pm = getSystemService(PowerManager.class);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        link("بهینه‌سازی باتری", batteryOk ? "خاموش است" : "روشن است", batteryOk ? R.color.fg : R.color.red,
                batteryOk ? "فوراپ در حالت خواب گوشی هم اینترنت دارد" : "خاموشش کنید تا ارسال‌ها در حالت خواب گوشی دیر نشوند",
                () -> {
                    try {
                        startActivity(new Intent(batteryOk ? Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                                : Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                });
        boolean sms = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        link("دسترسی خواندن پیامک", sms ? "داده شده" : "داده نشده", sms ? R.color.fg : R.color.red, null, this::appInfo);
        if (Build.VERSION.SDK_INT >= 33) {
            boolean n = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            link("اعلان ارسال ناموفق", n ? "روشن" : "خاموش", n ? R.color.fg : R.color.mid, null, this::appInfo);
        }

        add(Ui.section(this, "مسیر پیامک‌ها"));
        List<Db.Dest> ds = db.dests();
        int active = 0;
        for (Db.Dest d : ds) if (d.enabled) active++;
        link("مقصدها", ds.isEmpty() ? "هنوز ندارید" : Fa.d(active) + " فعال از " + Fa.d(ds.size()),
                ds.isEmpty() ? R.color.red : R.color.mid, null, () -> startActivity(new Intent(this, DestinationsActivity.class)));
        int configured = 0;
        for (Db.Bank bk : db.banks()) if (!bk.senderList().isEmpty()) configured++;
        link("بانک‌ها و فرستنده‌ها", configured == 0 ? "تنظیم نشده" : Fa.d(configured) + " بانک",
                configured == 0 ? R.color.red : R.color.mid, "فقط پیامک این فرستنده‌ها خوانده می‌شود",
                () -> startActivity(new Intent(this, BanksActivity.class)));
        Switch dep = Ui.toggle(this, p.depositOnly());
        dep.setOnCheckedChangeListener((v, c) -> p.depositOnly(c));
        add(Ui.row(this, "فقط پیامک واریز", "برداشت، رمز یکبار مصرف و تبلیغ بانک ارسال نمی‌شوند", dep));

        add(Ui.section(this, "ارسال"));
        choice("تلاش دوباره در خطا", p.maxAttempts(), new int[]{3, 5, 10, 15}, " بار", p::maxAttempts,
                "فاصله‌ی تلاش‌ها از ۱۵ ثانیه شروع می‌شود و هر بار دو برابر می‌شود. بی‌اینترنتی جزو تلاش‌ها حساب نمی‌شود.");
        choice("مهلت پاسخ سرور", p.timeoutSec(), new int[]{5, 10, 20, 30}, " ثانیه", p::timeoutSec, null);

        add(Ui.section(this, "لاگ"));
        choice("نگهداری لاگ", p.retentionDays(), new int[]{30, 90, 180, 365}, " روز", p::retentionDays, null);
        link("پاک کردن لاگ", "", R.color.mid, "ارسال‌های در صف پاک نمی‌شوند", () ->
                Ui.confirm(this, "پاک کردن لاگ", "همه‌ی پیامک‌های ارسال‌شده و ناموفق از لاگ پاک می‌شوند. این کار برنمی‌گردد.", "پاک کن", () -> {
                    Db.get(this).clearLog();
                    Forwarder.changed();
                    Ui.toast(this, "لاگ پاک شد");
                }));

        add(Ui.section(this, "ویجت"));
        link("افزودن ویجت جمع‌وجور", "۲×۱", R.color.mid, "تعداد و مبلغ واریزهای امروز", () -> pinWidget(Widget.class));
        link("افزودن ویجت نوار", "۴×۱", R.color.mid, "واریزها، مبلغ و ناموفق‌ها در یک نوار", () -> pinWidget(Widget.Strip.class));

        add(Ui.section(this, "برنامه"));
        link("معرفی و راه‌اندازی", "", R.color.mid, "صفحه‌های معرفی و قدم‌های راه‌اندازی، دوباره", () -> startActivity(new Intent(this, OnboardingActivity.class)));
        link("به‌روزرسانی", "بررسی", R.color.fg, "نسخه‌ی فعلی " + Fa.d(versionName()), this::checkUpdate);

        TextView ver = Ui.text(this, "فوراپ نسخه‌ی " + Fa.d(versionName()) + " · همه‌ی داده‌ها فقط روی همین گوشی است · تم مثل گوشی",
                11.5f, R.color.mid, Ui.W_REGULAR);
        Ui.pad(ver, 20, 24, 20, 32);
        add(Ui.hairline(this, R.color.line));
        add(ver);
    }

    private void add(View v) {
        list.addView(v);
    }

    private void link(String title, String value, int colorRes, String sub, Runnable click) {
        LinearLayout r = Ui.row(this, title, sub, Ui.value(this, value, colorRes));
        Ui.ripple(r, false);
        r.setOnClickListener(v -> click.run());
        add(Ui.hairline(this, R.color.line));
        add(r);
    }

    private interface IntSetter { void set(int v); }

    private void choice(String title, int current, int[] options, String unit, IntSetter set, String sub) {
        link(title, Fa.d(current) + unit, R.color.mid, sub, () -> {
            String[] labels = new String[options.length];
            int sel = -1;
            for (int i = 0; i < options.length; i++) {
                labels[i] = Fa.d(options[i]) + unit;
                if (options[i] == current) sel = i;
            }
            new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, sel, (d, w) -> {
                set.set(options[w]);
                d.dismiss();
                build();
            }).show();
        });
    }

    /** Asks the launcher to place the widget; it shows its own "Add to home screen" dialog. */
    private void pinWidget(Class<?> provider) {
        widgetsBefore = Widget.count(this); // some launchers (MIUI) add it without calling back; onResume checks the count
        android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(this);
        if (!m.isRequestPinAppWidgetSupported()
                || !m.requestPinAppWidget(new android.content.ComponentName(this, provider), null,
                android.app.PendingIntent.getBroadcast(this, provider == Widget.class ? 1 : 2, new Intent(this, Widget.Pinned.class),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE))) {
            Ui.toast(this, "این لانچر افزودن مستقیم را پشتیبانی نمی‌کند؛ از منوی ویجت‌های صفحه‌ی اصلی اضافه کنید");
        }
    }

    // ---------- update ----------

    /** Asks GitHub for the latest release; if it is newer, offers to download and install it in a sheet. */
    private void checkUpdate() {
        LinearLayout content = Ui.col(this);
        android.app.Dialog sheet = Ui.sheet(this, content);
        TextView title = Ui.text(this, "به‌روزرسانی", 20, R.color.fg, Ui.W_BLACK);
        Ui.pad(title, 2, 14, 2, 2);
        content.addView(title);
        TextView status = Ui.text(this, "در حال بررسی…", 13, R.color.mid, Ui.W_REGULAR);
        status.setLineSpacing(0, 1.3f);
        Ui.pad(status, 2, 6, 2, 6);
        content.addView(status);
        sheet.show();

        String mine = versionName();
        new Thread(() -> {
            Updater.Release r;
            try {
                r = Updater.latest();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("به سرور به‌روزرسانی (گیت‌هاب) وصل نشد. اینترنت یا فیلترشکن را بررسی کنید.\n" + Fa.d(String.valueOf(e.getMessage())));
                    status.setTextColor(Ui.color(this, R.color.red));
                });
                return;
            }
            runOnUiThread(() -> {
                if (r == null || !Updater.newer(r.name, mine)) {
                    status.setText("آخرین نسخه را دارید · " + Fa.d(versionName()));
                    return;
                }
                status.setText("نسخه‌ی " + Fa.d(r.name) + " آماده است. نسخه‌ی شما " + Fa.d(versionName()) + "."
                        + (r.size > 0 ? "\nحجم " + Fa.d(r.size / 1024) + " کیلوبایت" : ""));
                status.setTextColor(Ui.color(this, R.color.fg));
                if (!r.notes.isEmpty()) {
                    TextView notes = Ui.text(this, r.notes, 12.5f, R.color.mid, Ui.W_REGULAR);
                    notes.setLineSpacing(0, 1.3f);
                    Ui.pad(notes, 14, 10, 14, 10);
                    notes.setBackground(Ui.fill(this, R.color.line, 12));
                    content.addView(notes);
                }
                TextView go = Ui.button(this, "دانلود و نصب", true, null);
                go.setOnClickListener(v -> downloadAndInstall(r, go, status));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                p.topMargin = Ui.dp(this, 14);
                content.addView(go, p);
            });
        }, "forapp-update").start();
    }

    private void downloadAndInstall(Updater.Release r, TextView button, TextView status) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            // One-time: Android asks the user to let ForApp install updates.
            Ui.toast(this, "اجازه‌ی نصب را بدهید و برگردید");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        button.setEnabled(false);
        button.setAlpha(0.5f);
        new Thread(() -> {
            try {
                java.io.File apk = Updater.download(this, r, (done, total) -> runOnUiThread(() ->
                        button.setText(total > 0 ? "در حال دانلود " + Fa.d(done * 100 / total) + "٪" : "در حال دانلود…")));
                runOnUiThread(() -> button.setText("در حال نصب…"));
                Updater.install(this, apk);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("دانلود نشد: " + Fa.d(String.valueOf(e.getMessage())));
                    status.setTextColor(Ui.color(this, R.color.red));
                    button.setEnabled(true);
                    button.setAlpha(1f);
                    button.setText("دوباره امتحان کن");
                });
            }
        }, "forapp-download").start();
    }

    private void appInfo() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
