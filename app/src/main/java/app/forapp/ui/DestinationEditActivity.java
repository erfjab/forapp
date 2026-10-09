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
        f.addView(hint("متد، هدرها و بدنه‌ی درخواست را پایین همین صفحه در بخش «درخواست» تنظیم کنید."));

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
        f.addView(hint("در هدرها با {{api_key}} فرستاده می‌شود (پیش‌فرض: X-API-Key). Idempotency-Key هم کمک می‌کند ارسال دوباره، پرداخت تکراری نسازد."));

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

        buildRequest(f);

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

    // ---------- request ----------

    private String method, type;
    private EditText headersEd, bodyEd, lastEd;
    private LinearLayout methodChips, typeChips, bodyBox;
    private TextView preview, previewErr;

    /** Method, body type, headers and body template, with variables to insert and a live preview of what is sent. */
    private void buildRequest(LinearLayout f) {
        method = dest.method();
        type = dest.bodyType();
        f.addView(Ui.section(this, "درخواست"));
        f.addView(hint("هر مقصد درخواست خودش را دارد. متغیرها مثل {{amount}} موقع ارسال با مقدار واقعی پر می‌شوند و برای JSON خودشان در \"\" قرار می‌گیرند."));

        f.addView(label("متد"));
        methodChips = chipRow(f);
        f.addView(label("بدنه"));
        typeChips = chipRow(f);
        renderChips();

        headersEd = code(dest.headers(), 3);
        f.addView(label("هدرها · هر خط Name: value"));
        f.addView(box(headersEd));

        bodyBox = Ui.col(this);
        bodyEd = code(dest.bodyTpl(), 8);
        bodyBox.addView(label("قالب بدنه"));
        bodyBox.addView(box(bodyEd));
        f.addView(bodyBox);

        f.addView(label("متغیرها · بزنید تا در جای مکان‌نما اضافه شود"));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout vars = Ui.rowLayout(this);
        Ui.pad(vars, 20, 4, 20, 4);
        for (String[] v : app.forapp.core.Request.VARS) {
            TextView t = Ui.text(this, "{{" + v[0] + "}}", 12, R.color.fg, Ui.W_BOLD);
            t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            Ui.pad(t, 10, 5, 10, 5);
            t.setBackground(Ui.outline(this, R.color.faint, 99, 1, false));
            t.setOnClickListener(x -> insert("{{" + v[0] + "}}"));
            t.setOnLongClickListener(x -> { Ui.toast(this, v[1]); return true; });
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMarginEnd(Ui.dp(this, 6));
            vars.addView(t, p);
        }
        hs.addView(vars);
        f.addView(hs);

        TextView reset = Ui.text(this, "بازگشت به درخواست پیش‌فرض", 12.5f, R.color.fg, Ui.W_BOLD);
        Ui.pad(reset, 20, 10, 20, 0);
        reset.setOnClickListener(v -> {
            method = "POST";
            type = app.forapp.core.Request.JSON;
            headersEd.setText(app.forapp.core.Request.DEFAULT_HEADERS);
            bodyEd.setText(app.forapp.core.Request.defaultBody(type));
            renderChips();
        });
        f.addView(reset);

        f.addView(label("پیش‌نمایش · با یک پیامک نمونه"));
        LinearLayout pv = Ui.col(this);
        Ui.pad(pv, 14, 12, 14, 12);
        pv.setBackground(Ui.fill(this, R.color.line, 12));
        preview = Ui.text(this, "", 11.5f, R.color.fg, Ui.W_REGULAR);
        preview.setTypeface(android.graphics.Typeface.MONOSPACE);
        preview.setTextDirection(View.TEXT_DIRECTION_LTR);
        preview.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        preview.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        preview.setTextIsSelectable(true);
        pv.addView(preview);
        previewErr = Ui.text(this, "", 12, R.color.red, Ui.W_BOLD);
        Ui.pad(previewErr, 0, 8, 0, 0);
        pv.addView(previewErr);
        LinearLayout.LayoutParams pp = full();
        pp.setMargins(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        f.addView(pv, pp);

        android.text.TextWatcher w = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { updatePreview(); }
        };
        for (EditText e : new EditText[]{headersEd, bodyEd, url, key}) e.addTextChangedListener(w);
        updatePreview();
    }

    private LinearLayout chipRow(LinearLayout f) {
        LinearLayout r = Ui.rowLayout(this);
        Ui.pad(r, 20, 4, 20, 4);
        f.addView(r);
        return r;
    }

    private void renderChips() {
        methodChips.removeAllViews();
        for (String m : app.forapp.core.Request.METHODS) chip(methodChips, m, m.equals(method), () -> {
            method = m;
            renderChips();
        });
        typeChips.removeAllViews();
        String[] names = {"JSON", "Form", "متن", "بدون بدنه"};
        for (int i = 0; i < names.length; i++) {
            String t = app.forapp.core.Request.TYPES[i];
            chip(typeChips, names[i], t.equals(type), () -> {
                // a template still at the old type's default follows the new type
                if (bodyEd.getText().toString().trim().isEmpty()
                        || bodyEd.getText().toString().equals(app.forapp.core.Request.defaultBody(type))) {
                    bodyEd.setText(app.forapp.core.Request.defaultBody(t));
                }
                type = t;
                renderChips();
            });
        }
        boolean hasBody = !"GET".equals(method) && !app.forapp.core.Request.NONE.equals(type);
        if (bodyBox != null) bodyBox.setVisibility(hasBody ? View.VISIBLE : View.GONE);
        typeChips.setAlpha("GET".equals(method) ? 0.35f : 1f);
        if (preview != null) updatePreview();
    }

    private void chip(LinearLayout row, String label, boolean on, Runnable click) {
        TextView t = Ui.text(this, label, 12.5f, on ? R.color.bg : R.color.fg, Ui.W_BOLD);
        Ui.pad(t, 14, 5, 14, 5);
        t.setBackground(on ? Ui.fill(this, R.color.fg, 99) : Ui.outline(this, R.color.faint, 99, 1, false));
        t.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMarginEnd(Ui.dp(this, 6));
        row.addView(t, p);
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 11, R.color.mid, Ui.W_BOLD);
        Ui.pad(t, 20, 14, 20, 4);
        return t;
    }

    /** A left-to-right, multi-line code editor. */
    private EditText code(String text, int lines) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setTypeface(android.graphics.Typeface.MONOSPACE);
        e.setTextSize(12.5f);
        e.setTextColor(Ui.color(this, R.color.fg));
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // code reads left to right, inside a right-to-left screen
        e.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        e.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setMinLines(lines);
        e.setHorizontallyScrolling(false);
        e.setBackground(null);
        e.setOnFocusChangeListener((v, has) -> { if (has) lastEd = e; });
        return e;
    }

    private View box(EditText e) {
        LinearLayout b = Ui.col(this);
        Ui.pad(b, 10, 4, 10, 4);
        b.setBackground(Ui.outline(this, R.color.faint, 12, 1, false));
        b.addView(e, full());
        LinearLayout.LayoutParams p = full();
        p.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void insert(String s) {
        EditText e = lastEd != null ? lastEd : bodyEd;
        int at = Math.max(0, e.getSelectionStart());
        e.getText().insert(at, s);
        e.requestFocus();
    }

    private void updatePreview() {
        Db.Dest d = new Db.Dest();
        d.url = url.getText().toString().trim();
        d.apiKey = key.getText().toString().trim();
        readRequest(d);
        app.forapp.core.Request q = Forwarder.request(d, app.forapp.core.Request.sample(false), 1, false);
        preview.setText(q.describe(d.apiKey, false));
        String err = q.jsonError();
        previewErr.setText(err == null ? "" : "JSON درست نیست: " + err);
        previewErr.setVisibility(err == null ? View.GONE : View.VISIBLE);
    }

    /** Stores only what differs from the default, so an untouched destination keeps following the default. */
    private void readRequest(Db.Dest d) {
        String h = headersEd.getText().toString().trim(), b = bodyEd.getText().toString();
        d.method = "POST".equals(method) ? null : method;
        d.bodyType = app.forapp.core.Request.JSON.equals(type) ? null : type;
        d.headers = h.equals(app.forapp.core.Request.DEFAULT_HEADERS) ? null : h;
        d.bodyTpl = b.equals(app.forapp.core.Request.defaultBody(type)) ? null : b;
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
        readRequest(d);
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
        String jsonErr = Forwarder.request(d, app.forapp.core.Request.sample(false), 1, false).jsonError();
        if (jsonErr != null) {
            Ui.confirm(this, "بدنه JSON درست نیست", "سرور احتمالاً این درخواست را رد می‌کند:\n" + jsonErr, "با همین ذخیره کن", doSave);
        } else if (d.url.toLowerCase().startsWith("http://")) {
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
