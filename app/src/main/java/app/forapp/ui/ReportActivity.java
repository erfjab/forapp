package app.forapp.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Export;
import app.forapp.core.Fa;
import app.forapp.core.Prefs;
import app.forapp.core.Report;
import app.forapp.core.ShareProvider;
import app.forapp.core.Trace;

/** Deposits of a time range at a glance, and the same data saved or shared as Excel, CSV, JSON or text. */
public final class ReportActivity extends BaseActivity {
    private static final int REQ_SAVE = 21;
    private static final String[] RANGES = {"today", "7", "month", "prev", "30", "custom"};
    private static final String[] RANGE_LABELS = {"امروز", "۷ روز", "این ماه", "ماه قبل", "۳۰ روز", null};
    private static final String[] FORMAT_LABELS = {"Excel", "CSV", "JSON", "متن"};
    private static final String[] FORMAT_SUBS = {"چند برگه، راست‌به‌چپ", "برای شیت و حسابداری", "برای برنامه و اسکریپت", "خواندنی، برای چت"};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final TimeZone tz = TimeZone.getDefault();
    private Prefs p;

    private long from, to; // [from, to), local midnights
    private List<Db.Bank> banks = new ArrayList<>();
    private List<Db.Dest> dests = new ArrayList<>();
    private Report report;
    private long oldest = -1;

    private LinearLayout chips, bankBox, filterBox;
    private TextView rangeLine, heroNum, heroSum, heroBad, exportBtn;
    private ChartView chart;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Prefs.of(this);
        backHeader("گزارش");

        LinearLayout page = Ui.col(this);
        body().addView(page, Ui.frameMatch());
        ScrollView sv = new ScrollView(this);
        LinearLayout list = Ui.col(this);
        Ui.pad(list, 0, 0, 0, 20);
        sv.addView(list);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = Ui.rowLayout(this);
        Ui.pad(chips, 20, 10, 20, 12);
        hs.addView(chips);
        list.addView(hs);

        rangeLine = Ui.text(this, "", 12.5f, R.color.mid, Ui.W_REGULAR);
        Ui.pad(rangeLine, 20, 0, 20, 0);
        list.addView(rangeLine);

        // Same hero as the main screen: the count huge, the rial total beside it.
        LinearLayout hero = Ui.rowLayout(this);
        hero.setGravity(Gravity.BOTTOM);
        Ui.pad(hero, 20, 6, 20, 0);
        heroNum = Ui.text(this, "۰", 96, R.color.fg, Ui.W_BLACK);
        heroNum.setIncludeFontPadding(false);
        heroNum.setLineSpacing(0, 0.85f);
        hero.addView(heroNum, Ui.weight1());
        LinearLayout side = Ui.col(this);
        side.setGravity(Gravity.END);
        heroSum = Ui.text(this, "", 14, R.color.fg, Ui.W_BOLD);
        side.addView(heroSum);
        side.addView(Ui.text(this, "ریال در این بازه", 11.5f, R.color.mid, Ui.W_REGULAR));
        heroBad = Ui.text(this, "", 11.5f, R.color.red, Ui.W_BOLD);
        side.addView(heroBad);
        Ui.pad(side, 0, 0, 0, 10);
        hero.addView(side);
        list.addView(hero);

        TextView lede = Ui.text(this, "واریز در این بازه دریافت شد.", 14, R.color.fg, Ui.W_BOLD);
        Ui.pad(lede, 20, 6, 20, 8);
        list.addView(lede);

        chart = new ChartView(this);
        list.addView(chart);

        list.addView(Ui.section(this, "به تفکیک بانک"));
        bankBox = Ui.col(this);
        list.addView(bankBox);
        list.addView(Ui.section(this, "فیلتر"));
        filterBox = Ui.col(this);
        list.addView(filterBox);

        page.addView(Ui.hairline(this, R.color.faint));
        exportBtn = Ui.button(this, "خروجی گرفتن", true, v -> openExport());
        LinearLayout foot = Ui.col(this);
        Ui.pad(foot, 20, 12, 20, 14);
        foot.addView(exportBtn);
        page.addView(foot);

        resolveRange();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---------- range ----------

    private static long addDays(long ms, int n, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.add(Calendar.DAY_OF_MONTH, n);
        return c.getTimeInMillis();
    }

    private long today() {
        return Fa.dayStart(System.currentTimeMillis(), tz);
    }

    /** First day of the Jalali month {@code back} months before this one. */
    private long monthStart(int back) {
        int[] j = Fa.jalali(System.currentTimeMillis(), tz);
        int y = j[0], m = j[1] - back;
        while (m < 1) { m += 12; y--; }
        return Fa.jalaliDayStart(y, m, 1, tz);
    }

    private void resolveRange() {
        long t = today(), end = addDays(t, 1, tz);
        switch (p.reportRange()) {
            case "today": from = t; to = end; break;
            case "7": from = addDays(t, -6, tz); to = end; break;
            case "30": from = addDays(t, -29, tz); to = end; break;
            case "prev": from = monthStart(1); to = monthStart(0); break;
            case "custom":
                if (p.reportFrom() > 0) {
                    from = Fa.dayStart(p.reportFrom(), tz);
                    to = addDays(Fa.dayStart(Math.min(p.reportTo(), t), tz), 1, tz);
                    break;
                }
                p.reportRange("month");
                // fall through
            default: from = monthStart(0); to = end;
        }
    }

    private String customChipLabel() {
        if (p.reportFrom() <= 0) return "دلخواه…";
        long a = p.reportFrom(), b = Math.min(p.reportTo(), today());
        if (Fa.dayStart(a, tz) == Fa.dayStart(b, tz)) return Fa.date(a, tz, false);
        return Fa.date(a, tz, false) + " تا " + Fa.date(b, tz, false);
    }

    // ---------- data ----------

    private void load() {
        long f = from, t = to;
        int type = p.reportType();
        long bank = p.reportBank(), dest = p.reportDest();
        io.execute(() -> {
            Db db = Db.get(this);
            List<Db.Bank> bs = db.banks();
            List<Db.Dest> ds = db.dests();
            Report r = new Report(f, t, tz, db.reportRows(f, t, type, bank, dest, true, false));
            long old = db.oldestMessageAt();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                banks = bs;
                dests = ds;
                report = r;
                oldest = old;
                render();
            });
        });
    }

    private void render() {
        renderChips();
        Report r = report;
        rangeLine.setText(r.label() + (r.days() > 1 ? " · " + Fa.d(r.days()) + " روز" : ""));
        heroNum.setText(Fa.d(r.deposits));
        heroSum.setText(Fa.group(r.total));
        String bad = r.failed > 0 ? Fa.d(r.failed) + " ناموفق" : "";
        if (r.pending > 0) bad += (bad.isEmpty() ? "" : " · ") + Fa.d(r.pending) + " در صف";
        heroBad.setText(bad);
        heroBad.setVisibility(bad.isEmpty() ? View.GONE : View.VISIBLE);
        if (r.days() == 1) chart.setHours(r.slots, r.badSlots, from == today());
        else chart.setDays(r.slots, r.badSlots, r.slotDays());

        bankBox.removeAllViews();
        if (r.banks.isEmpty()) {
            bankBox.addView(Ui.row(this, "در این بازه واریزی نیست", null, null));
        }
        for (int i = 0; i < r.banks.size(); i++) {
            Report.BankTotal b = r.banks.get(i);
            if (i > 0) bankBox.addView(Ui.hairline(this, R.color.line));
            bankBox.addView(Ui.row(this, Ui.iso(b.bank), Fa.d(b.count) + " واریز",
                    Ui.ltr(Ui.text(this, Fa.group(b.sum), 12.5f, R.color.fg, Ui.W_BOLD))));
        }

        filterBox.removeAllViews();
        filterRow("بانک", null, bankName(), this::pickBank);
        filterRow("نوع پیامک", null, Report.TYPE_LABELS[p.reportType()], this::pickType);
        if (!dests.isEmpty()) filterRow("مقصد", "فقط پیامک‌هایی که به این مقصد رفته‌اند", destName(), this::pickDest);

        int n = r.rows.size() - r.ignored;
        exportBtn.setText(n > 0 ? "خروجی گرفتن · " + Fa.d(n) + " پیامک" : "در این بازه پیامکی نیست");
        exportBtn.setEnabled(n > 0 || r.ignored > 0);
        exportBtn.setAlpha(exportBtn.isEnabled() ? 1f : 0.35f);
    }

    private void renderChips() {
        chips.removeAllViews();
        String cur = p.reportRange();
        for (int i = 0; i < RANGES.length; i++) {
            String key = RANGES[i];
            String label = RANGE_LABELS[i] != null ? RANGE_LABELS[i] : customChipLabel();
            chips.addView(chip(label, key.equals(cur), () -> {
                if (key.equals("custom")) {
                    openCustom();
                    return;
                }
                p.reportRange(key);
                resolveRange();
                load();
            }), chipLp());
        }
    }

    private TextView chip(String label, boolean on, Runnable click) {
        TextView t = Ui.text(this, label, 12, on ? R.color.bg : R.color.mid, Ui.W_BOLD);
        Ui.pad(t, 12, 3, 12, 3);
        t.setBackground(on ? Ui.fill(this, R.color.fg, 99) : Ui.outline(this, R.color.faint, 99, 1, false));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> click.run());
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 6));
        return lp;
    }

    // ---------- filters ----------

    private void filterRow(String title, String sub, String value, Runnable click) {
        LinearLayout r = Ui.row(this, title, sub, Ui.value(this, value, R.color.mid));
        Ui.ripple(r, false);
        r.setOnClickListener(v -> click.run());
        if (filterBox.getChildCount() > 0) filterBox.addView(Ui.hairline(this, R.color.line));
        filterBox.addView(r);
    }

    private String bankName() {
        for (Db.Bank b : banks) if (b.id == p.reportBank()) return b.name;
        return "همه";
    }

    private String destName() {
        for (Db.Dest d : dests) if (d.id == p.reportDest()) return d.name;
        return "همه";
    }

    private void pickBank() {
        String[] names = new String[banks.size() + 1];
        names[0] = "همه";
        int sel = 0;
        for (int i = 0; i < banks.size(); i++) {
            names[i + 1] = banks.get(i).name;
            if (banks.get(i).id == p.reportBank()) sel = i + 1;
        }
        choose("بانک", names, sel, w -> p.reportBank(w == 0 ? 0 : banks.get(w - 1).id));
    }

    private void pickDest() {
        String[] names = new String[dests.size() + 1];
        names[0] = "همه";
        int sel = 0;
        for (int i = 0; i < dests.size(); i++) {
            names[i + 1] = dests.get(i).name;
            if (dests.get(i).id == p.reportDest()) sel = i + 1;
        }
        choose("مقصد", names, sel, w -> p.reportDest(w == 0 ? 0 : dests.get(w - 1).id));
    }

    private void pickType() {
        choose("نوع پیامک", Report.TYPE_LABELS, p.reportType(), w -> p.reportType(w));
    }

    private interface IntSet { void set(int v); }

    private void choose(String title, String[] labels, int sel, IntSet set) {
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, sel, (d, w) -> {
            set.set(w);
            d.dismiss();
            load();
        }).show();
    }

    // ---------- custom range ----------

    private void openCustom() {
        long[] sel = {from, addDays(to, -1, tz)}; // first and last day
        LinearLayout c = Ui.col(this);
        Dialog dlg = Ui.sheet(this, c);
        c.addView(sheetTitle("بازه‌ی دلخواه", "روز اول و روز آخر هر دو حساب می‌شوند"));

        LinearLayout fields = Ui.rowLayout(this);
        Ui.pad(fields, 2, 10, 2, 0);
        TextView fromTv = dateValue(), toTv = dateValue();
        TextView hint = Ui.text(this, "", 12, R.color.mid, Ui.W_REGULAR);
        Ui.pad(hint, 2, 10, 2, 0);
        TextView apply = Ui.button(this, "نمایش گزارش", true, null);
        Runnable refresh = () -> {
            fromTv.setText(Fa.date(sel[0], tz, true));
            toTv.setText(Fa.date(sel[1], tz, true));
            boolean bad = sel[0] > sel[1];
            hint.setTextColor(Ui.color(this, bad ? R.color.red : R.color.mid));
            hint.setTypeface(Ui.font(this, bad ? Ui.W_BOLD : Ui.W_REGULAR));
            apply.setEnabled(!bad);
            apply.setAlpha(bad ? 0.35f : 1f);
            if (bad) {
                hint.setText("«از» بعد از «تا» است");
                return;
            }
            long a = sel[0], b = addDays(sel[1], 1, tz);
            int type = p.reportType();
            long bank = p.reportBank(), dest = p.reportDest();
            hint.setText(Fa.d(new Report(a, b, tz, new ArrayList<>()).days()) + " روز");
            io.execute(() -> {
                Report r = new Report(a, b, tz, Db.get(this).reportRows(a, b, type, bank, dest, false, false));
                runOnUiThread(() -> {
                    if (sel[0] == a && addDays(sel[1], 1, tz) == b)
                        hint.setText(Fa.d(r.days()) + " روز · " + Fa.d(r.deposits) + " واریز · " + Fa.group(r.total) + " ریال");
                });
            });
        };
        LinearLayout.LayoutParams lp = Ui.weight1();
        fields.addView(dateField("از", fromTv, () -> JalaliPicker.show(this, "از تاریخ", sel[0], System.currentTimeMillis(), tz, ms -> {
            sel[0] = ms;
            refresh.run();
        })), lp);
        LinearLayout.LayoutParams lp2 = Ui.weight1();
        lp2.setMarginStart(Ui.dp(this, 18));
        fields.addView(dateField("تا", toTv, () -> JalaliPicker.show(this, "تا تاریخ", sel[1], System.currentTimeMillis(), tz, ms -> {
            sel[1] = ms;
            refresh.run();
        })), lp2);
        c.addView(fields);
        c.addView(hint);

        TextView note = Ui.text(this, "لاگ " + Fa.d(p.retentionDays()) + " روز نگه داشته می‌شود"
                + (oldest > 0 ? "؛ قدیمی‌ترین پیامک: " + Fa.date(oldest, tz, true) + "." : "."), 11.5f, R.color.mid, Ui.W_REGULAR);
        note.setLineSpacing(0, 1.2f);
        Ui.pad(note, 2, 4, 2, 0);
        c.addView(note);

        c.addView(sheetSection("میان‌بر"));
        LinearLayout quick = Ui.rowLayout(this);
        Ui.pad(quick, 2, 0, 2, 0);
        long t = today();
        quick.addView(chip("کل لاگ", false, () -> {
            sel[0] = oldest > 0 ? Fa.dayStart(oldest, tz) : addDays(t, -(p.retentionDays() - 1), tz);
            sel[1] = t;
            refresh.run();
        }), chipLp());
        for (int back = 2; back >= 0; back--) {
            long ms = monthStart(back), end = back == 0 ? t : addDays(monthStart(back - 1), -1, tz);
            quick.addView(chip(Fa.MONTHS[Fa.jalali(ms, tz)[1] - 1], false, () -> {
                sel[0] = ms;
                sel[1] = end;
                refresh.run();
            }), chipLp());
        }
        c.addView(quick);

        apply.setOnClickListener(v -> {
            if (sel[0] > sel[1]) return;
            p.reportCustom(sel[0], sel[1]);
            p.reportRange("custom");
            resolveRange();
            load();
            dlg.dismiss();
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(this, 22);
        c.addView(apply, ap);
        refresh.run();
        dlg.show();
    }

    private TextView dateValue() {
        TextView t = Ui.text(this, "", 15, R.color.fg, Ui.W_BOLD);
        Ui.pad(t, 8, 0, 0, 0);
        Ui.ellipsize(t);
        return t;
    }

    /** Label, calendar icon and date over a 1.5dp line, like the hours fields of a destination's filters. */
    private View dateField(String title, TextView value, Runnable click) {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 0, 4, 0, 0);
        box.addView(Ui.text(this, title, 11, R.color.mid, Ui.W_BOLD));
        LinearLayout r = Ui.rowLayout(this);
        Ui.pad(r, 4, 10, 4, 8);
        r.addView(Ui.icon(this, R.drawable.ic_calendar, R.color.fg, 16));
        r.addView(value, Ui.weight1());
        box.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(Ui.color(this, R.color.fg));
        box.addView(line, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1.5f)));
        Ui.ripple(box, false);
        box.setOnClickListener(v -> click.run());
        return box;
    }

    // ---------- export ----------

    /** The format picked in the sheet; also saved, since writing reads it from Prefs. */
    private String fmt;

    private void openExport() {
        if (report == null) return;
        fmt = p.reportFormat();

        LinearLayout c = Ui.col(this);
        Dialog dlg = Ui.sheet(this, c);
        TextView sub = Ui.text(this, "", 12.5f, R.color.mid, Ui.W_REGULAR);
        LinearLayout head = sheetTitle("خروجی گرفتن", null);
        head.addView(sub);
        c.addView(head);

        c.addView(sheetSection("فرمت"));
        TextView fname = Ui.text(this, "", 11.5f, R.color.mid, Ui.W_REGULAR);
        fname.setTypeface(Typeface.MONOSPACE);
        fname.setTextDirection(View.TEXT_DIRECTION_LTR);
        fname.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        Ui.pad(fname, 2, 14, 2, 12);
        List<LinearLayout> cards = new ArrayList<>();
        Runnable refresh = () -> {
            for (int i = 0; i < cards.size(); i++) {
                boolean on = Export.FORMATS[i].equals(fmt);
                cards.get(i).setBackground(on ? Ui.outline(this, R.color.fg, 14, 1.5f, false) : Ui.outline(this, R.color.faint, 14, 1, false));
            }
            fname.setText(report.fileName(fmt));
            int n = report.rows.size() - (p.reportOption("ignored", false) ? 0 : report.ignored);
            sub.setText(Fa.d(n) + " پیامک · " + report.label());
        };
        for (int row = 0; row < 2; row++) {
            LinearLayout line = Ui.rowLayout(this);
            for (int k = 0; k < 2; k++) {
                int i = row * 2 + k;
                LinearLayout card = Ui.col(this);
                Ui.pad(card, 14, 12, 14, 12);
                card.addView(Ui.text(this, FORMAT_LABELS[i], 14.5f, R.color.fg, Ui.W_BOLD));
                card.addView(Ui.text(this, FORMAT_SUBS[i], 11.5f, R.color.mid, Ui.W_REGULAR));
                card.setClickable(true);
                card.setFocusable(true);
                card.setOnClickListener(v -> {
                    fmt = Export.FORMATS[i];
                    p.reportFormat(fmt);
                    refresh.run();
                });
                cards.add(card);
                LinearLayout.LayoutParams lp = Ui.weight1();
                if (k > 0) lp.setMarginStart(Ui.dp(this, 10));
                line.addView(card, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row > 0) lp.topMargin = Ui.dp(this, 10);
            c.addView(line, lp);
        }

        c.addView(sheetSection("در فایل باشد"));
        c.addView(option("خلاصه و تفکیک بانک", null, "summary", true, refresh));
        c.addView(option("جزئیات ارسال به مقصدها", "وضعیت، کد پاسخ و خطای هر مقصد", "deliveries", false, refresh));
        c.addView(option("پیامک‌های نادیده", "در جمع حساب نمی‌شوند، فقط در فهرست می‌آیند", "ignored", false, refresh));
        c.addView(option("متن کامل پیامک", "ممکن است موجودی و شماره‌کارت داشته باشد", "body", false, refresh));
        c.addView(fname);

        LinearLayout btns = Ui.rowLayout(this);
        btns.addView(Ui.button(this, "ذخیره در فایل", true, v -> {
            dlg.dismiss();
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(Export.mime(fmt)).putExtra(Intent.EXTRA_TITLE, report.fileName(fmt));
            startActivityForResult(i, REQ_SAVE);
        }), Ui.weight1());
        LinearLayout.LayoutParams lp = Ui.weight1();
        lp.setMarginStart(Ui.dp(this, 10));
        btns.addView(Ui.button(this, "اشتراک‌گذاری", false, v -> {
            dlg.dismiss();
            share();
        }), lp);
        c.addView(btns);
        refresh.run();
        dlg.show();
    }

    private View option(String title, String sub, String key, boolean def, Runnable refresh) {
        Switch s = Ui.toggle(this, p.reportOption(key, def));
        s.setOnCheckedChangeListener((v, c) -> {
            p.setReportOption(key, c);
            refresh.run();
        });
        LinearLayout r = Ui.row(this, title, sub, s);
        Ui.pad(r, 2, 10, 2, 10);
        return r;
    }

    private LinearLayout sheetTitle(String title, String sub) {
        LinearLayout t = Ui.col(this);
        Ui.pad(t, 2, 14, 2, 2);
        t.addView(Ui.text(this, title, 20, R.color.fg, Ui.W_BLACK));
        if (sub != null) t.addView(Ui.text(this, sub, 12.5f, R.color.mid, Ui.W_REGULAR));
        return t;
    }

    private TextView sheetSection(String s) {
        TextView t = Ui.text(this, s, 11, R.color.mid, Ui.W_BOLD);
        Ui.pad(t, 2, 18, 2, 8);
        return t;
    }

    /**
     * Reads the rows again with what the file needs (deliveries, ignored SMS) and writes the chosen format.
     * Everything comes from Prefs and the database, so it also works after the activity was recreated
     * while the system file picker was open.
     */
    private void writeExport(OutputStream out) throws Exception {
        Db db = Db.get(this);
        String fmt = p.reportFormat();
        boolean ignored = p.reportOption("ignored", false), deliveries = p.reportOption("deliveries", false);
        int typeIdx = p.reportType();
        long bankId = p.reportBank(), destId = p.reportDest();
        Report r = new Report(from, to, tz, db.reportRows(from, to, typeIdx, bankId, destId, ignored, deliveries));
        Export.Options o = new Export.Options();
        o.summary = p.reportOption("summary", true);
        o.deliveries = deliveries;
        o.body = p.reportOption("body", false);
        String bank = "همه", dest = "همه";
        for (Db.Bank b : db.banks()) if (b.id == bankId) bank = b.name;
        List<Db.Dest> ds = db.dests();
        for (Db.Dest d : ds) if (d.id == destId) dest = d.name;
        o.filterText = "بانک: " + bank + " · نوع: " + Report.TYPE_LABELS[typeIdx] + (ds.isEmpty() ? "" : " · مقصد: " + dest);
        o.filters.put("bank", bankId == 0 ? "all" : bank);
        o.filters.put("type", new String[]{"deposit", "deposit_withdrawal", "all"}[typeIdx]);
        o.filters.put("destination", destId == 0 ? "all" : dest);
        o.filters.put("include_ignored", ignored);
        Export.write(fmt, r, o, System.currentTimeMillis(), out);
        out.flush();
        Trace.i("REPORT", "exported " + r.rows.size() + " rows as " + fmt);
    }

    private void share() {
        String fmt = p.reportFormat(), name = report.fileName(fmt), mime = Export.mime(fmt);
        io.execute(() -> {
            try {
                File f = new File(ShareProvider.dir(this), name);
                try (OutputStream out = new FileOutputStream(f)) {
                    writeExport(out);
                }
                Uri uri = ShareProvider.uri(this, f);
                Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri(name, uri));
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "فرستادن گزارش")));
            } catch (Exception e) {
                runOnUiThread(() -> Ui.toast(this, "ساخته نشد: " + e.getMessage()));
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAVE || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        io.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                writeExport(out);
                runOnUiThread(() -> Ui.toast(this, "فایل ذخیره شد"));
            } catch (Exception e) {
                runOnUiThread(() -> Ui.toast(this, "ذخیره نشد: " + e.getMessage()));
            }
        });
    }
}
