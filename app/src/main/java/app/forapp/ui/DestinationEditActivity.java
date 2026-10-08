package app.forapp.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Fa;
import app.forapp.core.Forwarder;

/** Name, webhook, API key and which banks go to this destination. */
public final class DestinationEditActivity extends BaseActivity {
    private Db.Dest dest;
    private EditText name, url, key;
    private Switch allBanks;
    private final List<CheckBox> boxes = new ArrayList<>();
    private final List<Db.Bank> banks = new ArrayList<>();
    private LinearLayout bankList;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Db db = Db.get(this);
        long id = getIntent().getLongExtra("id", 0);
        dest = id > 0 ? db.dest(id) : null;
        boolean isNew = dest == null;
        if (isNew) {
            dest = new Db.Dest();
            dest.name = "مقصد " + Fa.d(db.dests().size() + 1);
        }
        Set<Long> selected = isNew ? new HashSet<>() : db.destBanks(dest.id);

        LinearLayout end = backHeader(isNew ? "مقصد تازه" : dest.name);
        TextView save = Ui.text(this, "ذخیره", 14, R.color.red, Ui.W_BLACK);
        Ui.pad(save, 12, 8, 12, 8);
        Ui.ripple(save, true);
        save.setOnClickListener(v -> save());
        end.addView(save);

        ScrollView sv = new ScrollView(this);
        LinearLayout f = Ui.col(this);
        sv.addView(f);
        body().addView(sv, Ui.frameMatch());

        name = new EditText(this);
        name.setText(dest.name);
        f.addView(Ui.field(this, "نام مقصد", name, false));

        url = new EditText(this);
        url.setText(dest.url);
        url.setHint("https://example.com/hook");
        f.addView(Ui.field(this, "آدرس وبهوک", url, true));
        f.addView(hint("پیامک به‌صورت JSON با متد POST فرستاده می‌شود."));

        key = new EditText(this);
        key.setText(dest.apiKey);
        LinearLayout keyBox = Ui.field(this, "کلید API", key, true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        f.addView(keyBox);
        TextView show = Ui.text(this, "نمایش کلید", 12, R.color.fg, Ui.W_BOLD);
        Ui.pad(show, 20, 6, 20, 0);
        show.setOnClickListener(v -> {
            boolean hidden = (key.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            key.setInputType(InputType.TYPE_CLASS_TEXT | (hidden ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            key.setTypeface(android.graphics.Typeface.MONOSPACE);
            key.setSelection(key.length());
            show.setText(hidden ? "پنهان کردن کلید" : "نمایش کلید");
        });
        f.addView(show);
        f.addView(hint("در هدر X-API-Key فرستاده می‌شود. هر درخواست یک Idempotency-Key هم دارد تا ارسال دوباره، پرداخت تکراری نسازد."));

        f.addView(Ui.section(this, "بانک‌ها"));
        allBanks = Ui.toggle(this, dest.allBanks);
        allBanks.setOnCheckedChangeListener((v, c) -> refreshBanks());
        f.addView(Ui.hairline(this, R.color.line));
        f.addView(Ui.row(this, "همه‌ی بانک‌ها", "بانک‌هایی که بعداً اضافه می‌کنید هم شامل می‌شوند", allBanks));

        bankList = Ui.col(this);
        f.addView(bankList);
        int fg = Ui.color(this, R.color.fg);
        for (Db.Bank bk : db.banks()) {
            banks.add(bk);
            CheckBox cb = new CheckBox(this);
            cb.setChecked(selected.contains(bk.id));
            cb.setButtonTintList(ColorStateList.valueOf(fg));
            boxes.add(cb);
            String sub = bk.senderList().isEmpty() ? "فرستنده تنظیم نشده" : null;
            LinearLayout r = Ui.row(this, bk.name, sub, cb);
            if (sub != null) ((TextView) ((LinearLayout) r.getChildAt(0)).getChildAt(1)).setTextColor(Ui.color(this, R.color.red));
            r.setOnClickListener(v -> cb.toggle());
            bankList.addView(Ui.hairline(this, R.color.line));
            bankList.addView(r);
        }
        TextView banksLink = Ui.text(this, "تنظیم فرستنده‌ی بانک‌ها", 12.5f, R.color.fg, Ui.W_BOLD);
        Ui.pad(banksLink, 20, 12, 20, 0);
        banksLink.setOnClickListener(v -> startActivity(new Intent(this, BanksActivity.class)));
        f.addView(banksLink);
        refreshBanks();

        LinearLayout acts = Ui.col(this);
        Ui.pad(acts, 20, 24, 20, 32);
        acts.addView(Ui.button(this, "ارسال آزمایشی", true, v -> test()), full());
        if (!isNew) {
            TextView del = Ui.text(this, "حذف این مقصد", 14, R.color.red, Ui.W_BOLD);
            del.setGravity(android.view.Gravity.CENTER);
            Ui.pad(del, 12, 14, 12, 4);
            del.setOnClickListener(v -> Ui.confirm(this, "حذف مقصد", "«" + dest.name + "» حذف می‌شود و پیامک‌های در صف به آن فرستاده نمی‌شوند.", "حذف", () -> {
                Db.get(this).deleteDest(dest.id);
                Forwarder.schedule(this);
                finish();
            }));
            acts.addView(del, full());
        }
        f.addView(acts);
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView hint(String s) {
        TextView t = Ui.text(this, s, 11, R.color.mid, Ui.W_REGULAR);
        Ui.pad(t, 20, 4, 20, 0);
        return t;
    }

    private void refreshBanks() {
        boolean all = allBanks.isChecked();
        bankList.setAlpha(all ? 0.35f : 1f);
        for (CheckBox cb : boxes) cb.setEnabled(!all);
        for (int i = 0; i < bankList.getChildCount(); i++) bankList.getChildAt(i).setEnabled(!all);
    }

    /** Copies the form into a Dest, or explains what is wrong. */
    private Db.Dest read() {
        Db.Dest d = new Db.Dest();
        d.id = dest.id;
        d.enabled = dest.enabled;
        d.name = name.getText().toString().trim();
        d.url = url.getText().toString().trim();
        d.apiKey = key.getText().toString().trim();
        d.allBanks = allBanks.isChecked();
        if (d.name.isEmpty()) d.name = "مقصد";
        if (!d.url.matches("(?i)^https?://[^\\s/]+.*")) {
            url.setError("آدرس باید با https:// شروع شود");
            url.requestFocus();
            return null;
        }
        return d;
    }

    private Set<Long> selectedBanks() {
        Set<Long> s = new HashSet<>();
        for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) s.add(banks.get(i).id);
        return s;
    }

    private void save() {
        Db.Dest d = read();
        if (d == null) return;
        Set<Long> sel = selectedBanks();
        Runnable doSave = () -> {
            Db.get(this).saveDest(d, sel);
            Forwarder.changed();
            finish();
        };
        if (d.url.toLowerCase().startsWith("http://")) {
            Ui.confirm(this, "آدرس بدون رمزنگاری", "با http:// کلید API و متن پیامک‌ها رمزنگاری‌نشده فرستاده می‌شوند. بهتر است از https:// استفاده کنید.",
                    "با همین ذخیره کن", doSave);
        } else if (!d.allBanks && sel.isEmpty()) {
            Ui.confirm(this, "هیچ بانکی انتخاب نشده", "تا یک بانک انتخاب نکنید، پیامکی به این مقصد نمی‌رود.", "با همین ذخیره کن", doSave);
        } else {
            doSave.run();
        }
    }

    private void test() {
        Db.Dest d = read();
        if (d == null) return;
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("ارسال آزمایشی").setMessage("در حال ارسال به " + d.url + " …")
                .setPositiveButton("بستن", null).show();
        new Thread(() -> {
            Forwarder.Result r = Forwarder.test(this, d);
            String msg = r.ok()
                    ? "✓ سرور پاسخ " + Fa.d(r.code) + " داد، در " + Fa.d(r.ms) + " میلی‌ثانیه. اتصال درست است."
                    : "✕ " + (r.error == null ? "ناموفق" : Fa.d(r.error)) + (r.code == 401 || r.code == 403 ? "\nکلید API را بررسی کنید." : "");
            runOnUiThread(() -> { if (dlg.isShowing()) dlg.setMessage(msg); });
        }).start();
    }
}
