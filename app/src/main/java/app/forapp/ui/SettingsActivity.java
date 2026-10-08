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

public final class SettingsActivity extends BaseActivity {
    private LinearLayout list;

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
                batteryOk ? "فورآپ در حالت خواب گوشی هم اینترنت دارد" : "خاموشش کنید تا ارسال‌ها در حالت خواب گوشی دیر نشوند",
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

        TextView ver = Ui.text(this, "فورآپ نسخه‌ی " + Fa.d(versionName()) + " · همه‌ی داده‌ها فقط روی همین گوشی است · تم مثل گوشی",
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
