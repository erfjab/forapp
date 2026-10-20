package app.forapp.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Html;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Fa;
import app.forapp.core.Forwarder;
import app.forapp.core.Prefs;
import app.forapp.core.Proxies;
import app.forapp.core.Request;
import app.forapp.core.Telegram;

/** A webhook (address, API key, request) or a Telegram chat (bot, chat ID, message), and which banks go to it. */
public final class DestinationEditActivity extends BaseActivity {
    private Db.Dest dest;
    private boolean tg;
    private EditText name, url, key;
    private Switch allBanks;
    private final List<CheckBox> boxes = new ArrayList<>();
    private final List<Db.Bank> banks = new ArrayList<>();
    private LinearLayout bankList, kindChips, webhookTop, webhookBottom, telegramTop, telegramBottom;

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
        tg = dest.telegram();
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

        f.addView(label("نوع"));
        kindChips = chipRow(f);

        webhookTop = Ui.col(this);
        telegramTop = Ui.col(this);
        f.addView(webhookTop);
        f.addView(telegramTop);
        buildWebhookTop(webhookTop);
        buildTelegramTop(telegramTop);
        buildProxies(f, isNew ? new ArrayList<>() : db.proxies(dest.id));

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

        webhookBottom = Ui.col(this);
        telegramBottom = Ui.col(this);
        f.addView(webhookBottom);
        f.addView(telegramBottom);
        buildRequest(webhookBottom);
        buildTelegramBottom(telegramBottom);

        LinearLayout acts = Ui.col(this);
        Ui.pad(acts, 20, 24, 20, 32);
        acts.addView(Ui.button(this, "ارسال آزمایشی", true, v -> test()), full());
        if (!isNew) {
            TextView del = Ui.text(this, "حذف این مقصد", 14, R.color.red, Ui.W_BOLD);
            del.setGravity(Gravity.CENTER);
            Ui.pad(del, 12, 14, 12, 4);
            del.setOnClickListener(v -> Ui.confirm(this, "حذف مقصد", "«" + dest.name + "» حذف می‌شود و پیامک‌های در صف به آن فرستاده نمی‌شوند.", "حذف", () -> {
                Db.get(this).deleteDest(dest.id);
                Forwarder.schedule(this);
                finish();
            }));
            acts.addView(del, full());
        }
        f.addView(acts);
        renderKind();
    }

    private void renderKind() {
        kindChips.removeAllViews();
        chip(kindChips, "وبهوک", !tg, () -> { tg = false; renderKind(); });
        chip(kindChips, "تلگرام", tg, () -> { tg = true; renderKind(); });
        for (View v : new View[]{webhookTop, webhookBottom}) v.setVisibility(tg ? View.GONE : View.VISIBLE);
        for (View v : new View[]{telegramTop, telegramBottom}) v.setVisibility(tg ? View.VISIBLE : View.GONE);
        if (tg) updateTgPreview();
        else updatePreview();
    }

    // ---------- webhook ----------

    private void buildWebhookTop(LinearLayout f) {
        url = new EditText(this);
        url.setText(tg ? "" : dest.url);
        url.setHint("https://example.com/hook");
        f.addView(Ui.field(this, "آدرس وبهوک", url, true));
        f.addView(hint("متد، هدرها و بدنه‌ی درخواست را پایین همین صفحه در بخش «درخواست» تنظیم کنید."));

        key = secret(f, "کلید API", tg ? "" : dest.apiKey, "نمایش کلید", "پنهان کردن کلید");
        f.addView(hint("در هدرها با {{api_key}} فرستاده می‌شود (پیش‌فرض: X-API-Key). Idempotency-Key هم کمک می‌کند ارسال دوباره، پرداخت تکراری نسازد."));
    }

    private String method, type;
    private EditText headersEd, bodyEd, lastEd;
    private LinearLayout methodChips, typeChips, bodyBox;
    private TextView preview, previewErr;

    /** Method, body type, headers and body template, with variables to insert and a live preview of what is sent. */
    private void buildRequest(LinearLayout f) {
        boolean own = !tg;
        method = own ? dest.method() : "POST";
        type = own ? dest.bodyType() : Request.JSON;
        f.addView(Ui.section(this, "درخواست"));
        f.addView(hint("هر مقصد درخواست خودش را دارد. متغیرها مثل {{amount}} موقع ارسال با مقدار واقعی پر می‌شوند و برای JSON خودشان در \"\" قرار می‌گیرند."));

        f.addView(label("متد"));
        methodChips = chipRow(f);
        f.addView(label("بدنه"));
        typeChips = chipRow(f);

        headersEd = code(own ? dest.headers() : Request.DEFAULT_HEADERS, 3);
        f.addView(label("هدرها · هر خط Name: value"));
        f.addView(box(headersEd));

        bodyBox = Ui.col(this);
        bodyEd = code(own ? dest.bodyTpl() : Request.defaultBody(type), 8);
        bodyBox.addView(label("قالب بدنه"));
        bodyBox.addView(box(bodyEd));
        f.addView(bodyBox);
        renderChips();

        f.addView(label("متغیرها · بزنید تا در جای مکان‌نما اضافه شود"));
        f.addView(varChips(false));

        TextView reset = link("بازگشت به درخواست پیش‌فرض");
        reset.setOnClickListener(v -> {
            method = "POST";
            type = Request.JSON;
            headersEd.setText(Request.DEFAULT_HEADERS);
            bodyEd.setText(Request.defaultBody(type));
            renderChips();
        });
        f.addView(reset);

        f.addView(label("پیش‌نمایش · با یک پیامک نمونه"));
        LinearLayout pv = Ui.col(this);
        Ui.pad(pv, 14, 12, 14, 12);
        pv.setBackground(Ui.fill(this, R.color.line, 12));
        preview = Ui.text(this, "", 11.5f, R.color.fg, Ui.W_REGULAR);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextDirection(View.TEXT_DIRECTION_LTR);
        preview.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        preview.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        preview.setTextIsSelectable(true);
        pv.addView(preview);
        previewErr = Ui.text(this, "", 12, R.color.red, Ui.W_BOLD);
        Ui.pad(previewErr, 0, 8, 0, 0);
        pv.addView(previewErr);
        f.addView(pv, cardLp());

        for (EditText e : new EditText[]{headersEd, bodyEd, url, key}) e.addTextChangedListener(watch(this::updatePreview));
    }

    private void renderChips() {
        methodChips.removeAllViews();
        for (String m : Request.METHODS) chip(methodChips, m, m.equals(method), () -> {
            method = m;
            renderChips();
        });
        typeChips.removeAllViews();
        String[] names = {"JSON", "Form", "متن", "بدون بدنه"};
        for (int i = 0; i < names.length; i++) {
            String t = Request.TYPES[i];
            chip(typeChips, names[i], t.equals(type), () -> {
                // a template still at the old type's default follows the new type
                if (bodyEd.getText().toString().trim().isEmpty() || bodyEd.getText().toString().equals(Request.defaultBody(type))) {
                    bodyEd.setText(Request.defaultBody(t));
                }
                type = t;
                renderChips();
            });
        }
        boolean hasBody = !"GET".equals(method) && !Request.NONE.equals(type);
        bodyBox.setVisibility(hasBody ? View.VISIBLE : View.GONE);
        typeChips.setAlpha("GET".equals(method) ? 0.35f : 1f);
        if (preview != null) updatePreview();
    }

    private void updatePreview() {
        if (tg || preview == null) return;
        Db.Dest d = new Db.Dest();
        d.url = url.getText().toString().trim();
        d.apiKey = key.getText().toString().trim();
        readRequest(d);
        Request q = Forwarder.request(d, Request.sample(false), 1, false);
        preview.setText(q.describe(d.apiKey, false));
        String err = q.jsonError();
        previewErr.setText(err == null ? "" : "JSON درست نیست: " + err);
        previewErr.setVisibility(err == null ? View.GONE : View.VISIBLE);
    }

    /** Stores only what differs from the default, so an untouched destination keeps following the default. */
    private void readRequest(Db.Dest d) {
        String h = headersEd.getText().toString().trim(), b = bodyEd.getText().toString();
        d.method = "POST".equals(method) ? null : method;
        d.bodyType = Request.JSON.equals(type) ? null : type;
        d.headers = h.equals(Request.DEFAULT_HEADERS) ? null : h;
        d.bodyTpl = b.equals(Request.defaultBody(type)) ? null : b;
    }

    // ---------- telegram ----------

    private EditText token, chat, apiServer, tplEd;
    private String parse;
    private LinearLayout parseChips;
    private TextView tgPreview, tgNote;

    private void buildTelegramTop(LinearLayout f) {
        token = secret(f, "توکن بات", tg ? dest.apiKey : "", "نمایش توکن", "پنهان کردن توکن");
        token.setHint("123456789:AAH…");
        f.addView(hint("از @BotFather در تلگرام بگیرید: /newbot. هر بات فقط به چت‌هایی پیام می‌دهد که در آن‌ها عضو است یا یک بار به آن پیام داده‌اند."));

        chat = new EditText(this);
        chat.setText(tg ? dest.chatId : "");
        chat.setHint("-1001234567890 یا @channel");
        f.addView(Ui.field(this, "چت آیدی", chat, true));
        TextView find = link("پیدا کردن چت آیدی");
        find.setOnClickListener(v -> findChat());
        f.addView(find);
        f.addView(hint("بات را به گروه اضافه کنید (برای کانال، ادمین کنید) و یک پیام در آن بفرستید، بعد «پیدا کردن چت آیدی» را بزنید."));

        f.addView(Ui.section(this, "متن پیام"));
        parse = tg ? dest.parseMode() : Telegram.MARKDOWN;
        f.addView(label("قالب‌بندی"));
        parseChips = chipRow(f);

        tplEd = new EditText(this);
        tplEd.setText(tg ? dest.template() : Telegram.DEFAULT_TEMPLATE);
        tplEd.setTypeface(Ui.font(this, Ui.W_REGULAR));
        tplEd.setTextSize(14);
        tplEd.setTextColor(Ui.color(this, R.color.fg));
        tplEd.setGravity(Gravity.TOP | Gravity.START);
        tplEd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tplEd.setMinLines(6);
        tplEd.setBackground(null);
        tplEd.setOnFocusChangeListener((v, has) -> { if (has) lastEd = tplEd; });
        f.addView(label("قالب پیام"));
        f.addView(box(tplEd));

        // Mark buttons wrap the selected text, like the formatting menu in Telegram.
        LinearLayout marks = Ui.rowLayout(this);
        Ui.pad(marks, 20, 8, 20, 0);
        String[][] buttons = {{"B", "**"}, {"I", "__"}, {"S", "~~"}, {"M", "`"}, {"</>", "```"}, {"❝", "> "}, {"▒", "||"}};
        for (String[] m : buttons) {
            TextView t = Ui.text(this, m[0], 13, R.color.fg, Ui.W_BLACK);
            if ("I".equals(m[0])) t.setTypeface(Typeface.SERIF, Typeface.BOLD_ITALIC);
            if ("M".equals(m[0]) || "</>".equals(m[0])) t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            t.setMinWidth(Ui.dp(this, 40));
            Ui.pad(t, 8, 5, 8, 5);
            t.setBackground(Ui.outline(this, R.color.faint, 8, 1, false));
            t.setOnClickListener(v -> wrap(m[1]));
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMarginEnd(Ui.dp(this, 6));
            marks.addView(t, p);
        }
        f.addView(marks);
        tgNote = hint("");
        f.addView(tgNote);

        f.addView(label("متغیرها · بزنید تا در جای مکان‌نما اضافه شود"));
        f.addView(varChips(true));

        TextView reset = link("بازگشت به متن پیش‌فرض");
        reset.setOnClickListener(v -> {
            tplEd.setText(Telegram.DEFAULT_TEMPLATE);
            parse = Telegram.MARKDOWN;
            renderParse();
        });
        f.addView(reset);

        f.addView(label("پیش‌نمایش در تلگرام · با یک پیامک نمونه"));
        LinearLayout bubble = Ui.col(this);
        Ui.pad(bubble, 14, 10, 14, 10);
        bubble.setBackground(Ui.fill(this, R.color.line, 16));
        tgPreview = Ui.text(this, "", 14, R.color.fg, Ui.W_REGULAR);
        tgPreview.setLineSpacing(0, 1.25f);
        tgPreview.setTextIsSelectable(true);
        bubble.addView(tgPreview);
        f.addView(bubble, cardLp());

        tplEd.addTextChangedListener(watch(this::updateTgPreview));
        renderParse();
    }

    private void buildTelegramBottom(LinearLayout f) {
        f.addView(Ui.section(this, "پیشرفته"));
        apiServer = new EditText(this);
        String server = tg ? dest.url : "";
        apiServer.setText(server == null || server.equals(Telegram.API) ? "" : server);
        apiServer.setHint(Telegram.API);
        f.addView(Ui.field(this, "سرور Bot API", apiServer, true));
        f.addView(hint("خالی یعنی سرور رسمی تلگرام. اگر api.telegram.org فیلتر است و همیشه VPN ندارید، آدرس Bot API سرور یا رله‌ی خودتان را بگذارید."
                + " تا وصل نشود، پیام‌ها در صف می‌مانند و بعد فرستاده می‌شوند."));
    }

    private void renderParse() {
        parseChips.removeAllViews();
        chip(parseChips, "مارک‌داون", Telegram.MARKDOWN.equals(parse), () -> { parse = Telegram.MARKDOWN; renderParse(); });
        chip(parseChips, "متن ساده", Telegram.PLAIN.equals(parse), () -> { parse = Telegram.PLAIN; renderParse(); });
        boolean md = Telegram.MARKDOWN.equals(parse);
        StringBuilder s = new StringBuilder();
        for (String[] m : Telegram.MARKS) s.append(s.length() > 0 ? "  ·  " : "").append(m[0]).append(" ").append(m[1]);
        tgNote.setText(md ? s + "\nعلامت‌های داخل متن پیامک‌ها قالب را به‌هم نمی‌زنند." : "متن همان‌طور که نوشته‌اید فرستاده می‌شود، بدون قالب‌بندی.");
        tgNote.setTextDirection(View.TEXT_DIRECTION_RTL);
        updateTgPreview();
    }

    private void wrap(String mark) {
        int s = tplEd.getSelectionStart(), e = tplEd.getSelectionEnd();
        if (s < 0) s = e = tplEd.length();
        if (s > e) { int t = s; s = e; e = t; }
        if (mark.equals("> ")) { // a quote marks the start of the line, not both ends
            int line = tplEd.getText().toString().lastIndexOf('\n', s - 1) + 1;
            tplEd.getText().insert(line, mark);
            tplEd.requestFocus();
            return;
        }
        tplEd.getText().insert(e, mark);
        tplEd.getText().insert(s, mark);
        tplEd.requestFocus();
        tplEd.setSelection(s + mark.length(), e + mark.length());
    }

    /** The message as Telegram shows it. Html can not draw spoilers, so they are shown as plain text here. */
    private void updateTgPreview() {
        if (!tg || tgPreview == null) return;
        Db.Dest d = new Db.Dest();
        d.bodyTpl = tplEd.getText().toString();
        boolean md = Telegram.MARKDOWN.equals(parse);
        String text = Telegram.text(d.template(), Request.vars(d, Request.sample(false), 1, false), md);
        if (!md) {
            tgPreview.setText(text);
            return;
        }
        String html = text.replace("\n", "<br>")
                .replace("<code>", "<tt>").replace("</code>", "</tt>")
                .replace("<pre>", "<tt>").replace("</pre>", "</tt>")
                .replace("<tg-spoiler>", "").replace("</tg-spoiler>", "");
        tgPreview.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
    }

    private void findChat() {
        String t = token.getText().toString().trim();
        if (!Telegram.validToken(t)) {
            token.setError("اول توکن بات را درست وارد کنید");
            token.requestFocus();
            return;
        }
        AlertDialog wait = new AlertDialog.Builder(this).setTitle("پیدا کردن چت آیدی").setMessage("در حال پرسیدن از تلگرام…")
                .setNegativeButton("بستن", null).show();
        String server = apiServer.getText().toString().trim();
        int timeout = Prefs.of(this).timeoutSec();
        List<Proxies.Proxy> chatProxies = proxyOn.isChecked() ? new ArrayList<>(proxies) : null;
        new Thread(() -> {
            List<String[]> chats;
            try {
                chats = Telegram.recentChats(server, t, timeout, chatProxies);
            } catch (java.net.UnknownHostException | java.net.SocketTimeoutException | java.net.ConnectException e) {
                runOnUiThread(() -> { if (wait.isShowing()) wait.setMessage("به سرور تلگرام وصل نشد. پروکسی‌ها یا «سرور Bot API» را بررسی کنید."); });
                return;
            } catch (Exception e) {
                runOnUiThread(() -> { if (wait.isShowing()) wait.setMessage(String.valueOf(e.getMessage())); });
                return;
            }
            runOnUiThread(() -> {
                if (!wait.isShowing()) return;
                if (chats.isEmpty()) {
                    wait.setMessage("هنوز پیامی به این بات نرسیده. بات را به گروه اضافه کنید یا در پی‌وی‌اش /start بزنید، یک پیام بفرستید و دوباره امتحان کنید.");
                    return;
                }
                wait.dismiss();
                String[] labels = new String[chats.size()];
                for (int i = 0; i < chats.size(); i++) labels[i] = chats.get(i)[1] + " · " + chats.get(i)[2] + "\n" + chats.get(i)[0];
                new AlertDialog.Builder(this).setTitle("کدام چت؟")
                        .setItems(labels, (dlg, w) -> chat.setText(chats.get(w)[0]))
                        .setNegativeButton("انصراف", null).show();
            });
        }, "forapp-tg-chats").start();
    }

    private void readTelegram(Db.Dest d) {
        d.kind = Telegram.KIND;
        String server = apiServer.getText().toString().trim();
        d.url = server.isEmpty() ? Telegram.API : server;
        d.apiKey = token.getText().toString().trim();
        d.chatId = chat.getText().toString().trim();
        d.parseMode = Telegram.MARKDOWN.equals(parse) ? null : parse;
        String tpl = tplEd.getText().toString();
        d.bodyTpl = tpl.equals(Telegram.DEFAULT_TEMPLATE) ? null : tpl;
    }

    // ---------- proxies ----------

    private static final int REQ_PROXY = 7;
    private final List<Proxies.Proxy> proxies = new ArrayList<>();
    private Switch proxyOn, proxyDirect;
    private LinearLayout proxyList;

    /** Proxies tried in order, one pass per attempt; edited here and saved with the destination. */
    private void buildProxies(LinearLayout f, List<Proxies.Proxy> stored) {
        proxies.addAll(stored);
        f.addView(Ui.section(this, "پروکسی"));
        f.addView(Ui.hairline(this, R.color.line));
        proxyOn = Ui.toggle(this, dest.proxyOn);
        f.addView(Ui.row(this, "ارسال از طریق پروکسی", "به ترتیب امتحان می‌شوند، تا یکی برسد", proxyOn));
        proxyList = Ui.col(this);
        f.addView(proxyList);
        f.addView(Ui.hairline(this, R.color.line));
        TextView add = link("＋ افزودن پروکسی");
        Ui.pad(add, 20, 12, 20, 0);
        add.setOnClickListener(v -> editProxy(-1));
        f.addView(add);
        LinearLayout acts = Ui.col(this);
        Ui.pad(acts, 20, 16, 20, 0);
        acts.addView(Ui.button(this, "تست پروکسی‌ها", false, v -> testProxies()), full());
        f.addView(acts);
        View gap = new View(this);
        f.addView(gap, Ui.lp(1, Ui.dp(this, 12)));
        f.addView(Ui.hairline(this, R.color.line));
        proxyDirect = Ui.toggle(this, dest.proxyDirect);
        f.addView(Ui.row(this, "اگر هیچ‌کدام نرسید، مستقیم بفرست", "بدون پروکسی، در آخر همان دور", proxyDirect));
        f.addView(Ui.hairline(this, R.color.line));
        renderProxies();
    }

    private void renderProxies() {
        if (proxyList == null) return;
        // a test send or test sheet may have updated the stored status of saved proxies
        if (dest.id > 0) {
            List<Proxies.Proxy> fresh = Db.get(this).proxies(dest.id);
            for (Proxies.Proxy p : proxies)
                for (Proxies.Proxy s : fresh)
                    if (p.id > 0 && p.id == s.id && s.lastAt > p.lastAt) {
                        p.lastOk = s.lastOk; p.lastMs = s.lastMs; p.lastError = s.lastError; p.lastAt = s.lastAt;
                    }
        }
        proxyList.removeAllViews();
        for (int i = 0; i < proxies.size(); i++) {
            proxyList.addView(Ui.hairline(this, R.color.line));
            proxyList.addView(proxyRow(i));
        }
    }

    private View proxyRow(int i) {
        Proxies.Proxy p = proxies.get(i);
        LinearLayout r = Ui.rowLayout(this);
        Ui.pad(r, 12, 10, 20, 10);
        r.setMinimumHeight(Ui.dp(this, 52));
        Ui.ripple(r, false);
        r.setOnClickListener(v -> editProxy(i));

        TextView handle = Ui.text(this, "⋮⋮", 15, R.color.mid, Ui.W_BOLD);
        handle.setAlpha(0.5f);
        Ui.pad(handle, 8, 8, 8, 8);
        handle.setContentDescription("جابه‌جا کردن");
        handle.setOnTouchListener(new DragHandle(r, i));
        r.addView(handle);

        TextView n = Ui.text(this, Fa.d(i + 1), 11, R.color.fg, Ui.W_BLACK);
        n.setGravity(Gravity.CENTER);
        n.setBackground(Ui.outline(this, R.color.fg, 99, 1.5f, false));
        LinearLayout.LayoutParams np = Ui.lp(Ui.dp(this, 22), Ui.dp(this, 22));
        np.setMarginEnd(Ui.dp(this, 10));
        r.addView(n, np);

        LinearLayout t = Ui.col(this);
        TextView addr = Ui.ellipsize(Ui.text(this, p.label(), 12.5f, R.color.fg, Ui.W_BOLD));
        addr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        addr.setTextDirection(View.TEXT_DIRECTION_LTR);
        addr.setGravity(Gravity.END);
        t.addView(addr, full());
        boolean bad = Boolean.FALSE.equals(p.lastOk);
        String sub = p.lastOk == null ? "هنوز تست نشده"
                : p.lastOk ? Fa.d(p.lastMs) + " میلی‌ثانیه · " + Fa.ago(p.lastAt, System.currentTimeMillis())
                : "وصل نشد · " + (p.lastError == null ? "" : p.lastError);
        TextView st = Ui.ellipsize(Ui.text(this, sub, 11, bad ? R.color.red : R.color.mid, Ui.W_REGULAR));
        t.addView(st, full());
        r.addView(t, Ui.weight1());

        TextView tag = Ui.text(this, p.typeLabel(), 10, R.color.fg, Ui.W_BLACK);
        Ui.pad(tag, 7, 0, 7, 0);
        tag.setBackground(Ui.outline(this, R.color.faint, 99, 1, false));
        LinearLayout.LayoutParams tp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.setMarginStart(Ui.dp(this, 10));
        r.addView(tag, tp);

        View dot = new View(this);
        dot.setBackground(Ui.fill(this, p.lastOk == null ? R.color.faint : bad ? R.color.red : R.color.fg, 99));
        LinearLayout.LayoutParams dp = Ui.lp(Ui.dp(this, 8), Ui.dp(this, 8));
        dp.setMarginStart(Ui.dp(this, 10));
        r.addView(dot, dp);
        return r;
    }

    /** Drag the handle up or down; the row follows the finger and lands where it is let go. */
    private final class DragHandle implements View.OnTouchListener {
        private final View row;
        private final int index;
        private float startY;

        DragHandle(View row, int index) {
            this.row = row;
            this.index = index;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = e.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    row.setElevation(Ui.dp(DestinationEditActivity.this, 4));
                    row.setBackgroundColor(Ui.color(DestinationEditActivity.this, R.color.bg));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    row.setTranslationY(e.getRawY() - startY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    int h = Math.max(1, row.getHeight());
                    int to = Math.max(0, Math.min(proxies.size() - 1, index + Math.round((e.getRawY() - startY) / h)));
                    row.setTranslationY(0);
                    row.setElevation(0);
                    if (to != index) {
                        Proxies.Proxy moved = proxies.remove(index);
                        proxies.add(to, moved);
                    }
                    renderProxies();
                    return true;
            }
            return false;
        }
    }

    /** Where the proxies are tested to: the webhook, or the Telegram Bot API server. */
    private String proxyTarget() {
        if (tg) {
            String s = apiServer.getText().toString().trim();
            return s.isEmpty() ? Telegram.API : s;
        }
        return url.getText().toString().trim();
    }

    private void editProxy(int i) {
        Intent in = new Intent(this, ProxyEditActivity.class);
        in.putExtra(ProxyEditActivity.EXTRA_INDEX, i);
        in.putExtra(ProxyEditActivity.EXTRA_TARGET, proxyTarget());
        if (i >= 0) ProxyEditActivity.put(in, proxies.get(i));
        startActivityForResult(in, REQ_PROXY);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PROXY || res != RESULT_OK || data == null) return;
        int i = data.getIntExtra(ProxyEditActivity.EXTRA_INDEX, -1);
        if (data.getBooleanExtra(ProxyEditActivity.EXTRA_DELETE, false)) {
            if (i >= 0 && i < proxies.size()) proxies.remove(i);
        } else {
            Proxies.Proxy p = ProxyEditActivity.read(data);
            if (i >= 0 && i < proxies.size()) proxies.set(i, p);
            else {
                proxies.add(p);
                if (proxies.size() == 1) proxyOn.setChecked(true); // the first proxy turns sending through proxies on
            }
        }
        renderProxies();
    }

    /** Tests every proxy at once: a connection through it to this destination's host, nothing sent. */
    private void testProxies() {
        if (proxies.isEmpty()) {
            Ui.toast(this, "اول یک پروکسی اضافه کنید");
            return;
        }
        String target = proxyTarget();
        if (!target.matches("(?i)^https?://[^\\s/]+.*")) {
            Ui.toast(this, "اول آدرس مقصد را درست بنویسید");
            return;
        }
        LinearLayout c = Ui.col(this);
        c.addView(Ui.text(this, "تست پروکسی‌ها", 17, R.color.fg, Ui.W_BLACK));
        TextView to = Ui.text(this, "تا " + app.forapp.core.Trace.host(target), 12, R.color.mid, Ui.W_REGULAR);
        c.addView(to);
        LinearLayout sum = Ui.rowLayout(this);
        sum.setGravity(Gravity.BOTTOM);
        Ui.pad(sum, 0, 12, 0, 0);
        TextView big = Ui.text(this, "", 36, R.color.fg, Ui.W_BLACK);
        big.setIncludeFontPadding(false);
        sum.addView(big);
        TextView bigSub = Ui.text(this, "جواب دادند", 12, R.color.mid, Ui.W_REGULAR);
        Ui.pad(bigSub, 10, 0, 0, 4);
        sum.addView(bigSub);
        c.addView(sum);
        LinearLayout bar = Ui.rowLayout(this);
        Ui.pad(bar, 0, 8, 0, 4);
        c.addView(bar, full());
        LinearLayout cards = Ui.col(this);
        c.addView(cards, full());
        LinearLayout btns = Ui.rowLayout(this);
        Ui.pad(btns, 0, 16, 0, 0);
        c.addView(btns, full());
        android.app.Dialog dlg = Ui.sheet(this, c);
        Runnable[] run = new Runnable[1];
        btns.addView(Ui.button(this, "تست دوباره", false, v -> run[0].run()), Ui.weight1());
        View gap = new View(this);
        btns.addView(gap, Ui.lp(Ui.dp(this, 10), 1));
        btns.addView(Ui.button(this, "بستن", true, v -> dlg.dismiss()), Ui.weight1());

        List<Proxies.Proxy> list = new ArrayList<>(proxies);
        Proxies.Probe[] results = new Proxies.Probe[list.size()];
        int timeout = Prefs.of(this).timeoutSec();
        run[0] = () -> {
            java.util.Arrays.fill(results, null);
            drawTest(list, results, big, bar, cards);
            for (int k = 0; k < list.size(); k++) {
                int idx = k;
                new Thread(() -> {
                    Proxies.Proxy p = list.get(idx);
                    Proxies.Probe r = Proxies.probe(p, target, timeout);
                    app.forapp.core.Trace.i("SETTINGS", "proxy test " + (idx + 1) + " " + p.typeLabel() + " " + p.hostPort() + ": "
                            + (r.ok ? "OK in " + r.ms + "ms" : r.error));
                    Db.get(this).setProxyLast(p.id, r.ok, r.ms, r.error);
                    runOnUiThread(() -> {
                        results[idx] = r;
                        p.lastOk = r.ok;
                        p.lastMs = r.ms;
                        p.lastError = r.error;
                        p.lastAt = System.currentTimeMillis();
                        if (dlg.isShowing()) drawTest(list, results, big, bar, cards);
                        renderProxies();
                    });
                }, "forapp-proxy-test").start();
            }
        };
        dlg.show();
        run[0].run();
    }

    private void drawTest(List<Proxies.Proxy> list, Proxies.Probe[] results, TextView big, LinearLayout bar, LinearLayout cards) {
        int ok = 0;
        for (Proxies.Probe r : results) if (r != null && r.ok) ok++;
        big.setText(Fa.d(ok) + " از " + Fa.d(list.size()));
        bar.removeAllViews();
        cards.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            Proxies.Probe r = results[i];
            View seg = new View(this);
            seg.setBackgroundColor(Ui.color(this, r == null ? R.color.faint : r.ok ? R.color.fg : R.color.red));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, Ui.dp(this, 4), 1f);
            if (i > 0) sp.setMarginStart(Ui.dp(this, 3));
            bar.addView(seg, sp);

            Proxies.Proxy p = list.get(i);
            LinearLayout card = Ui.col(this);
            Ui.pad(card, 12, 10, 12, 10);
            card.setBackground(Ui.outline(this, r != null && !r.ok ? R.color.red : R.color.faint, 14, 1, false));
            LinearLayout head = Ui.rowLayout(this);
            head.addView(Ui.icon(this, r == null ? R.drawable.ic_retry : r.ok ? R.drawable.ic_ok : R.drawable.ic_fail,
                    r != null && !r.ok ? R.color.red : r == null ? R.color.mid : R.color.fg, 16));
            TextView addr = Ui.ellipsize(Ui.text(this, p.hostPort(), 12, R.color.fg, Ui.W_BOLD));
            addr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            addr.setTextDirection(View.TEXT_DIRECTION_LTR);
            addr.setGravity(Gravity.END);
            Ui.pad(addr, 8, 0, 8, 0);
            head.addView(addr, Ui.weight1());
            head.addView(Ui.text(this, r == null ? "در حال تست…" : r.ok ? Fa.d(r.ms) + " میلی‌ثانیه" : "ناموفق", 12.5f,
                    r == null ? R.color.mid : r.ok ? R.color.fg : R.color.red, r == null ? Ui.W_REGULAR : Ui.W_BLACK));
            card.addView(head);
            TextView meta = Ui.text(this, Fa.d(i + 1) + " · " + p.typeLabel(), 11, R.color.mid, Ui.W_REGULAR);
            Ui.pad(meta, 24, 0, 0, 0);
            card.addView(meta);
            if (r != null && !r.ok && r.error != null) {
                TextView err = Ui.text(this, r.error, 11.5f, R.color.red, Ui.W_REGULAR);
                err.setTextDirection(View.TEXT_DIRECTION_LTR);
                Ui.pad(err, 10, 5, 10, 5);
                err.setBackground(Ui.fill(this, R.color.redbg, 8));
                LinearLayout.LayoutParams ep = full();
                ep.topMargin = Ui.dp(this, 6);
                card.addView(err, ep);
            }
            LinearLayout.LayoutParams cp = full();
            cp.topMargin = Ui.dp(this, 8);
            cards.addView(card, cp);
        }
    }

    // ---------- shared pieces ----------

    /** A hidden text field (API key, bot token) with a show/hide link under it. */
    private EditText secret(LinearLayout f, String title, String value, String showText, String hideText) {
        EditText e = new EditText(this);
        e.setText(value);
        LinearLayout box = Ui.field(this, title, e, true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTypeface(Typeface.MONOSPACE);
        f.addView(box);
        TextView show = Ui.text(this, showText, 12, R.color.fg, Ui.W_BOLD);
        Ui.pad(show, 20, 6, 20, 0);
        show.setOnClickListener(v -> {
            boolean hidden = (e.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            e.setInputType(InputType.TYPE_CLASS_TEXT | (hidden ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            e.setTypeface(Typeface.MONOSPACE);
            e.setSelection(e.length());
            show.setText(hidden ? hideText : showText);
        });
        f.addView(show);
        return e;
    }

    /** Variables to insert at the cursor; long-press says what each holds. The Telegram row leaves out the API key. */
    private View varChips(boolean telegram) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout vars = Ui.rowLayout(this);
        Ui.pad(vars, 20, 4, 20, 4);
        for (String[] v : Request.VARS) {
            if (telegram && "api_key".equals(v[0])) continue;
            TextView t = Ui.text(this, "{{" + v[0] + "}}", 12, R.color.fg, Ui.W_BOLD);
            t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            Ui.pad(t, 10, 5, 10, 5);
            t.setBackground(Ui.outline(this, R.color.faint, 99, 1, false));
            t.setOnClickListener(x -> insert("{{" + v[0] + "}}", telegram ? tplEd : null));
            t.setOnLongClickListener(x -> { Ui.toast(this, v[1]); return true; });
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMarginEnd(Ui.dp(this, 6));
            vars.addView(t, p);
        }
        hs.addView(vars);
        return hs;
    }

    private void insert(String s, EditText only) {
        EditText e = only != null ? only : lastEd != null && lastEd != tplEd ? lastEd : bodyEd;
        int at = Math.max(0, e.getSelectionStart());
        e.getText().insert(at, s);
        e.requestFocus();
    }

    private LinearLayout chipRow(LinearLayout f) {
        LinearLayout r = Ui.rowLayout(this);
        Ui.pad(r, 20, 4, 20, 4);
        f.addView(r);
        return r;
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

    private TextView link(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.fg, Ui.W_BOLD);
        Ui.pad(t, 20, 10, 20, 0);
        return t;
    }

    /** A left-to-right, multi-line code editor. */
    private EditText code(String text, int lines) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(12.5f);
        e.setTextColor(Ui.color(this, R.color.fg));
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // code reads left to right, inside a right-to-left screen
        e.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        e.setGravity(Gravity.TOP | Gravity.LEFT);
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

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams p = full();
        p.setMargins(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        return p;
    }

    private static android.text.TextWatcher watch(Runnable r) {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { r.run(); }
        };
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

    // ---------- save / test ----------

    /** Copies the form into a Dest, or explains what is wrong. */
    private Db.Dest read() {
        Db.Dest d = new Db.Dest();
        d.id = dest.id;
        d.enabled = dest.enabled;
        d.name = name.getText().toString().trim();
        if (d.name.isEmpty()) d.name = "مقصد";
        d.allBanks = allBanks.isChecked();
        d.proxyOn = proxyOn.isChecked();
        d.proxyDirect = proxyDirect.isChecked();
        d.proxies = new ArrayList<>();
        for (Proxies.Proxy p : proxies) d.proxies.add(p.copy());
        if (tg) {
            readTelegram(d);
            if (!Telegram.validToken(d.apiKey)) return fail(token, "توکن بات درست نیست؛ شکلش مثل 123456789:AAH… است");
            if (d.chatId.isEmpty()) return fail(chat, "چت آیدی را بنویسید یا «پیدا کردن چت آیدی» را بزنید");
            if (!d.url.matches("(?i)^https?://[^\\s/]+.*")) return fail(apiServer, "آدرس باید با https:// شروع شود");
            return d;
        }
        d.url = url.getText().toString().trim();
        d.apiKey = key.getText().toString().trim();
        readRequest(d);
        if (!d.url.matches("(?i)^https?://[^\\s/]+.*")) return fail(url, "آدرس باید با https:// شروع شود");
        return d;
    }

    private Db.Dest fail(EditText e, String msg) {
        e.setError(msg);
        e.requestFocus();
        return null;
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
        String jsonErr = d.telegram() ? null : Forwarder.request(d, Request.sample(false), 1, false).jsonError();
        if (jsonErr != null) {
            Ui.confirm(this, "بدنه JSON درست نیست", "سرور احتمالاً این درخواست را رد می‌کند:\n" + jsonErr, "با همین ذخیره کن", doSave);
        } else if (d.url.toLowerCase().startsWith("http://")) {
            Ui.confirm(this, "آدرس بدون رمزنگاری", "با http:// کلید و متن پیامک‌ها رمزنگاری‌نشده فرستاده می‌شوند. بهتر است از https:// استفاده کنید.",
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
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("ارسال آزمایشی").setMessage("در حال ارسال به " + d.target() + " …")
                .setPositiveButton("بستن", null).show();
        new Thread(() -> {
            Forwarder.Result r = Forwarder.test(this, d);
            String via = r.via == null ? "" : "\nاز " + r.via;
            String msg = r.ok() ? (d.telegram() ? "✓ پیام آزمایشی در تلگرام فرستاده شد." : "✓ سرور پاسخ " + Fa.d(r.code) + " داد، در " + Fa.d(r.ms) + " میلی‌ثانیه. اتصال درست است.") + via
                    : "✕ " + (r.error == null ? "ناموفق" : Fa.d(r.error)) + via + "\n" + advice(d, r.code);
            runOnUiThread(this::renderProxies); // a send through the proxies updates their status
            runOnUiThread(() -> { if (dlg.isShowing()) dlg.setMessage(msg.trim()); });
        }).start();
    }

    private static String advice(Db.Dest d, int code) {
        if (!d.telegram()) return code == 401 || code == 403 ? "کلید API را بررسی کنید." : "";
        switch (code) {
            case 0: return "به سرور تلگرام وصل نشد. پروکسی‌ها یا «سرور Bot API» را بررسی کنید.";
            case 400: return "چت آیدی درست نیست، بات عضو آن چت نیست یا قالب متن ایراد دارد.";
            case 401: case 404: return "توکن بات درست نیست.";
            case 403: return "بات اجازه‌ی پیام دادن در این چت را ندارد (در کانال باید ادمین باشد).";
            default: return "";
        }
    }
}
