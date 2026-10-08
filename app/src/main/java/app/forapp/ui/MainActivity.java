package app.forapp.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Fa;
import app.forapp.core.Forwarder;
import app.forapp.core.Prefs;
import app.forapp.core.SmsParser;

/** Today at a glance, then every deposit grouped by day, newest first. */
public final class MainActivity extends BaseActivity {
    private static final int REQ_SMS = 1, REQ_NOTIFY = 2;
    private static final int LOG_LIMIT = 20_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final TimeZone tz = TimeZone.getDefault();
    private final Set<Long> collapsed = new HashSet<>();
    private final Runnable refresh = this::load;

    private ListView list;
    private final Adapter adapter = new Adapter();
    private LinearLayout problemsBox, chips, brand, searchBar;
    private TextView heroNum, heroSum, heroBad, brandSub, emptyText;
    private ChartView chart;
    private EditText search;
    private LinearLayout sticky;

    private List<Db.Msg> all = new ArrayList<>();
    private List<Db.Dest> dests = new ArrayList<>();
    private List<String> problems = new ArrayList<>();
    private long destFilter = 0;
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildHeader();
        FrameLayout body = body();
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.addHeaderView(buildTop(), null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object o = adapter.getItem(pos - list.getHeaderViewsCount());
            if (o instanceof Day) toggle(((Day) o).start);
            else if (o instanceof Db.Msg) details((Db.Msg) o);
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {}
            @Override public void onScroll(AbsListView v, int first, int visible, int total) { updateSticky(first); }
        });
        body.addView(list, Ui.frameMatch());
        sticky = dayView(null);
        sticky.setVisibility(View.GONE);
        sticky.setOnClickListener(v -> {
            Object tag = v.getTag();
            if (tag instanceof Day) toggle(((Day) tag).start);
        });
        body.addView(sticky, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Forwarder.listen(refresh);
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Forwarder.unlisten(refresh);
    }

    @Override
    public void onBackPressed() {
        if (searchBar.getVisibility() == View.VISIBLE) closeSearch();
        else super.onBackPressed();
    }

    // ---------- header ----------

    private void buildHeader() {
        LinearLayout h = Ui.rowLayout(this);
        Ui.pad(h, 16, 4, 8, 4);
        h.setMinimumHeight(Ui.dp(this, 56));

        brand = Ui.rowLayout(this);
        View dot = new View(this);
        dot.setBackground(Ui.fill(this, R.color.red, 99));
        brand.addView(dot, Ui.lp(Ui.dp(this, 8), Ui.dp(this, 8)));
        TextView name = Ui.text(this, "فورآپ", 17, R.color.fg, Ui.W_BLACK);
        Ui.pad(name, 8, 0, 6, 0);
        brand.addView(name);
        brandSub = Ui.ellipsize(Ui.text(this, "", 11.5f, R.color.mid, Ui.W_REGULAR));
        brand.addView(brandSub, Ui.weight1());
        h.addView(brand, Ui.weight1());

        searchBar = Ui.rowLayout(this);
        searchBar.setVisibility(View.GONE);
        search = new EditText(this);
        search.setHint("کد سه‌رقمی، مبلغ یا بانک");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTypeface(Ui.font(this, Ui.W_BOLD));
        search.setTextColor(Ui.color(this, R.color.fg));
        search.setHintTextColor(Ui.color(this, R.color.mid));
        search.setBackground(null);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { query = e.toString().trim(); render(); }
        });
        searchBar.addView(search, Ui.weight1());
        searchBar.addView(Ui.iconButton(this, R.drawable.ic_close, "بستن جستجو", v -> closeSearch()));
        h.addView(searchBar, Ui.weight1());

        h.addView(Ui.iconButton(this, R.drawable.ic_send, "ارسال آزمایشی", v -> testAll()));
        h.addView(Ui.iconButton(this, R.drawable.ic_search, "جستجو", v -> openSearch()));
        h.addView(Ui.iconButton(this, R.drawable.ic_gear, "تنظیمات", v -> startActivity(new Intent(this, SettingsActivity.class))));
        root.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.hairline(this, R.color.faint));
    }

    private void openSearch() {
        brand.setVisibility(View.GONE);
        searchBar.setVisibility(View.VISIBLE);
        search.requestFocus();
        getSystemService(InputMethodManager.class).showSoftInput(search, 0);
    }

    private void closeSearch() {
        search.setText("");
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        searchBar.setVisibility(View.GONE);
        brand.setVisibility(View.VISIBLE);
    }

    // ---------- top of the list: problems, today, chart, filters ----------

    private View buildTop() {
        LinearLayout top = Ui.col(this);
        problemsBox = Ui.col(this);
        top.addView(problemsBox);

        LinearLayout hero = Ui.rowLayout(this);
        hero.setGravity(Gravity.BOTTOM);
        Ui.pad(hero, 20, 18, 20, 0);
        heroNum = Ui.text(this, "۰", 96, R.color.fg, Ui.W_BLACK);
        heroNum.setIncludeFontPadding(false);
        heroNum.setLineSpacing(0, 0.85f);
        hero.addView(heroNum, Ui.weight1());
        LinearLayout side = Ui.col(this);
        side.setGravity(Gravity.END);
        heroSum = Ui.text(this, "", 14, R.color.fg, Ui.W_BOLD);
        side.addView(heroSum);
        side.addView(Ui.text(this, "تومان امروز", 11.5f, R.color.mid, Ui.W_REGULAR));
        heroBad = Ui.text(this, "", 11.5f, R.color.red, Ui.W_BOLD);
        side.addView(heroBad);
        Ui.pad(side, 0, 0, 0, 10);
        hero.addView(side);
        top.addView(hero);

        TextView lbl = Ui.text(this, "واریز امروز دریافت و ارسال شد.", 14, R.color.fg, Ui.W_BOLD);
        Ui.pad(lbl, 20, 6, 20, 8);
        top.addView(lbl);

        chart = new ChartView(this);
        top.addView(chart);

        LinearLayout logh = Ui.rowLayout(this);
        Ui.pad(logh, 20, 22, 20, 0);
        logh.addView(Ui.text(this, "همه‌ی واریزها", 14, R.color.fg, Ui.W_BOLD), Ui.weight1());
        logh.addView(Ui.text(this, "جدیدترین بالا", 11.5f, R.color.mid, Ui.W_REGULAR));
        top.addView(logh);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = Ui.rowLayout(this);
        Ui.pad(chips, 20, 10, 20, 12);
        hs.addView(chips);
        top.addView(hs);

        emptyText = Ui.text(this, "", 13, R.color.mid, Ui.W_REGULAR);
        emptyText.setGravity(Gravity.CENTER);
        Ui.pad(emptyText, 24, 32, 24, 32);
        emptyText.setVisibility(View.GONE);
        top.addView(emptyText);
        return top;
    }

    // ---------- data ----------

    private void load() {
        long filter = destFilter;
        io.execute(() -> {
            Db db = Db.get(this);
            List<Db.Msg> msgs = db.log(filter, LOG_LIMIT);
            List<Db.Dest> ds = db.dests();
            List<String> pr = Forwarder.problems(this);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                all = msgs;
                dests = ds;
                problems = pr;
                render();
            });
        });
    }

    private void render() {
        long now = System.currentTimeMillis(), today = Fa.dayStart(now, tz);
        int active = 0;
        for (Db.Dest d : dests) if (d.enabled) active++;
        brandSub.setText(Prefs.of(this).enabled() ? Fa.d(active) + " مقصد فعال" : "خاموش");

        // Today
        int count = 0, failed = 0, waiting = 0;
        long toman = 0;
        int[] hours = new int[24], bad = new int[24];
        java.util.Calendar cal = java.util.Calendar.getInstance(tz);
        for (Db.Msg m : all) {
            if (m.at < today) break;
            if (m.skipped) continue;
            count++;
            if (m.amount != null) toman += "toman".equals(m.unit) ? m.amount : m.amount / 10;
            cal.setTimeInMillis(m.at);
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
            hours[h]++;
            if (m.failed > 0 || m.pending > 0) bad[h]++;
            if (m.failed > 0) failed++;
            else if (m.pending > 0) waiting++;
        }
        heroNum.setText(Fa.d(count));
        heroSum.setText(Fa.group(toman));
        String b = failed > 0 ? Fa.d(failed) + " ناموفق" : "";
        if (waiting > 0) b += (b.isEmpty() ? "" : " · ") + Fa.d(waiting) + " در صف تلاش";
        heroBad.setText(b);
        heroBad.setVisibility(b.isEmpty() ? View.GONE : View.VISIBLE);
        chart.set(hours, bad);

        renderProblems();
        renderChips();

        // Log, grouped by day
        List<Object> items = new ArrayList<>();
        String q = SmsParser.normalize(query).toLowerCase(Locale.US).replace(",", "");
        Day cur = null;
        for (Db.Msg m : all) {
            if (!q.isEmpty() && !matches(m, q)) continue;
            long ds = Fa.dayStart(m.at, tz);
            if (cur == null || cur.start != ds) {
                cur = new Day(ds, ds == today ? "امروز" : ds == Fa.dayStart(today - 3600_000L, tz) ? "دیروز" : null,
                        Fa.dayLabel(m.at, now, tz));
                items.add(cur);
            }
            if (!m.skipped) cur.count++;
            if (m.failed > 0) cur.bad++;
            if (!collapsed.contains(ds)) items.add(m);
        }
        adapter.items = items;
        adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            emptyText.setText(q.isEmpty() ? "هنوز پیامک واریزی نرسیده. وقتی برسد اینجا می‌بینید که به کدام مقصد رفت."
                    : "چیزی با «" + query + "» پیدا نشد.");
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
        list.post(() -> updateSticky(list.getFirstVisiblePosition()));
    }

    private static boolean matches(Db.Msg m, String q) {
        if (m.bank != null && m.bank.contains(q)) return true;
        if (m.dests != null && m.dests.contains(q)) return true;
        if (SmsParser.canonicalSender(m.sender).contains(q)) return true;
        if (m.amount != null && Long.toString(m.amount).contains(q)) return true;
        return SmsParser.normalize(m.body).replace(",", "").toLowerCase(Locale.US).contains(q);
    }

    private void toggle(long day) {
        if (!collapsed.remove(day)) collapsed.add(day);
        render();
    }

    private void renderChips() {
        chips.removeAllViews();
        int active = 0;
        for (Db.Dest d : dests) if (d.enabled) active++;
        if (active < 2) {
            ((View) chips.getParent()).setVisibility(View.GONE);
            return;
        }
        ((View) chips.getParent()).setVisibility(View.VISIBLE);
        addChip("همه", 0);
        for (Db.Dest d : dests) if (d.enabled) addChip(d.name, d.id);
    }

    private void addChip(String label, long id) {
        boolean on = destFilter == id;
        TextView t = Ui.text(this, label, 12, on ? R.color.bg : R.color.mid, Ui.W_BOLD);
        Ui.pad(t, 12, 3, 12, 3);
        t.setBackground(on ? Ui.fill(this, R.color.fg, 99) : Ui.outline(this, R.color.faint, 99, 1, false));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> {
            destFilter = id;
            load();
        });
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMarginEnd(Ui.dp(this, 6));
        chips.addView(t, p);
    }

    // ---------- setup problems ----------

    private void renderProblems() {
        problemsBox.removeAllViews();
        for (String p : problems) {
            String text, action;
            Runnable fix;
            switch (p) {
                case "sms":
                    text = "اجازه‌ی خواندن پیامک داده نشده. بدون آن هیچ واریزی ارسال نمی‌شود.";
                    action = "اجازه بده";
                    fix = this::askSms;
                    break;
                case "off":
                    text = "فوروارد خاموش است.";
                    action = "روشن کن";
                    fix = () -> { Prefs.of(this).enabled(true); load(); };
                    break;
                case "battery":
                    text = "بهینه‌سازی باتری برای فورآپ روشن است و ممکن است ارسال‌ها دیر انجام شوند.";
                    action = "خاموش کن";
                    fix = this::askBattery;
                    break;
                case "dest":
                    text = "هنوز مقصدی ندارید. آدرس وبهوک و کلید API را اضافه کنید.";
                    action = "افزودن مقصد";
                    fix = () -> startActivity(new Intent(this, DestinationEditActivity.class));
                    break;
                default:
                    text = "شماره‌ی فرستنده‌ی هیچ بانکی تنظیم نشده، پس هیچ پیامکی خوانده نمی‌شود.";
                    action = "تنظیم بانک‌ها";
                    fix = () -> startActivity(new Intent(this, BanksActivity.class));
            }
            LinearLayout r = Ui.rowLayout(this);
            Ui.pad(r, 20, 10, 20, 10);
            r.setBackgroundColor(Ui.color(this, R.color.redbg));
            TextView t = Ui.text(this, text, 12.5f, R.color.red, Ui.W_BOLD);
            r.addView(t, Ui.weight1());
            TextView a = Ui.text(this, action, 12.5f, R.color.fg, Ui.W_BLACK);
            Ui.pad(a, 12, 6, 0, 6);
            r.addView(a);
            r.setClickable(true);
            r.setOnClickListener(v -> fix.run());
            problemsBox.addView(r);
            problemsBox.addView(Ui.hairline(this, R.color.bg));
        }
    }

    private void askSms() {
        requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS}, REQ_SMS);
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_SMS) {
            boolean granted = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
                // "Don't ask again" (or restricted settings on Android 13+): the user has to allow it in app info.
                Ui.toast(this, "از بخش «دسترسی‌ها» اجازه‌ی پیامک را بدهید");
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } else if (granted && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            }
        }
        load();
    }

    // ---------- test send ----------

    private void testAll() {
        List<Db.Dest> on = new ArrayList<>();
        for (Db.Dest d : dests) if (d.enabled) on.add(d);
        if (on.isEmpty()) {
            Ui.toast(this, "اول یک مقصد اضافه کنید");
            startActivity(new Intent(this, DestinationEditActivity.class));
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("ارسال آزمایشی").setMessage("در حال ارسال به " + Fa.d(on.size()) + " مقصد…")
                .setPositiveButton("بستن", null).show();
        new Thread(() -> {
            StringBuilder s = new StringBuilder();
            for (Db.Dest d : on) {
                Forwarder.Result r = Forwarder.test(this, d);
                s.append(r.ok() ? "✓ " : "✕ ").append(d.name).append(" · ")
                        .append(r.ok() ? Fa.d(r.code) + " · " + Fa.d(r.ms) + " میلی‌ثانیه" : (r.error == null ? "" : r.error)).append("\n");
            }
            runOnUiThread(() -> { if (dlg.isShowing()) dlg.setMessage(s.toString().trim()); load(); });
        }).start();
    }

    // ---------- one message ----------

    private void details(Db.Msg m) {
        io.execute(() -> {
            List<Db.Delivery> dels = Db.get(this).deliveries(m.id);
            runOnUiThread(() -> showDetails(m, dels));
        });
    }

    private void showDetails(Db.Msg m, List<Db.Delivery> dels) {
        LinearLayout v = Ui.col(this);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Ui.pad(v, 24, 20, 24, 8);
        TextView amt = Ui.ltr(Ui.text(this, Ui.amount(this, m.amount), 30, R.color.fg, Ui.W_BLACK));
        amt.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        amt.setTextDirection(View.TEXT_DIRECTION_RTL);
        v.addView(amt);
        v.addView(Ui.text(this, (m.bank == null ? "" : m.bank + " · ") + m.sender, 13, R.color.mid, Ui.W_REGULAR));
        v.addView(Ui.text(this, Fa.full(m.at, tz), 13, R.color.mid, Ui.W_REGULAR));

        TextView sec = Ui.text(this, "ارسال", 11, R.color.mid, Ui.W_BOLD);
        Ui.pad(sec, 0, 18, 0, 4);
        v.addView(sec);
        if (m.skipped) v.addView(Ui.text(this, "ارسال نشد: این پیامک واریز تشخیص داده نشد.", 13.5f, R.color.fg, Ui.W_REGULAR));
        else if (dels.isEmpty()) v.addView(Ui.text(this, "هیچ مقصد فعالی پیامک این بانک را نمی‌گیرد.", 13.5f, R.color.fg, Ui.W_REGULAR));
        for (Db.Delivery d : dels) {
            String st;
            int col = R.color.fg;
            switch (d.status) {
                case Db.SENT: st = "رسید · " + Fa.d(d.code == null ? "" : d.code) + " · " + Fa.time(d.updatedAt, tz); break;
                case Db.FAILED: st = "ناموفق بعد از " + Fa.d(d.attempts) + " تلاش · " + (d.error == null ? "" : Fa.d(d.error)); col = R.color.red; break;
                case Db.SENDING: st = "در حال ارسال…"; break;
                default: st = d.attempts == 0 && d.error == null ? "در صف ارسال" : "تلاش بعدی " + Fa.time(d.nextAt, tz) + (d.error == null ? "" : " · " + Fa.d(d.error)); col = R.color.red;
            }
            LinearLayout r = Ui.rowLayout(this);
            Ui.pad(r, 0, 6, 0, 6);
            r.addView(Ui.text(this, d.dest, 13.5f, R.color.fg, Ui.W_BOLD));
            TextView s = Ui.text(this, st, 12.5f, col, Ui.W_REGULAR);
            Ui.pad(s, 10, 0, 0, 0);
            r.addView(s, Ui.weight1());
            v.addView(r);
        }

        TextView sec2 = Ui.text(this, "متن پیامک", 11, R.color.mid, Ui.W_BOLD);
        Ui.pad(sec2, 0, 18, 0, 4);
        v.addView(sec2);
        TextView body = Ui.text(this, m.body, 13.5f, R.color.fg, Ui.W_REGULAR);
        body.setTextIsSelectable(true);
        body.setLineSpacing(0, 1.25f);
        v.addView(body);

        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        boolean canResend = m.skipped || m.failed > 0;
        AlertDialog.Builder bld = new AlertDialog.Builder(this).setView(sv).setPositiveButton("بستن", null)
                .setNeutralButton("کپی متن", (d, w) -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("sms", m.body));
                    Ui.toast(this, "کپی شد");
                });
        if (canResend) {
            bld.setNegativeButton(m.skipped ? "ارسال دستی" : "ارسال دوباره", (d, w) -> io.execute(() -> {
                Db.get(this).resend(m);
                List<Long> ids = new ArrayList<>();
                ids.add(m.id);
                Forwarder.sendNow(this, ids, 15_000);
            }));
        }
        bld.show();
    }

    // ---------- sticky day header ----------

    private void updateSticky(int first) {
        int idx = first - list.getHeaderViewsCount();
        if (idx < 0 || adapter.items.isEmpty()) {
            sticky.setVisibility(View.GONE);
            return;
        }
        Day day = null;
        for (int i = Math.min(idx, adapter.items.size() - 1); i >= 0; i--) {
            Object o = adapter.items.get(i);
            if (o instanceof Day) { day = (Day) o; break; }
        }
        if (day == null) {
            sticky.setVisibility(View.GONE);
            return;
        }
        bindDay(sticky, day);
        sticky.setVisibility(View.VISIBLE);
    }

    // ---------- list rows ----------

    static final class Day {
        final long start;
        final String name, date;
        int count, bad;

        Day(long start, String name, String date) {
            this.start = start;
            this.name = name;
            this.date = date;
        }
    }

    private LinearLayout dayView(ViewGroup parent) {
        LinearLayout wrap = Ui.col(this);
        wrap.setBackgroundColor(Ui.color(this, R.color.bg));
        wrap.addView(Ui.hairline(this, R.color.faint));
        LinearLayout r = Ui.rowLayout(this);
        Ui.pad(r, 20, 10, 20, 8);
        TextView title = Ui.text(this, "", 13.5f, R.color.fg, Ui.W_BLACK);
        r.addView(title);
        TextView date = Ui.text(this, "", 11.5f, R.color.mid, Ui.W_REGULAR);
        Ui.pad(date, 6, 0, 0, 0);
        r.addView(date, Ui.weight1());
        TextView bad = Ui.text(this, "", 11.5f, R.color.red, Ui.W_BOLD);
        Ui.pad(bad, 0, 0, 8, 0);
        r.addView(bad);
        TextView count = Ui.text(this, "", 11.5f, R.color.mid, Ui.W_REGULAR);
        r.addView(count);
        ImageView chev = Ui.icon(this, R.drawable.ic_fwd, R.color.mid, 14);
        ((LinearLayout.LayoutParams) chev.getLayoutParams()).setMarginStart(Ui.dp(this, 6));
        r.addView(chev);
        wrap.addView(r);
        wrap.setTag(R.id.day_views, new View[]{title, date, bad, count, chev});
        return wrap;
    }

    private void bindDay(LinearLayout v, Day d) {
        View[] vs = (View[]) v.getTag(R.id.day_views);
        ((TextView) vs[0]).setText(d.name != null ? d.name : d.date);
        ((TextView) vs[1]).setText(d.name != null ? d.date : "");
        ((TextView) vs[2]).setText(d.bad > 0 ? Fa.d(d.bad) + " خطا" : "");
        ((TextView) vs[3]).setText(Fa.d(d.count) + " واریز");
        vs[4].setRotation(collapsed.contains(d.start) ? 0 : 90);
        v.setTag(d);
        v.setClickable(true);
    }

    private final class Adapter extends BaseAdapter {
        List<Object> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return i >= 0 && i < items.size() ? items.get(i) : null; }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return items.get(i) instanceof Day ? 0 : 1; }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            Object o = items.get(i);
            if (o instanceof Day) {
                LinearLayout v = cv != null ? (LinearLayout) cv : dayView(parent);
                bindDay(v, (Day) o);
                v.setClickable(false);
                return v;
            }
            LinearLayout v = cv != null ? (LinearLayout) cv : msgView();
            bindMsg(v, (Db.Msg) o);
            return v;
        }
    }

    private LinearLayout msgView() {
        LinearLayout r = Ui.rowLayout(this);
        Ui.pad(r, 20, 10, 20, 10);
        r.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView time = Ui.text(this, "", 13, R.color.fg, Ui.W_BOLD);
        r.addView(time, Ui.lp(Ui.dp(this, 46), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView bank = Ui.ellipsize(Ui.text(this, "", 12.5f, R.color.mid, Ui.W_REGULAR));
        r.addView(bank, Ui.weight1());
        TextView amt = Ui.ltr(Ui.text(this, "", 15, R.color.fg, Ui.W_BOLD));
        Ui.pad(amt, 8, 0, 8, 0);
        r.addView(amt);
        ImageView st = Ui.icon(this, R.drawable.ic_ok, R.color.mid, 16);
        r.addView(st);
        r.setTag(R.id.msg_views, new View[]{time, bank, amt, st});
        return r;
    }

    private void bindMsg(LinearLayout v, Db.Msg m) {
        View[] vs = (View[]) v.getTag(R.id.msg_views);
        TextView time = (TextView) vs[0], bank = (TextView) vs[1], amt = (TextView) vs[2];
        ImageView st = (ImageView) vs[3];
        time.setText(Fa.time(m.at, tz));
        amt.setText(Ui.amount(this, m.amount));
        amt.setAlpha(m.skipped || m.total == 0 ? 0.45f : 1f);
        String b = m.bank == null ? m.sender : m.bank;
        int icon, tint = R.color.mid, bankColor = R.color.mid, bg = R.color.bg;
        if (m.skipped) { b += " · واریز تشخیص داده نشد"; icon = R.drawable.ic_dash; }
        else if (m.total == 0) { b += " · بدون مقصد"; icon = R.drawable.ic_dash; }
        else if (m.failed > 0) { b += " · به " + m.dests + " نرسید"; icon = R.drawable.ic_fail; tint = R.color.red; bankColor = R.color.red; bg = R.color.redbg; }
        else if (m.pending > 0) { b += " · در صف ارسال"; icon = R.drawable.ic_retry; }
        else { b += " → " + m.dests; icon = R.drawable.ic_ok; }
        bank.setText(b);
        bank.setTextColor(Ui.color(this, bankColor));
        st.setImageResource(icon);
        st.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, tint)));
        v.setBackgroundColor(Ui.color(this, bg));
    }
}
