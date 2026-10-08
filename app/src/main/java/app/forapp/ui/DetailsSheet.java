package app.forapp.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Fa;
import app.forapp.core.Forwarder;
import app.forapp.core.SmsParser;

/** One SMS from bottom to top: amount, facts, where it went (with a manual "send now" per destination), and the text. */
final class DetailsSheet {
    private final Activity a;
    private final long msgId;
    private final ExecutorService io;
    private final TimeZone tz;
    private final Dialog dialog;
    private final LinearLayout content;
    private final Set<Long> sending = new HashSet<>();
    private final Runnable refresh = this::load;

    DetailsSheet(Activity a, long msgId, ExecutorService io, TimeZone tz) {
        this.a = a;
        this.msgId = msgId;
        this.io = io;
        this.tz = tz;
        content = Ui.col(a);
        dialog = Ui.sheet(a, content);
        dialog.setOnShowListener(d -> Forwarder.listen(refresh));
        dialog.setOnDismissListener(d -> Forwarder.unlisten(refresh));
    }

    void show() {
        load();
    }

    private void load() {
        io.execute(() -> {
            Db db = Db.get(a);
            Db.Msg m = db.message(msgId);
            List<Db.Delivery> dels = m == null ? new ArrayList<>() : db.deliveries(msgId);
            a.runOnUiThread(() -> {
                if (a.isDestroyed() || m == null) return;
                bind(m, dels);
                if (!dialog.isShowing()) dialog.show();
            });
        });
    }

    // ---------- layout ----------

    private void bind(Db.Msg m, List<Db.Delivery> dels) {
        View scroller = (View) content.getParent();
        int y = scroller.getScrollY(); // a live refresh must not jump back to the top
        scroller.post(() -> scroller.scrollTo(0, y));
        content.removeAllViews();

        // Status line: pill on the start side, "how long ago" on the end side.
        LinearLayout top = Ui.rowLayout(a);
        Ui.pad(top, 0, 12, 0, 0);
        top.addView(statusPill(m));
        View gap = new View(a);
        top.addView(gap, Ui.weight1());
        top.addView(Ui.text(a, Fa.ago(m.at, System.currentTimeMillis()), 12, R.color.mid, Ui.W_REGULAR));
        top.addView(Ui.iconButton(a, R.drawable.ic_close, "بستن", v -> dialog.dismiss()));
        content.addView(top);

        // Amount, with the matching code in red and the unit beside it.
        LinearLayout amtRow = Ui.rowLayout(a);
        amtRow.setGravity(Gravity.BOTTOM);
        Ui.pad(amtRow, 0, 10, 0, 0);
        TextView amt = Ui.text(a, Ui.amount(a, m.amount), 38, R.color.fg, Ui.W_BLACK);
        amt.setTextDirection(View.TEXT_DIRECTION_LTR);
        amt.setIncludeFontPadding(false);
        amtRow.addView(amt);
        if (m.amount != null) {
            TextView unit = Ui.text(a, "toman".equals(m.unit) ? "تومان" : "ریال", 13, R.color.mid, Ui.W_BOLD);
            Ui.pad(unit, 8, 0, 0, 6);
            amtRow.addView(unit);
        }
        content.addView(amtRow);
        if (m.amount != null) {
            TextView code = Ui.text(a, "کد تطبیق  " + Fa.d(SmsParser.code(m.amount)), 12, R.color.red, Ui.W_BOLD);
            Ui.pad(code, 10, 3, 10, 3);
            code.setBackground(Ui.fill(a, R.color.redbg, 99));
            LinearLayout.LayoutParams cp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = Ui.dp(a, 8);
            content.addView(code, cp);
        }

        // Facts.
        LinearLayout facts = card(R.color.line);
        fact(facts, "بانک", m.bank == null ? "نامشخص" : m.bank, false, false);
        fact(facts, "فرستنده", m.sender, true, false);
        fact(facts, "زمان دریافت", Fa.full(m.at, tz), false, false);
        fact(facts, "نوع", m.deposit == null ? "نامشخص" : m.deposit ? "واریز" : "برداشت", false, false);
        fact(facts, "شناسه", m.uid, true, true);
        content.addView(facts, cardLp(16));

        // Deliveries.
        content.addView(section("ارسال به مقصدها", dels.isEmpty() ? null : Fa.d(m.sent) + " از " + Fa.d(dels.size()) + " رسید"));
        if (m.skipped) content.addView(note("ارسال نشد، چون این پیامک واریز تشخیص داده نشد. می‌توانید دستی بفرستید."));
        else if (dels.isEmpty()) content.addView(note("هیچ مقصد فعالی پیامک این بانک را نمی‌گیرد."));
        for (Db.Delivery d : dels) content.addView(delivery(d), cardLp(8));

        // SMS text.
        content.addView(section("متن پیامک", null));
        LinearLayout box = card(R.color.line);
        TextView body = Ui.text(a, m.body, 14, R.color.fg, Ui.W_REGULAR);
        body.setTextIsSelectable(true);
        body.setLineSpacing(0, 1.35f);
        box.addView(body);
        content.addView(box, cardLp(0));

        // Actions.
        LinearLayout actions = Ui.rowLayout(a);
        Ui.pad(actions, 0, 18, 0, 0);
        actions.addView(Ui.button(a, "کپی متن", false, v -> {
            a.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("sms", m.body));
            Ui.toast(a, "کپی شد");
        }), Ui.weight1());
        if (m.skipped || m.failed > 0) {
            View sp = new View(a);
            actions.addView(sp, Ui.lp(Ui.dp(a, 10), 1));
            actions.addView(Ui.button(a, m.skipped ? "ارسال دستی" : "ارسال دوباره همه", true, v -> io.execute(() -> {
                Db.get(a).resend(m);
                List<Long> ids = new ArrayList<>();
                ids.add(m.id);
                Forwarder.sendNow(a, ids, 15_000);
            })), Ui.weight1());
        }
        content.addView(actions);
    }

    private View statusPill(Db.Msg m) {
        String s;
        int fg, bg, icon;
        if (m.skipped) { s = "ارسال نشد"; fg = R.color.mid; bg = R.color.line; icon = R.drawable.ic_dash; }
        else if (m.total == 0) { s = "بدون مقصد"; fg = R.color.mid; bg = R.color.line; icon = R.drawable.ic_dash; }
        else if (m.failed > 0) { s = "ناموفق"; fg = R.color.red; bg = R.color.redbg; icon = R.drawable.ic_fail; }
        else if (m.pending > 0) { s = "در صف ارسال"; fg = R.color.fg; bg = R.color.line; icon = R.drawable.ic_retry; }
        else { s = "رسید"; fg = R.color.bg; bg = R.color.fg; icon = R.drawable.ic_ok; }
        LinearLayout p = Ui.rowLayout(a);
        Ui.pad(p, 10, 4, 12, 4);
        p.setBackground(Ui.fill(a, bg, 99));
        p.addView(Ui.icon(a, icon, fg, 14));
        TextView t = Ui.text(a, s, 12, fg, Ui.W_BOLD);
        Ui.pad(t, 5, 0, 0, 0);
        p.addView(t);
        return p;
    }

    private View delivery(Db.Delivery d) {
        boolean busy = d.status == Db.SENDING || sending.contains(d.id);
        String st, meta;
        int col, icon;
        switch (d.status) {
            case Db.SENT:
                st = "رسید"; col = R.color.fg; icon = R.drawable.ic_ok;
                meta = Fa.time(d.updatedAt, tz);
                break;
            case Db.FAILED:
                st = "ناموفق"; col = R.color.red; icon = R.drawable.ic_fail;
                meta = "بعد از " + Fa.d(d.attempts) + " تلاش · " + Fa.time(d.updatedAt, tz);
                break;
            case Db.SENDING:
                st = "در حال ارسال…"; col = R.color.mid; icon = R.drawable.ic_retry;
                meta = "";
                break;
            default:
                boolean unreachable = d.code == null && d.error != null;
                st = d.attempts == 0 && d.error == null ? "در صف" : unreachable ? "منتظر اتصال" : "تلاش دوباره";
                col = d.error == null ? R.color.mid : R.color.red; icon = R.drawable.ic_retry;
                meta = d.error == null ? "" : unreachable
                        ? "با وصل شدن اینترنت یا فیلترشکن خودکار ارسال می‌شود"
                        : "تلاش " + Fa.d(d.attempts + 1) + " · ساعت " + Fa.time(d.nextAt, tz);
        }
        if (busy) { st = "در حال ارسال…"; col = R.color.mid; }

        LinearLayout c = card(0);
        c.setBackground(Ui.outline(a, d.status == Db.FAILED ? R.color.red : R.color.faint, 14, 1, false));
        LinearLayout head = Ui.rowLayout(a);
        head.addView(Ui.icon(a, icon, col, 18));
        TextView name = Ui.ellipsize(Ui.text(a, d.dest, 14.5f, R.color.fg, Ui.W_BOLD));
        Ui.pad(name, 8, 0, 8, 0);
        head.addView(name, Ui.weight1());
        if (d.code != null) {
            TextView code = Ui.text(a, "HTTP " + Fa.d(d.code), 11, d.code >= 200 && d.code < 300 ? R.color.mid : R.color.red, Ui.W_BOLD);
            Ui.pad(code, 8, 2, 8, 2);
            code.setBackground(Ui.outline(a, R.color.faint, 99, 1, false));
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMarginEnd(Ui.dp(a, 8));
            head.addView(code, p);
        }
        head.addView(Ui.text(a, st, 12.5f, col, Ui.W_BOLD));
        c.addView(head);

        if (!meta.isEmpty()) {
            TextView mt = Ui.text(a, meta, 12, R.color.mid, Ui.W_REGULAR);
            Ui.pad(mt, 26, 4, 0, 0);
            c.addView(mt);
        }
        if (d.error != null && d.status != Db.SENT) {
            TextView err = Ui.text(a, Fa.d(d.error), 12, R.color.red, Ui.W_REGULAR);
            err.setTextIsSelectable(true);
            err.setLineSpacing(0, 1.2f);
            Ui.pad(err, 10, 6, 10, 6);
            err.setBackground(Ui.fill(a, R.color.redbg, 8));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = Ui.dp(a, 8);
            c.addView(err, p);
        }
        if (d.status == Db.PENDING || d.status == Db.FAILED) {
            TextView b = Ui.button(a, busy ? "در حال ارسال…" : "ارسال الان", false, v -> sendNow(d));
            b.setTextSize(13);
            Ui.pad(b, 12, 8, 12, 8);
            b.setEnabled(!busy);
            b.setAlpha(busy ? 0.5f : 1f);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = Ui.dp(a, 10);
            c.addView(b, p);
        }
        return c;
    }

    /** One try right now, whatever the automatic schedule says; the result shows on the card and in a toast. */
    private void sendNow(Db.Delivery d) {
        sending.add(d.id);
        load();
        new Thread(() -> {
            Forwarder.Result r = Forwarder.sendOne(a, d.id);
            a.runOnUiThread(() -> {
                sending.remove(d.id);
                if (r == null) Ui.toast(a, "همین الان در حال ارسال است");
                else if (r.ok()) Ui.toast(a, "رسید · " + Fa.d(r.code) + " · " + Fa.d(r.ms) + " میلی‌ثانیه");
                else Ui.toast(a, "نرسید · " + (r.error == null ? "" : Fa.d(r.error)));
                load();
            });
        }, "forapp-manual").start();
    }

    // ---------- small pieces ----------

    private LinearLayout card(int fillRes) {
        LinearLayout c = Ui.col(a);
        Ui.pad(c, 14, 12, 14, 12);
        if (fillRes != 0) c.setBackground(Ui.fill(a, fillRes, 14));
        return c;
    }

    private LinearLayout.LayoutParams cardLp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(a, topDp);
        return p;
    }

    private void fact(LinearLayout parent, String label, String value, boolean ltr, boolean copy) {
        LinearLayout r = Ui.rowLayout(a);
        Ui.pad(r, 0, 5, 0, 5);
        r.addView(Ui.text(a, label, 12.5f, R.color.mid, Ui.W_REGULAR));
        TextView v = Ui.ellipsize(Ui.text(a, ltr ? value : Fa.d(value), 13.5f, R.color.fg, Ui.W_BOLD));
        v.setGravity(Gravity.END);
        Ui.pad(v, 16, 0, 0, 0);
        if (ltr) {
            v.setTextDirection(View.TEXT_DIRECTION_LTR);
            v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            v.setTextSize(12.5f);
        }
        r.addView(v, Ui.weight1());
        if (copy) {
            r.setOnClickListener(x -> {
                a.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, value));
                Ui.toast(a, label + " کپی شد");
            });
        }
        parent.addView(r);
    }

    private View section(String title, String end) {
        LinearLayout r = Ui.rowLayout(a);
        Ui.pad(r, 2, 24, 2, 8);
        r.addView(Ui.text(a, title, 13, R.color.fg, Ui.W_BLACK), Ui.weight1());
        if (end != null) r.addView(Ui.text(a, end, 12, R.color.mid, Ui.W_REGULAR));
        return r;
    }

    private View note(String s) {
        TextView t = Ui.text(a, s, 13, R.color.mid, Ui.W_REGULAR);
        t.setLineSpacing(0, 1.25f);
        Ui.pad(t, 2, 0, 2, 4);
        return t;
    }
}
