package app.forapp.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Fa;

/** Every webhook the SMS can go to. One SMS goes to all destinations that take its bank. */
public final class DestinationsActivity extends BaseActivity {
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout end = backHeader("مقصدها");
        end.addView(Ui.iconButton(this, R.drawable.ic_plus, "افزودن مقصد", v -> edit(0)));
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
        Db db = Db.get(this);
        List<Db.Dest> ds = db.dests();
        Map<Long, String> bankNames = new HashMap<>();
        for (Db.Bank bk : db.banks()) bankNames.put(bk.id, bk.name);
        int active = 0;
        for (Db.Dest d : ds) if (d.enabled) active++;

        LinearLayout sum = Ui.rowLayout(this);
        sum.setGravity(Gravity.BOTTOM);
        Ui.pad(sum, 20, 16, 20, 14);
        TextView n = Ui.text(this, Fa.d(active), 72, R.color.fg, Ui.W_BLACK);
        n.setIncludeFontPadding(false);
        sum.addView(n);
        LinearLayout st = Ui.col(this);
        Ui.pad(st, 12, 0, 0, 8);
        st.addView(Ui.text(this, "مقصد فعال", 14, R.color.fg, Ui.W_BOLD));
        st.addView(Ui.text(this, "هر پیامک به همه‌ی مقصدهایی می‌رود که بانکش را دارند", 11.5f, R.color.mid, Ui.W_REGULAR));
        sum.addView(st, Ui.weight1());
        list.addView(sum);

        long today = Fa.dayStart(System.currentTimeMillis(), TimeZone.getDefault());
        for (Db.Dest d : ds) {
            list.addView(Ui.hairline(this, R.color.faint));
            LinearLayout box = Ui.col(this);
            Ui.pad(box, 20, 14, 20, 14);
            Ui.ripple(box, false);
            box.setOnClickListener(v -> edit(d.id));

            LinearLayout top = Ui.rowLayout(this);
            View dot = new View(this);
            dot.setBackground(d.enabled ? Ui.fill(this, R.color.fg, 99) : Ui.outline(this, R.color.mid, 99, 1.5f, false));
            top.addView(dot, Ui.lp(Ui.dp(this, 7), Ui.dp(this, 7)));
            TextView name = Ui.ellipsize(Ui.text(this, d.name, 15, R.color.fg, Ui.W_BLACK));
            Ui.pad(name, 8, 0, 8, 0);
            top.addView(name, Ui.weight1());
            Switch sw = Ui.toggle(this, d.enabled);
            sw.setOnCheckedChangeListener((v, c) -> {
                Db.get(this).setDestEnabled(d.id, c);
                build();
            });
            top.addView(sw);
            box.addView(top);

            TextView url = Ui.ellipsize(Ui.text(this, d.target(), 11.5f, R.color.mid, Ui.W_REGULAR));
            url.setTypeface(android.graphics.Typeface.MONOSPACE);
            url.setTextDirection(View.TEXT_DIRECTION_LTR);
            url.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            Ui.pad(url, 0, 4, 0, 4);
            box.addView(url);

            String banks;
            boolean none = false;
            if (d.allBanks) banks = "همه‌ی بانک‌ها";
            else {
                Set<Long> ids = db.destBanks(d.id);
                StringBuilder s = new StringBuilder();
                for (Long id : ids) if (bankNames.containsKey(id)) s.append(s.length() > 0 ? "، " : "").append(bankNames.get(id));
                none = s.length() == 0;
                banks = none ? "هیچ بانکی انتخاب نشده" : s.toString();
            }
            box.addView(Ui.text(this, banks, 12, none ? R.color.red : R.color.fg, Ui.W_BOLD));

            LinearLayout stat = Ui.rowLayout(this);
            Ui.pad(stat, 0, 6, 0, 0);
            int[] s = db.destStats(d.id, today);
            stat.addView(Ui.text(this, d.enabled ? "امروز " + Fa.d(s[0]) + " واریز" + (s[1] > 0 ? " · " + Fa.d(s[1]) + " ناموفق" : "") : "غیرفعال",
                    11.5f, s[1] > 0 ? R.color.red : R.color.mid, Ui.W_REGULAR), Ui.weight1());
            if (d.lastCode != null) {
                boolean ok = d.lastCode >= 200 && d.lastCode < 300;
                stat.addView(Ui.text(this, "آخرین پاسخ " + Fa.d(d.lastCode) + " · " + Fa.d(d.lastMs) + " میلی‌ثانیه",
                        11.5f, ok ? R.color.mid : R.color.red, ok ? Ui.W_REGULAR : Ui.W_BOLD));
            }
            box.addView(stat);
            list.addView(box);
        }
        list.addView(Ui.hairline(this, R.color.faint));

        TextView add = Ui.text(this, "+  افزودن مقصد", 13.5f, R.color.mid, Ui.W_BOLD);
        add.setGravity(Gravity.CENTER);
        Ui.pad(add, 12, 14, 12, 14);
        add.setBackground(Ui.outline(this, R.color.faint, 0, 1.5f, true));
        add.setClickable(true);
        add.setOnClickListener(v -> edit(0));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 32));
        list.addView(add, p);
    }

    private void edit(long id) {
        startActivity(new Intent(this, DestinationEditActivity.class).putExtra("id", id));
    }
}
