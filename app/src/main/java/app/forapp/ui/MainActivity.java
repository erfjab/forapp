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
        TextView name = Ui.text(this, "ForApp", 17, R.color.fg, Ui.W_BLACK);
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

        View gap = new View(this); // breathing room between the chart and the log
        top.addView(gap, Ui.lp(1, Ui.dp(this, 16)));

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
            Widget.refresh(this); // permission or on/off may have changed in settings
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
                    text = "بهینه‌سازی باتری برای ForApp روشن است و ممکن است ارسال‌ها دیر انجام شوند.";
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
        LinearLayout content = Ui.col(this);
        android.app.Dialog dlg = Ui.sheet(this, content);
        runTests(content, on);
        dlg.show();
    }

    /** Sends a test payload to every destination at once; each card fills in as its answer comes back. */
    private void runTests(LinearLayout content, List<Db.Dest> on) {
        content.removeAllViews();
        LinearLayout head = Ui.rowLayout(this);
        Ui.pad(head, 2, 14, 2, 2);
        LinearLayout titles = Ui.col(this);
        titles.addView(Ui.text(this, "ارسال آزمایشی", 20, R.color.fg, Ui.W_BLACK));
        TextView summary = Ui.text(this, "در حال ارسال به " + Fa.d(on.size()) + " مقصد…", 12.5f, R.color.mid, Ui.W_REGULAR);
        titles.addView(summary);
        head.addView(titles, Ui.weight1());
        content.addView(head);
        TextView hint = Ui.text(this, "یک پیامک نمونه با \"test\": true فرستاده می‌شود و در گزارش واریزها ثبت نمی‌شود.", 12, R.color.mid, Ui.W_REGULAR);
        hint.setLineSpacing(0, 1.25f);
        Ui.pad(hint, 2, 6, 2, 6);
        content.addView(hint);

        int[] done = {0, 0}; // answered, ok
        for (Db.Dest d : on) {
            LinearLayout card = Ui.col(this);
            Ui.pad(card, 14, 12, 14, 12);
            card.setBackground(Ui.outline(this, R.color.faint, 14, 1, false));
            LinearLayout r = Ui.rowLayout(this);
            ImageView ic = Ui.icon(this, R.drawable.ic_retry, R.color.mid, 18);
            r.addView(ic);
            TextView name = Ui.ellipsize(Ui.text(this, d.name, 14.5f, R.color.fg, Ui.W_BOLD));
            Ui.pad(name, 8, 0, 8, 0);
            r.addView(name, Ui.weight1());
            TextView st = Ui.text(this, "در حال ارسال…", 12.5f, R.color.mid, Ui.W_BOLD);
            r.addView(st);
            card.addView(r);
            TextView url = Ui.ellipsize(Ui.text(this, d.url, 11.5f, R.color.mid, Ui.W_REGULAR));
            url.setTextDirection(View.TEXT_DIRECTION_LTR);
            url.setTypeface(android.graphics.Typeface.MONOSPACE);
            Ui.pad(url, 26, 4, 0, 0);
            card.addView(url);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = Ui.dp(this, 10);
            content.addView(card, p);

            new Thread(() -> {
                Forwarder.Result res = Forwarder.test(this, d);
                runOnUiThread(() -> {
                    done[0]++;
                    if (res.ok()) done[1]++;
                    int col = res.ok() ? R.color.fg : R.color.red;
                    ic.setImageResource(res.ok() ? R.drawable.ic_ok : R.drawable.ic_fail);
                    ic.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, col)));
                    st.setText(res.ok() ? "رسید" : "نرسید");
                    st.setTextColor(Ui.color(this, col));
                    LinearLayout stats = Ui.rowLayout(this);
                    Ui.pad(stats, 26, 8, 0, 0);
                    if (res.code > 0) stats.addView(pill("HTTP " + Fa.d(res.code), res.ok() ? R.color.mid : R.color.red));
                    stats.addView(pill(Fa.d(res.ms) + " میلی‌ثانیه", R.color.mid));
                    card.addView(stats);
                    if (!res.ok() && res.error != null) {
                        TextView err = Ui.text(this, Fa.d(res.error), 12, R.color.red, Ui.W_REGULAR);
                        err.setTextIsSelectable(true);
                        Ui.pad(err, 10, 6, 10, 6);
                        err.setBackground(Ui.fill(this, R.color.redbg, 8));
                        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        ep.topMargin = Ui.dp(this, 8);
                        card.addView(err, ep);
                    }
                    if (!res.ok()) card.setBackground(Ui.outline(this, R.color.red, 14, 1, false));
                    if (done[0] == on.size()) {
                        summary.setText(Fa.d(done[1]) + " از " + Fa.d(on.size()) + " مقصد پاسخ درست داد");
                        summary.setTextColor(Ui.color(this, done[1] == on.size() ? R.color.mid : R.color.red));
                        TextView again = Ui.button(this, "دوباره امتحان کن", false, v -> runTests(content, on));
                        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        bp.topMargin = Ui.dp(this, 16);
                        content.addView(again, bp);
                        load();
                    }
                });
            }, "forapp-test").start();
        }
    }

    private TextView pill(String s, int colorRes) {
        TextView t = Ui.text(this, s, 11, colorRes, Ui.W_BOLD);
        Ui.pad(t, 8, 2, 8, 2);
        t.setBackground(Ui.outline(this, colorRes == R.color.red ? R.color.red : R.color.faint, 99, 1, false));
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMarginEnd(Ui.dp(this, 6));
        t.setLayoutParams(p);
        return t;
    }

    // ---------- one message ----------

    private void details(Db.Msg m) {
        new DetailsSheet(this, m.id, io, tz).show();
    }

    // ---------- sticky day header ----------

    private void updateSticky(int first) {
        if (sticky == null) return; // setOnScrollListener calls onScroll before sticky exists
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
        String b = Ui.iso(m.bank == null ? m.sender : m.bank);
        int icon, tint = R.color.mid, bankColor = R.color.mid, bg = R.color.bg;
        if (m.skipped) { b += " · واریز تشخیص داده نشد"; icon = R.drawable.ic_dash; }
        else if (m.total == 0) { b += " · بدون مقصد"; icon = R.drawable.ic_dash; }
        else if (m.failed > 0) { b += " · به " + Ui.iso(m.dests) + " نرسید"; icon = R.drawable.ic_fail; tint = R.color.red; bankColor = R.color.red; bg = R.color.redbg; }
        else if (m.pending > 0) { b += " · در صف ارسال"; icon = R.drawable.ic_retry; }
        else { b += " ← " + Ui.iso(m.dests); icon = R.drawable.ic_ok; }
        bank.setText(b);
        bank.setTextColor(Ui.color(this, bankColor));
        st.setImageResource(icon);
        st.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, tint)));
        v.setBackgroundColor(Ui.color(this, bg));
    }
}
