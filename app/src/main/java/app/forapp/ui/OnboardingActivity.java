package app.forapp.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Prefs;

/**
 * First launch: a few pages on what فوراپ does (Next … Start), then one page per setup step
 * (SMS permission, notifications, battery, bank senders, first destination). Every page can be skipped.
 */
public final class OnboardingActivity extends BaseActivity {
    private static final int REQ_SMS = 1, REQ_NOTIFY = 2;

    private interface Page {
        View art();
        String title();
        String body();
        /** Setup pages: whether it is already done, and what the main button does. Intro pages return null. */
        Boolean done();
        String action();
        void act();
    }

    private final List<Page> intro = new ArrayList<>(), setup = new ArrayList<>();
    private int index; // over intro then setup, then the final page
    private TextView skip, counter;
    private LinearLayout dots;
    private FrameLayout content;
    private TextView primary;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildPages();

        LinearLayout top = Ui.rowLayout(this);
        Ui.pad(top, 20, 14, 20, 6);
        counter = Ui.text(this, "", 12.5f, R.color.mid, Ui.W_BOLD);
        top.addView(counter, Ui.weight1());
        dots = Ui.rowLayout(this);
        top.addView(dots, Ui.weight1());
        dots.setGravity(Gravity.CENTER);
        skip = Ui.text(this, "", 13.5f, R.color.mid, Ui.W_BOLD);
        skip.setGravity(Gravity.END);
        Ui.pad(skip, 12, 10, 0, 10);
        skip.setOnClickListener(v -> skip());
        top.addView(skip, Ui.weight1());
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = body();

        LinearLayout bottom = Ui.col(this);
        Ui.pad(bottom, 24, 8, 24, 24);
        primary = Ui.button(this, "", true, v -> primary());
        bottom.addView(primary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottom);

        if (b != null) index = b.getInt("index");
        show(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("index", index);
    }

    @Override
    protected void onResume() {
        super.onResume();
        show(false); // a step may have been done in another screen
    }

    @Override
    public void onBackPressed() {
        if (index > 0) { index--; show(true); }
        else super.onBackPressed();
    }

    // ---------- navigation ----------

    private int total() { return intro.size() + setup.size() + 1; }
    private boolean inIntro() { return index < intro.size(); }
    private boolean atEnd() { return index >= intro.size() + setup.size(); }
    private Page page() { return inIntro() ? intro.get(index) : atEnd() ? null : setup.get(index - intro.size()); }

    private void next() {
        if (index < total() - 1) { index++; show(true); }
    }

    private void skip() {
        if (inIntro()) index = intro.size(); // to the first setup step
        else index++;
        show(true);
    }

    private void primary() {
        Page p = page();
        if (atEnd()) {
            Prefs.of(this).onboarded(true);
            finish();
        } else if (inIntro() || Boolean.TRUE.equals(p.done())) {
            next();
        } else {
            p.act();
        }
    }

    private void show(boolean animate) {
        Page p = page();
        boolean end = atEnd();
        skip.setText(inIntro() ? "رد کردن" : end ? "" : "بعداً");
        counter.setText(inIntro() || end ? "" : "راه‌اندازی · " + app.forapp.core.Fa.d(index - intro.size() + 1) + " از " + app.forapp.core.Fa.d(setup.size()));
        dots.removeAllViews();
        if (inIntro()) for (int i = 0; i < intro.size(); i++) {
            View d = new View(this);
            boolean on = i == index;
            d.setBackground(Ui.fill(this, on ? R.color.fg : R.color.faint, 99));
            LinearLayout.LayoutParams lp = Ui.lp(Ui.dp(this, on ? 18 : 6), Ui.dp(this, 6));
            lp.setMarginStart(Ui.dp(this, 3));
            lp.setMarginEnd(Ui.dp(this, 3));
            dots.addView(d, lp);
        }

        LinearLayout v = Ui.col(this);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(v, 28, 0, 28, 0);
        View art = end ? finalArt() : p.art();
        v.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Ui.text(this, end ? "همه‌چیز آماده است" : p.title(), 24, R.color.fg, Ui.W_BLACK);
        title.setGravity(Gravity.CENTER);
        Ui.pad(title, 0, 36, 0, 0);
        v.addView(title, full());
        TextView body = Ui.text(this, end ? finalText() : p.body(), 15, R.color.mid, Ui.W_REGULAR);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.45f);
        Ui.pad(body, 0, 12, 0, 0);
        v.addView(body, full());

        if (!inIntro() && !end && Boolean.TRUE.equals(p.done())) {
            TextView ok = Ui.text(this, "✓ انجام شده", 14, R.color.fg, Ui.W_BOLD);
            Ui.pad(ok, 16, 6, 16, 6);
            ok.setBackground(Ui.outline(this, R.color.faint, 99, 1, false));
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 20);
            v.addView(ok, lp);
        }

        content.removeAllViews();
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        content.addView(v, fl);
        if (animate) {
            v.setAlpha(0f);
            v.setTranslationX(Ui.dp(this, -24)); // pages come in from the left, the way a right-to-left book turns
            v.animate().alpha(1f).translationX(0).setDuration(260).start();
        }

        String label;
        if (end) label = "ورود به فوراپ";
        else if (inIntro()) label = index == intro.size() - 1 ? "شروع" : "بعدی";
        else label = Boolean.TRUE.equals(p.done()) ? "بعدی" : p.action();
        primary.setText(label);
    }

    // ---------- pages ----------

    private void buildPages() {
        intro.add(introPage(this::artLogo, "پیامک واریز، مستقیم به سرور شما",
                "فوراپ پیامک بانک را می‌خواند، مبلغ را درمی‌آورد و همان لحظه به وبهوک شما می‌فرستد؛ دیگر کسی لازم نیست واریزها را دستی ثبت کند."));
        intro.add(introPage(this::artFlow, "فقط بانک‌هایی که خودتان می‌خواهید",
                "برای هر بانک شماره‌ی فرستنده‌اش را وارد می‌کنید. بقیه‌ی پیامک‌ها، حتی رمز یکبار مصرف و تبلیغ، دست نمی‌خورند."));
        intro.add(introPage(this::artCode, "کد تطبیق سه‌رقمی",
                "سه رقم آخر هر مبلغ جدا فرستاده می‌شود تا واریز هر مشتری را خودکار پیدا کنید."));
        intro.add(introPage(this::artReliable, "در اینترنت فیلترشده هم مطمئن",
                "قطعی اتصال جزو تلاش‌ها حساب نمی‌شود و تا وصل شوید، خودش می‌فرستد. هر واریز کلید یکتای خودش را دارد و هیچ‌وقت دو بار ثبت نمی‌شود."));
        intro.add(introPage(this::artPrivate, "همه‌چیز روی گوشی خودتان",
                "بدون سرور واسطه و بدون حساب کاربری. درخواست هر مقصد را خودتان می‌سازید و جزئیات هر ارسال را می‌بینید."));

        setup.add(setupPage("اجازه‌ی خواندن پیامک",
                "بدون این اجازه فوراپ پیامک بانک را نمی‌بیند. روی شیائومی اگر اجازه نداد، از «اطلاعات برنامه» گزینه‌ی Allow restricted settings را بزنید.",
                R.drawable.ic_notify, () -> has(Manifest.permission.RECEIVE_SMS), "اجازه بده",
                () -> requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS}, REQ_SMS)));
        if (Build.VERSION.SDK_INT >= 33) {
            setup.add(setupPage("اعلان ارسال ناموفق",
                    "اگر واریزی بعد از همه‌ی تلاش‌ها به سرورتان نرسید، با یک اعلان خبردار می‌شوید.",
                    R.drawable.ic_fail, () -> has(Manifest.permission.POST_NOTIFICATIONS), "روشن کن",
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY)));
        }
        setup.add(setupPage("بهینه‌سازی باتری",
                "خاموشش کنید تا در خواب گوشی هم بی‌معطلی بفرستد. روی شیائومی «شروع خودکار» و «بدون محدودیت» را هم روشن کنید.",
                R.drawable.ic_gear, () -> {
                    PowerManager pm = getSystemService(PowerManager.class);
                    return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                }, "خاموش کن", () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                }));
        setup.add(setupPage("فرستنده‌ی بانک‌ها",
                "برای هر بانکی که واریزهایش را می‌خواهید، شماره‌ای که با آن پیامک می‌دهد را وارد کنید.",
                R.drawable.ic_search, () -> {
                    for (Db.Bank bk : Db.get(this).banks()) if (!bk.senderList().isEmpty()) return true;
                    return false;
                }, "تنظیم بانک‌ها", () -> startActivity(new Intent(this, BanksActivity.class))));
        setup.add(setupPage("اولین مقصد",
                "آدرس وبهوک سرورتان و کلید API را بدهید، یا یک بات تلگرام و چت آیدی. با «ارسال آزمایشی» همان لحظه ببینید می‌رسد یا نه.",
                R.drawable.ic_send, () -> !Db.get(this).dests().isEmpty(), "افزودن مقصد",
                () -> startActivity(new Intent(this, DestinationEditActivity.class))));
    }

    private interface Art { View make(); }
    private interface Check { boolean ok(); }

    private Page introPage(Art art, String title, String body) {
        return new Page() {
            public View art() { return art.make(); }
            public String title() { return title; }
            public String body() { return body; }
            public Boolean done() { return null; }
            public String action() { return null; }
            public void act() {}
        };
    }

    private Page setupPage(String title, String body, int icon, Check check, String action, Runnable act) {
        return new Page() {
            public View art() {
                FrameLayout f = new FrameLayout(this_());
                View ring = new View(this_());
                boolean ok = check.ok();
                ring.setBackground(ok ? Ui.fill(this_(), R.color.fg, 99) : Ui.outline(this_(), R.color.faint, 99, 2, false));
                f.addView(ring, new FrameLayout.LayoutParams(Ui.dp(this_(), 120), Ui.dp(this_(), 120), Gravity.CENTER));
                android.widget.ImageView i = Ui.icon(this_(), ok ? R.drawable.ic_ok : icon, ok ? R.color.bg : R.color.fg, 44);
                f.addView(i, new FrameLayout.LayoutParams(Ui.dp(this_(), 44), Ui.dp(this_(), 44), Gravity.CENTER));
                return f;
            }
            public String title() { return title; }
            public String body() { return body; }
            public Boolean done() { return check.ok(); }
            public String action() { return action; }
            public void act() { act.run(); }
        };
    }

    private OnboardingActivity this_() { return this; }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        boolean granted = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) next();
        else if (code == REQ_SMS && !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
            // Android 13+ restricted settings (MIUI too): only app info can grant it now.
            Ui.toast(this, "از «اطلاعات برنامه» اجازه‌ی پیامک را بدهید");
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } else show(false);
    }

    // ---------- illustrations (plain views in the app's own style) ----------

    private View artLogo() {
        LinearLayout l = Ui.col(this);
        l.setGravity(Gravity.CENTER);
        View dot = new View(this);
        dot.setBackground(Ui.fill(this, R.color.red, 99));
        l.addView(dot, Ui.lp(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView n = Ui.text(this, "فوراپ", 58, R.color.fg, Ui.W_BLACK);
        n.setGravity(Gravity.CENTER);
        Ui.pad(n, 0, 10, 0, 0);
        l.addView(n);
        return l;
    }

    private View artFlow() {
        LinearLayout l = Ui.col(this);
        l.setGravity(Gravity.CENTER);
        String[] steps = {"پیامک بانک", "فوراپ", "سرور شما"};
        for (int i = 0; i < steps.length; i++) {
            TextView t = Ui.text(this, steps[i], 17, i == 1 ? R.color.bg : R.color.fg, Ui.W_BLACK);
            t.setGravity(Gravity.CENTER);
            Ui.pad(t, 0, 16, 0, 16);
            t.setBackground(i == 1 ? Ui.fill(this, R.color.fg, 18) : Ui.outline(this, R.color.faint, 18, 1.5f, false));
            l.addView(t, Ui.lp(Ui.dp(this, 220), ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < steps.length - 1) {
                View line = new View(this);
                line.setBackgroundColor(Ui.color(this, i == 0 ? R.color.red : R.color.faint));
                LinearLayout.LayoutParams lp = Ui.lp(Ui.dp(this, 2), Ui.dp(this, 26));
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                l.addView(line, lp);
            }
        }
        return l;
    }

    private View artCode() {
        LinearLayout l = Ui.col(this);
        l.setGravity(Gravity.CENTER);
        TextView amt = Ui.text(this, Ui.amount(this, 1_050_132L), 52, R.color.fg, Ui.W_BLACK);
        amt.setTextDirection(View.TEXT_DIRECTION_LTR);
        amt.setGravity(Gravity.CENTER);
        l.addView(amt);
        TextView code = Ui.text(this, "کد تطبیق " + app.forapp.core.Fa.d("132"), 15, R.color.red, Ui.W_BOLD);
        Ui.pad(code, 16, 6, 16, 6);
        code.setBackground(Ui.fill(this, R.color.redbg, 99));
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        l.addView(code, lp);
        return l;
    }

    private View artReliable() {
        LinearLayout l = Ui.col(this);
        l.setGravity(Gravity.CENTER);
        l.addView(stateRow(R.drawable.ic_retry, "منتظر اتصال", "فیلترشکن خاموش", R.color.mid));
        View gap = new View(this);
        l.addView(gap, Ui.lp(1, Ui.dp(this, 10)));
        l.addView(stateRow(R.drawable.ic_ok, "رسید", "HTTP ۲۰۰", R.color.fg));
        return l;
    }

    private View stateRow(int icon, String state, String sub, int color) {
        LinearLayout r = Ui.rowLayout(this);
        Ui.pad(r, 18, 14, 18, 14);
        r.setBackground(Ui.outline(this, R.color.faint, 16, 1.5f, false));
        r.addView(Ui.icon(this, icon, color, 20));
        TextView t = Ui.text(this, state, 16, color, Ui.W_BLACK);
        Ui.pad(t, 10, 0, 10, 0);
        r.addView(t, Ui.weight1());
        r.addView(Ui.text(this, sub, 13, R.color.mid, Ui.W_REGULAR));
        LinearLayout.LayoutParams lp = Ui.lp(Ui.dp(this, 280), ViewGroup.LayoutParams.WRAP_CONTENT);
        r.setLayoutParams(lp);
        return r;
    }

    private View artPrivate() {
        LinearLayout l = Ui.col(this);
        l.setGravity(Gravity.CENTER);
        String[] items = {"بدون سرور واسطه", "بدون حساب کاربری", "درخواست به سلیقه‌ی شما"};
        for (String s : items) {
            LinearLayout r = Ui.rowLayout(this);
            Ui.pad(r, 0, 6, 0, 6);
            r.addView(Ui.icon(this, R.drawable.ic_ok, R.color.fg, 20));
            TextView t = Ui.text(this, s, 17, R.color.fg, Ui.W_BOLD);
            Ui.pad(t, 10, 0, 0, 0);
            r.addView(t);
            l.addView(r);
        }
        return l;
    }

    private View finalArt() {
        FrameLayout f = new FrameLayout(this);
        View ring = new View(this);
        ring.setBackground(Ui.fill(this, R.color.fg, 99));
        f.addView(ring, new FrameLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120), Gravity.CENTER));
        f.addView(Ui.icon(this, R.drawable.ic_ok, R.color.bg, 48), new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.CENTER));
        return f;
    }

    private String finalText() {
        int left = 0;
        for (Page p : setup) if (!Boolean.TRUE.equals(p.done())) left++;
        return left == 0 ? "فوراپ از همین حالا واریزها را می‌فرستد."
                : app.forapp.core.Fa.d(left) + " قدم مانده؛ هر وقت خواستید از صفحه‌ی اصلی یا تنظیمات کاملش کنید.";
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
