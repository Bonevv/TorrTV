// TorrTV - услуга за настройки (Cloudflare Worker).
// Един файл. Нужно е KV пространство, вързано към Worker-а с име променлива DB.
// Приложението пита тук с номер и ПИН и получава ключовете и списъците на своя профил.
// Страницата за управление е на адрес /admin.

const ADMIN_NUM = "61581";   // номерът на админ профила
const MAX_FAILS = 5;         // грешни ПИН-ове до заключване на номера
const LOCK_SECONDS = 600;    // колко време е заключен номерът (10 минути)
const IP_FAILS = 40;         // грешни опити за час от един адрес
const SESSION_HOURS = 12;    // колко време важи вход в страницата за управление

// Полета, които услугата раздава. Приложението ги знае със същите имена.
const STR_FIELDS = ["tmdb_key", "os_key", "os_user", "os_pass", "subdl_key", "subsource_key",
  "dom_apibay", "dom_yts", "dom_eztv", "dom_nyaa", "dom_knaben", "dom_csv", "dom_torrentsdb", "dom_torrentio"];
const LIST_FIELDS = ["web_movie", "web_tv", "web_bg_providers", "addons", "trackers", "src_off", "sites"];

const enc = new TextEncoder();

function json(o, status = 200) {
  return new Response(JSON.stringify(o), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });
}

async function sha(s) {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function rnd(n) {
  const a = new Uint32Array(1);
  const lim = Math.floor(4294967296 / n) * n;
  do { crypto.getRandomValues(a); } while (a[0] >= lim);
  return a[0] % n;
}

function digits(len) {
  let s = "";
  for (let i = 0; i < len; i++) s += rnd(10);
  return s;
}

function token() {
  const b = new Uint8Array(24);
  crypto.getRandomValues(b);
  return [...b].map(x => x.toString(16).padStart(2, "0")).join("");
}

function same(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

const getJ = (env, k) => env.DB.get(k, "json");
const putJ = (env, k, v, opt) => env.DB.put(k, JSON.stringify(v), opt);

// ---------- Почистване на входа ----------

function cleanStr(v, max = 300) {
  return String(v ?? "").replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, max);
}

// Торент сайт: име|адрес с {q} или {imdb}|json или rss|списък|заглавие|хеш|магнит|сийдъри|размер
function siteOk(s) {
  return /^[^|]{1,40}\|https?:\/\/[^|\s]+\|(json|rss)(\|[^|]*){0,6}$/i.test(s) && /\{(q|imdb)\}/.test(s);
}

function cleanList(v, f) {
  const a = Array.isArray(v) ? v : String(v || "").split("\n");
  const out = [];
  for (const x of a) {
    const s = cleanStr(x, f === "sites" ? 700 : 300);
    if (!s || s.startsWith("#")) continue;
    if (f === "web_bg_providers") {
      if (!["NINE", "TWO_K", "ZENO", "ALL", "FEN", "ZT", "ZIP", "-"].includes(s)) continue;
    }
    else if (f === "src_off") { if (!/^[a-z0-9_.-]{1,60}$/.test(s)) continue; }
    else if (f === "sites") { if (!siteOk(s)) continue; }
    else if (f === "trackers") { if (!/^(udp|http|https):\/\//i.test(s)) continue; }
    else if (!/^https?:\/\//i.test(s)) continue;
    if (!out.includes(s)) out.push(s);
    if (out.length >= 100) break;
  }
  return out;
}

function cleanSet(inp) {
  const out = {};
  if (!inp || typeof inp !== "object") return out;
  for (const f of STR_FIELDS) if (f in inp) out[f] = cleanStr(inp[f]);
  for (const f of LIST_FIELDS) if (f in inp) out[f] = cleanList(inp[f], f);
  return out;
}

// Крайните настройки на профила: неговите собствени, а каквото липсва - общите.
function effective(g, p) {
  const gs = (g && g.set) || {};
  const ps = (p && p.set) || {};
  const out = {};
  for (const f of STR_FIELDS) out[f] = f in ps ? ps[f] : (f in gs ? gs[f] : "");
  for (const f of LIST_FIELDS) out[f] = f in ps ? ps[f] : (f in gs ? gs[f] : []);
  return out;
}

async function revOf(eff, name) {
  return (await sha(JSON.stringify([name, eff]))).slice(0, 16);
}

function warnings(eff) {
  const w = [];
  if (!eff.tmdb_key) w.push("няма TMDB ключ");
  if (!eff.os_key || !eff.os_user || !eff.os_pass) w.push("няма пълен акаунт за OpenSubtitles");
  return w;
}

// ---------- Вход, заключване ----------

function numKey(num) { return /^\d{1,8}$/.test(num) ? num : "x"; }

async function failed(env, num, ip) {
  const k = "lock:" + numKey(num);
  const cur = (await getJ(env, k)) || { n: 0, until: 0 };
  cur.n++;
  if (cur.n >= MAX_FAILS) { cur.until = Date.now() + LOCK_SECONDS * 1000; cur.n = 0; }
  await putJ(env, k, cur, { expirationTtl: 3600 });
  const ik = "ip:" + ip;
  const c = parseInt((await env.DB.get(ik)) || "0", 10) + 1;
  await env.DB.put(ik, String(c), { expirationTtl: 3600 });
}

async function checkLogin(env, num, pin, ip) {
  const c = parseInt((await env.DB.get("ip:" + ip)) || "0", 10);
  if (c >= IP_FAILS) return { err: "ip", status: 429 };
  const lk = await getJ(env, "lock:" + numKey(num));
  if (lk && lk.until > Date.now()) return { err: "locked", wait: Math.ceil((lk.until - Date.now()) / 1000), status: 423 };
  const p = /^\d{1,8}$/.test(num) ? await getJ(env, "p:" + num) : null;
  if (!p || !same(String(p.pin), String(pin || ""))) {
    await failed(env, num, ip);
    return { err: "bad", status: 401 };
  }
  if (lk) await env.DB.delete("lock:" + numKey(num));
  return { p };
}

// Запомня кога и с коя версия е влизал профилът (най-много веднъж в минутата).
async function touch(env, p, ver) {
  const now = Date.now();
  if (now - (p.seen || 0) > 60000 || ver !== (p.ver || "")) {
    p.seen = now;
    p.ver = ver;
    await putJ(env, "p:" + p.n, p);
  }
}

// ---------- Приложението: /api/sync ----------

async function sync(req, env, ip) {
  const b = await req.json().catch(() => ({}));
  const num = cleanStr(b.n, 12);
  const r = await checkLogin(env, num, cleanStr(b.p, 12), ip);
  if (r.err) return json({ ok: false, err: r.err, wait: r.wait || 0 }, r.status);
  const p = r.p;
  if (!p.on) return json({ ok: false, err: "disabled" }, 403);
  const g = (await getJ(env, "g")) || { set: {} };
  const eff = effective(g, p);
  const rev = await revOf(eff, p.name);
  await touch(env, p, cleanStr(b.v, 20));
  if (b.have && b.have === rev) return json({ ok: true, changed: false, name: p.name, rev });
  return json({ ok: true, changed: true, name: p.name, rev, set: eff });
}

// ---------- Страницата за управление: /admin ----------

async function adminAuth(req, env) {
  const t = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
  if (!/^[0-9a-f]{48}$/.test(t)) return null;
  const s = await getJ(env, "sess:" + t);
  if (!s || s.exp < Date.now()) return null;
  const p = await getJ(env, "p:" + s.n);
  return p && p.admin && p.on ? p : null;
}

async function allNums(env) {
  const out = [];
  let cursor;
  do {
    const r = await env.DB.list({ prefix: "p:", cursor });
    for (const k of r.keys) out.push(k.name.slice(2));
    cursor = r.list_complete ? undefined : r.cursor;
  } while (cursor);
  return out;
}

function profileView(g, p) {
  return {
    n: p.n, name: p.name, admin: !!p.admin, on: !!p.on, pin: p.pin,
    set: p.set || {}, seen: p.seen || 0, ver: p.ver || "", warn: warnings(effective(g, p))
  };
}

function subst(t, tv) {
  return t.replace(/\{tmdb\}/g, tv ? "1399" : "27205").replace(/\{imdb\}/g, tv ? "tt0944947" : "tt1375666")
    .replace(/\{season\}/g, "1").replace(/\{episode\}/g, "1");
}

function dig(node, path) {
  for (const seg of String(path).split(".")) node = node && typeof node === "object" ? node[seg] : undefined;
  return node;
}

// Проба на торент сайт: пуска се търсене и се брои колко записа се разчитат.
async function probeSite(raw) {
  const p = raw.split("|").map(x => x.trim());
  if (!siteOk(raw)) return { url: p[0], ok: false, status: 0, note: "редът не е във вид име|адрес|json или rss" };
  const url = p[1].replace(/\{q\}/g, "matrix").replace(/\{imdb\}/g, "tt1375666");
  const t0 = Date.now();
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 9000);
  try {
    const res = await fetch(url, { signal: ac.signal, redirect: "follow",
      headers: { "user-agent": "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36" } });
    const text = (await res.text()).slice(0, 2000000);
    const out = { url: p[0], status: res.status, ms: Date.now() - t0, maybe: [403, 429, 503].includes(res.status) };
    if (res.status >= 400) return { ...out, ok: false };
    let n = 0;
    if (p[2].toLowerCase() === "rss") n = (text.match(/<item[\s>]|<entry[\s>]/g) || []).length;
    else {
      let j;
      try { j = JSON.parse(text); } catch (e) { return { ...out, ok: false, note: "отговорът не е JSON" }; }
      const arr = p[3] ? dig(j, p[3]) : j;
      if (!Array.isArray(arr)) return { ...out, ok: false, note: "няма списък „" + (p[3] || "") + "“" };
      n = arr.length;
    }
    return { ...out, ok: n > 0, count: n, note: n > 0 ? "" : "няма записи" };
  } catch (e) {
    return { url: p[0], ok: false, status: 0, ms: Date.now() - t0 };
  } finally {
    clearTimeout(timer);
  }
}

async function probe(kind, raw) {
  if (kind === "sites") return probeSite(raw);
  let url = raw;
  if (kind === "trackers" && /^udp:/i.test(raw)) return { url: raw, skip: true };
  if (kind === "web_movie") url = subst(raw, false);
  else if (kind === "web_tv") url = subst(raw, true);
  else if (kind === "addons") url = raw.replace(/\/manifest\.json$/i, "").replace(/\/+$/, "") + "/manifest.json";
  else if (kind === "domain" && !/^https?:\/\//i.test(raw)) url = "https://" + raw;
  if (!/^https?:\/\//i.test(url)) return { url: raw, ok: false, status: 0 };
  const t0 = Date.now();
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 8000);
  try {
    const res = await fetch(url, {
      signal: ac.signal, redirect: "follow",
      headers: { "user-agent": "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36" }
    });
    try { res.body && res.body.cancel(); } catch (e) { /* няма значение */ }
    // 403/429/503 са често защита срещу ботове: от телевизора може да върви
    return { url: raw, ok: res.status < 400, maybe: [403, 429, 503].includes(res.status), status: res.status, ms: Date.now() - t0 };
  } catch (e) {
    return { url: raw, ok: false, status: 0, ms: Date.now() - t0 };
  } finally {
    clearTimeout(timer);
  }
}

async function admin(req, env, path, ip) {
  const b = await req.json().catch(() => ({}));
  if (path === "status") return json({ claimed: !!(await getJ(env, "p:" + ADMIN_NUM)), num: ADMIN_NUM });

  if (path === "claim") {
    if (await getJ(env, "p:" + ADMIN_NUM)) return json({ ok: false, err: "claimed" }, 409);
    const pin = digits(6);
    await putJ(env, "p:" + ADMIN_NUM, { n: ADMIN_NUM, name: "Админ", pin, admin: true, on: true, set: {}, at: Date.now() });
    return json({ ok: true, num: ADMIN_NUM, pin });
  }

  if (path === "login") {
    const num = cleanStr(b.n, 12);
    const r = await checkLogin(env, num, cleanStr(b.p, 12), ip);
    if (r.err) return json({ ok: false, err: r.err, wait: r.wait || 0 }, r.status);
    if (!r.p.admin || !r.p.on) return json({ ok: false, err: "bad" }, 401);
    const t = token();
    await putJ(env, "sess:" + t, { n: num, exp: Date.now() + SESSION_HOURS * 3600 * 1000 }, { expirationTtl: SESSION_HOURS * 3600 });
    return json({ ok: true, token: t });
  }

  const me = await adminAuth(req, env);
  if (!me) return json({ ok: false, err: "auth" }, 401);
  const g = (await getJ(env, "g")) || { set: {} };

  if (path === "state") {
    const all = await Promise.all((await allNums(env)).map(n => getJ(env, "p:" + n)));
    const profiles = all.filter(Boolean).map(p => profileView(g, p));
    profiles.sort((a, c) => (c.admin - a.admin) || a.name.localeCompare(c.name, "bg"));
    return json({ ok: true, global: g.set || {}, profiles, adminNum: ADMIN_NUM });
  }

  if (path === "global") {
    await putJ(env, "g", { set: cleanSet(b.set) });
    return json({ ok: true });
  }

  if (path === "profile") {
    let p = null;
    const n = cleanStr(b.n, 12);
    if (n) {
      p = await getJ(env, "p:" + n);
      if (!p) return json({ ok: false, err: "missing" }, 404);
    } else {
      let num;
      for (let i = 0; i < 50; i++) {
        num = String(10000 + rnd(90000));
        if (num !== ADMIN_NUM && !(await getJ(env, "p:" + num))) break;
        num = null;
      }
      if (!num) return json({ ok: false, err: "full" }, 500);
      p = { n: num, name: "", pin: digits(4), admin: false, on: true, set: {}, at: Date.now() };
    }
    p.name = cleanStr(b.name, 40) || p.name || "Без име";
    p.on = p.admin ? true : b.on !== false;
    p.set = cleanSet(b.set);
    if (b.newPin) p.pin = digits(p.admin ? 6 : 4);
    await putJ(env, "p:" + p.n, p);
    return json({ ok: true, profile: profileView(g, p) });
  }

  if (path === "delete") {
    const n = cleanStr(b.n, 12);
    const p = await getJ(env, "p:" + n);
    if (!p) return json({ ok: false, err: "missing" }, 404);
    if (p.admin) return json({ ok: false, err: "admin" }, 400);
    await env.DB.delete("p:" + n);
    await env.DB.delete("lock:" + n);
    return json({ ok: true });
  }

  if (path === "check") {
    const items = (Array.isArray(b.items) ? b.items : []).slice(0, 30);
    const res = await Promise.all(items.map(it => probe(cleanStr(it.kind, 20), cleanStr(it.url, it.kind === "sites" ? 700 : 400))));
    return json({ ok: true, results: res });
  }

  return json({ ok: false, err: "unknown" }, 404);
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const ip = req.headers.get("cf-connecting-ip") || "0";
    try {
      if (url.pathname === "/api/sync" && req.method === "POST") return await sync(req, env, ip);
      if (url.pathname.startsWith("/admin/api/") && req.method === "POST") return await admin(req, env, url.pathname.slice(11), ip);
      if (url.pathname === "/admin" || url.pathname === "/admin/") {
        return new Response(PAGE, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
      }
      if (url.pathname === "/") return new Response("TorrTV sync", { headers: { "content-type": "text/plain; charset=utf-8" } });
      return new Response("Not found", { status: 404 });
    } catch (e) {
      return json({ ok: false, err: "server", msg: String(e && e.message || e).slice(0, 120) }, 500);
    }
  }
};

const PAGE = [
  "<!doctype html>\n",
  "<html lang=\"bg\">\n",
  "<head>\n",
  "<meta charset=\"utf-8\">\n",
  "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n",
  "<title>TorrTV – управление</title>\n",
  "<style>\n",
  ":root{--bg:#07090e;--bg2:#0e121a;--card:#141a26;--hi:#242e44;--text:#f4f1ea;--dim:#9aa3b6;--acc:#e9b44c;--acc2:#f2d58f;--green:#3ddc84;--red:#ff5c6c;--amber:#ffb020;--line:rgba(255,255,255,.13)}\n",
  "*{box-sizing:border-box}\n",
  "body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif}\n",
  ".wrap{max-width:860px;margin:0 auto;padding:16px 16px 60px}\n",
  "h1{font-size:20px;margin:6px 0 12px}\n",
  "h2{font-size:16px;margin:24px 0 6px;color:var(--acc2)}\n",
  ".card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px;margin:10px 0}\n",
  ".bar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:8px 0 14px}\n",
  ".sp{flex:1}\n",
  "input[type=text],input[type=password]{width:100%;background:var(--bg2);color:var(--text);border:1px solid var(--line);border-radius:10px;padding:10px;font:inherit}\n",
  "input:disabled{opacity:.55}\n",
  "button{background:var(--hi);color:var(--text);border:1px solid var(--line);border-radius:10px;padding:9px 14px;font:inherit;cursor:pointer}\n",
  "button:disabled{opacity:.5;cursor:default}\n",
  "button.p{background:var(--acc);color:#1a1305;border-color:transparent;font-weight:600}\n",
  "button.t{background:transparent}\n",
  "button.t.on{border-color:var(--acc);color:var(--acc2)}\n",
  "button.d{color:var(--red)}\n",
  "button.x{padding:6px 10px;color:var(--red)}\n",
  ".dim{color:var(--dim)}\n",
  ".hint{font-size:12px;color:var(--dim);margin-top:4px}\n",
  ".fld{margin:12px 0;padding-bottom:10px;border-bottom:1px solid var(--line)}\n",
  ".fld.dimmed{opacity:.55}\n",
  ".fh{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:5px}\n",
  ".fl{font-size:13px;color:var(--dim);font-weight:600}\n",
  ".own{font-size:12px;color:var(--dim);cursor:pointer;white-space:nowrap}\n",
  ".lr{display:flex;gap:6px;align-items:center;margin:5px 0}\n",
  "select{background:var(--bg2);color:var(--text);border:1px solid var(--line);border-radius:10px;padding:9px;font:inherit}\n",
  "select:disabled{opacity:.55}\n",
  ".site{border:1px solid var(--line);border-radius:12px;padding:4px 8px;margin:8px 0}\n",
  ".site summary{cursor:pointer;margin:2px 0 4px}\n",
  ".adv{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:6px;margin:6px 0 8px}\n",
  ".st:empty{display:none}\n",
  ".st{min-width:78px;font-size:12px;text-align:right;white-space:nowrap}\n",
  ".ok{color:var(--green)}.bad{color:var(--red)}.maybe{color:var(--amber)}\n",
  ".sw{display:flex;align-items:center;gap:8px;margin:6px 0;cursor:pointer}\n",
  ".row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}\n",
  ".big{font-size:30px;letter-spacing:4px;font-weight:700;color:var(--acc2)}\n",
  ".tag{display:inline-block;font-size:12px;padding:2px 8px;border-radius:99px;border:1px solid var(--line);color:var(--dim)}\n",
  ".tag.g{color:var(--green);border-color:var(--green)}\n",
  ".tag.r{color:var(--red);border-color:var(--red)}\n",
  ".warn{color:var(--amber);font-size:13px;margin-top:6px}\n",
  ".err{color:var(--red);margin:8px 0}\n",
  ".msg{color:var(--green);margin:8px 0}\n",
  "</style>\n",
  "</head>\n",
  "<body>\n",
  "<div class=\"wrap\" id=\"app\"></div>\n",
  "<script>\n",
  "(function () {\n",
  "\"use strict\";\n",
  "\n",
  "var STR = [\n",
  "  [\"tmdb_key\", \"TMDB – ключ\"],\n",
  "  [\"os_key\", \"OpenSubtitles – API ключ\"],\n",
  "  [\"os_user\", \"OpenSubtitles – потребител\"],\n",
  "  [\"os_pass\", \"OpenSubtitles – парола\"],\n",
  "  [\"subdl_key\", \"Subdl – API ключ\"],\n",
  "  [\"subsource_key\", \"SubSource – API ключ\"]\n",
  "];\n",
  "var DOMS = [\n",
  "  [\"dom_apibay\", \"The Pirate Bay\", \"apibay.org\"],\n",
  "  [\"dom_yts\", \"YTS\", \"yts.mx\"],\n",
  "  [\"dom_eztv\", \"EZTV\", \"eztvx.to\"],\n",
  "  [\"dom_nyaa\", \"Nyaa\", \"nyaa.si\"],\n",
  "  [\"dom_knaben\", \"Knaben\", \"api.knaben.org\"],\n",
  "  [\"dom_csv\", \"Torrents-CSV\", \"torrents-csv.com\"],\n",
  "  [\"dom_torrentsdb\", \"TorrentsDB\", \"torrentsdb.com\"],\n",
  "  [\"dom_torrentio\", \"Torrentio\", \"torrentio.strem.fun\"]\n",
  "];\n",
  "var SRC = [\n",
  "  [\"apibay\", \"The Pirate Bay\"], [\"yts\", \"YTS (само филми)\"], [\"eztv\", \"EZTV (само сериали)\"],\n",
  "  [\"nyaa\", \"Nyaa (аниме)\"], [\"knaben\", \"Knaben\"], [\"csv\", \"Torrents-CSV\"], \n",
  "  [\"torrentsdb\", \"TorrentsDB\"], [\"torrentio\", \"Torrentio\"]\n",
  "];\n",
  "var DEF_SITES = [\n",
  "  \"Magnetz|https://magnetz.eu/api/magnets/search?query={q}|json|data|name|info_hash|magnet_link|seeders|size\",\n",
  "  \"Bitsearch|https://bitsearch.eu/api/v1/search?q={q}|json\"\n",
  "];\n",
  "var DEF_MOVIE = [\"https://vidsrc.sh/embed/movie/{tmdb}\", \"https://vidcore.org/embed/movie/{tmdb}\", \"https://vidlink.pro/movie/{tmdb}\", \"https://vidspark.to/movie/{tmdb}\", \"https://vidsrc.me/embed/movie/{tmdb}\", \"https://vsembed.ru/embed/movie/{tmdb}\", \"https://yapgrid.com/embed/movie/{tmdb}\", \"https://embedwave.cc/embed/movie/{tmdb}\", \"https://embed.vidrift.net/embed/movie/{tmdb}\", \"https://cinesrc.st/embed/movie/{tmdb}\", \"https://vidfast.to/embed/movie/{tmdb}\", \"https://player.videasy.ws/embed/movie/{tmdb}\", \"https://stellar.rip/en/watch/embed/movie/{tmdb}\", \"https://flix.screenscape.me/embed?tmdb={tmdb}&type=movie\", \"https://vidy.st/movie/{tmdb}\", \"https://primesrc.me/embed/movie?tmdb={tmdb}\", \"https://autoembed.me/movie/tmdb/{tmdb}\", \"https://player.embedheaven.st/movie/{tmdb}\", \"https://streamdata.vaplayer.ru/api.php?tmdb={tmdb}&type=movie\"];\n",
  "var DEF_TV = [\"https://vidsrc.sh/embed/tv/{tmdb}/{season}/{episode}\", \"https://vidcore.org/embed/tv/{tmdb}/{season}/{episode}\", \"https://vidlink.pro/tv/{tmdb}/{season}/{episode}\", \"https://vidspark.to/tv/{tmdb}/{season}/{episode}\", \"https://vidsrc.me/embed/tv/{tmdb}/{season}/{episode}\", \"https://vsembed.ru/embed/tv/{tmdb}/{season}/{episode}\", \"https://yapgrid.com/embed/tv/{tmdb}/{season}/{episode}\", \"https://embedwave.cc/embed/tv/{tmdb}/{season}/{episode}\", \"https://embed.vidrift.net/embed/tv/{tmdb}/{season}/{episode}\", \"https://cinesrc.st/embed/tv/{tmdb}?s={season}&e={episode}\", \"https://vidfast.to/embed/tv/{tmdb}/{season}/{episode}\", \"https://player.videasy.ws/embed/tv/{tmdb}/{season}/{episode}\", \"https://stellar.rip/en/watch/embed/tv/{tmdb}-{season}-{episode}\", \"https://flix.screenscape.me/embed?tmdb={tmdb}&type=tv&s={season}&e={episode}\", \"https://vidy.st/tv/{tmdb}/{season}/{episode}\", \"https://primesrc.me/embed/tv?tmdb={tmdb}&season={season}&episode={episode}\", \"https://autoembed.me/tv/tmdb/{tmdb}-{season}-{episode}\", \"https://player.embedheaven.st/tv/{tmdb}/{season}/{episode}\", \"https://streamdata.vaplayer.ru/api.php?tmdb={tmdb}&type=tv&season={season}&episode={episode}\"];\n",
  "var DEF_TRK = [\n",
  "  \"udp://tracker.opentrackr.org:1337/announce\", \"udp://open.demonii.com:1337/announce\",\n",
  "  \"udp://tracker.torrent.eu.org:451/announce\", \"udp://open.stealth.si:80/announce\",\n",
  "  \"udp://exodus.desync.com:6969/announce\", \"udp://tracker.tiny-vps.com:6969/announce\",\n",
  "  \"udp://explodie.org:6969/announce\", \"udp://opentracker.i2p.rocks:6969/announce\",\n",
  "  \"udp://tracker.dler.org:6969/announce\", \"udp://p4p.arenabg.com:1337/announce\",\n",
  "  \"udp://tracker.bittor.pw:1337/announce\", \"udp://open.tracker.cl:1337/announce\"\n",
  "];\n",
  "\n",
  "var app = document.getElementById(\"app\");\n",
  "var token = \"\";\n",
  "try { token = sessionStorage.getItem(\"tt\") || \"\"; } catch (e) { token = \"\"; }\n",
  "var cache = null; // последното състояние от услугата\n",
  "var gone = {};    // изтрити профили: списъкът на KV може да закъснее с до минута\n",
  "\n",
  "function h(tag, attrs) {\n",
  "  var e = document.createElement(tag);\n",
  "  if (attrs) for (var k in attrs) {\n",
  "    var v = attrs[k];\n",
  "    if (k === \"class\") e.className = v;\n",
  "    else if (k === \"value\") e.value = v;\n",
  "    else if (k === \"checked\") e.checked = !!v;\n",
  "    else if (k === \"disabled\") e.disabled = !!v;\n",
  "    else if (k.indexOf(\"on\") === 0) e.addEventListener(k.slice(2), v);\n",
  "    else if (v !== false && v != null) e.setAttribute(k, v);\n",
  "  }\n",
  "  for (var i = 2; i < arguments.length; i++) add(e, arguments[i]);\n",
  "  return e;\n",
  "}\n",
  "function add(e, c) {\n",
  "  if (c == null || c === false) return;\n",
  "  if (Array.isArray(c)) { c.forEach(function (x) { add(e, x); }); return; }\n",
  "  e.appendChild(c.nodeType ? c : document.createTextNode(String(c)));\n",
  "}\n",
  "function show(nodes) { app.textContent = \"\"; add(app, nodes); }\n",
  "\n",
  "function api(path, body) {\n",
  "  return fetch(\"/admin/api/\" + path, {\n",
  "    method: \"POST\",\n",
  "    headers: { \"content-type\": \"application/json\", \"authorization\": \"Bearer \" + token },\n",
  "    body: JSON.stringify(body || {})\n",
  "  }).then(function (r) { return r.json().catch(function () { return { ok: false, err: \"server\" }; }); })\n",
  "    .then(function (j) {\n",
  "      if (j && j.err === \"auth\") { token = \"\"; try { sessionStorage.removeItem(\"tt\"); } catch (e) {} start(); throw new Error(\"auth\"); }\n",
  "      return j;\n",
  "    });\n",
  "}\n",
  "\n",
  "function ago(ms) {\n",
  "  if (!ms) return \"още не е влизал\";\n",
  "  var s = Math.floor((Date.now() - ms) / 1000);\n",
  "  if (s < 90) return \"току-що\";\n",
  "  var m = Math.floor(s / 60); if (m < 60) return \"преди \" + m + \" мин.\";\n",
  "  var hh = Math.floor(m / 60); if (hh < 36) return \"преди \" + hh + \" ч.\";\n",
  "  return \"преди \" + Math.floor(hh / 24) + \" дни\";\n",
  "}\n",
  "\n",
  "// ---------- Първоначална настройка и вход ----------\n",
  "\n",
  "function start() {\n",
  "  fetch(\"/admin/api/status\", { method: \"POST\", body: \"{}\" }).then(function (r) { return r.json(); }).then(function (j) {\n",
  "    if (!j.claimed) return claimView(j.num);\n",
  "    if (token) return loadMain();\n",
  "    loginView(j.num);\n",
  "  }).catch(function () { show(h(\"div\", { class: \"card err\" }, \"Няма връзка с услугата.\")); });\n",
  "}\n",
  "\n",
  "function claimView(num) {\n",
  "  var out = h(\"div\");\n",
  "  var btn = h(\"button\", { class: \"p\", onclick: function () {\n",
  "    btn.disabled = true;\n",
  "    fetch(\"/admin/api/claim\", { method: \"POST\", body: \"{}\" }).then(function (r) { return r.json(); }).then(function (j) {\n",
  "      if (!j.ok) { out.textContent = \"Вече е създаден админ профил.\"; return; }\n",
  "      show([h(\"h1\", null, \"Админ профилът е готов\"),\n",
  "        h(\"div\", { class: \"card\" },\n",
  "          h(\"div\", { class: \"dim\" }, \"Номер\"), h(\"div\", { class: \"big\" }, j.num),\n",
  "          h(\"div\", { class: \"dim\", style: \"margin-top:10px\" }, \"ПИН\"), h(\"div\", { class: \"big\" }, j.pin),\n",
  "          h(\"p\", { class: \"warn\" }, \"Запиши ги сега. После ПИН-ът се вижда и в списъка с профили, след като влезеш.\"),\n",
  "          h(\"button\", { class: \"p\", onclick: function () { loginView(j.num, j.pin); } }, \"Към входа\"))]);\n",
  "    });\n",
  "  } }, \"Създай админ профил\");\n",
  "  show([h(\"h1\", null, \"TorrTV – първоначална настройка\"),\n",
  "    h(\"div\", { class: \"card\" },\n",
  "      h(\"p\", null, \"Ще се създаде админ профил с номер \", h(\"b\", null, num), \" и ще получиш ПИН. Направи го веднага след като пуснеш услугата.\"),\n",
  "      btn, out)]);\n",
  "}\n",
  "\n",
  "function loginView(num, pin) {\n",
  "  var n = h(\"input\", { type: \"text\", value: num || \"\", inputmode: \"numeric\", autocomplete: \"off\" });\n",
  "  var p = h(\"input\", { type: \"password\", value: pin || \"\", inputmode: \"numeric\", autocomplete: \"off\" });\n",
  "  var msg = h(\"div\", { class: \"err\" });\n",
  "  function go() {\n",
  "    msg.textContent = \"\";\n",
  "    fetch(\"/admin/api/login\", { method: \"POST\", headers: { \"content-type\": \"application/json\" },\n",
  "      body: JSON.stringify({ n: n.value.trim(), p: p.value.trim() }) })\n",
  "      .then(function (r) { return r.json(); }).then(function (j) {\n",
  "        if (j.ok) { token = j.token; try { sessionStorage.setItem(\"tt\", token); } catch (e) {} loadMain(); return; }\n",
  "        msg.textContent = j.err === \"locked\" ? \"Заключено. Опитай след \" + Math.ceil(j.wait / 60) + \" мин.\"\n",
  "          : j.err === \"ip\" ? \"Твърде много грешни опити. Опитай по-късно.\" : \"Грешен номер или ПИН.\";\n",
  "      });\n",
  "  }\n",
  "  p.addEventListener(\"keydown\", function (e) { if (e.key === \"Enter\") go(); });\n",
  "  show([h(\"h1\", null, \"TorrTV – управление\"),\n",
  "    h(\"div\", { class: \"card\" },\n",
  "      h(\"div\", { class: \"fl\" }, \"Номер\"), n, h(\"div\", { class: \"fl\", style: \"margin-top:10px\" }, \"ПИН\"), p, msg,\n",
  "      h(\"div\", { class: \"bar\" }, h(\"button\", { class: \"p\", onclick: go }, \"Вход\")))]);\n",
  "}\n",
  "\n",
  "// ---------- Главен екран ----------\n",
  "\n",
  "function loadMain(tab, note) {\n",
  "  api(\"state\").then(function (j) {\n",
  "    j.profiles = j.profiles.filter(function (p) { return !gone[p.n]; });\n",
  "    cache = j; mainView(tab || \"profiles\", note);\n",
  "  }).catch(function () {});\n",
  "}\n",
  "\n",
  "function topBar(tab) {\n",
  "  return h(\"div\", { class: \"bar\" },\n",
  "    h(\"button\", { class: \"t\" + (tab === \"profiles\" ? \" on\" : \"\"), onclick: function () { mainView(\"profiles\"); } }, \"Профили\"),\n",
  "    h(\"button\", { class: \"t\" + (tab === \"global\" ? \" on\" : \"\"), onclick: function () { mainView(\"global\"); } }, \"Общи настройки\"),\n",
  "    h(\"div\", { class: \"sp\" }),\n",
  "    h(\"button\", { class: \"t\", onclick: function () { token = \"\"; try { sessionStorage.removeItem(\"tt\"); } catch (e) {} start(); } }, \"Изход\"));\n",
  "}\n",
  "\n",
  "function mainView(tab, note) {\n",
  "  if (tab === \"global\") return globalView();\n",
  "  var list = cache.profiles.map(profileCard);\n",
  "  show([h(\"h1\", null, \"TorrTV – управление\"), topBar(\"profiles\"),\n",
  "    note ? note : null,\n",
  "    h(\"div\", { class: \"bar\" }, h(\"button\", { class: \"p\", onclick: function () { editProfile(null); } }, \"+ Нов профил\")),\n",
  "    list.length ? list : h(\"div\", { class: \"dim\" }, \"Още няма профили.\")]);\n",
  "}\n",
  "\n",
  "function copyText(t) {\n",
  "  if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(t).catch(function () { window.prompt(\"Копирай:\", t); });\n",
  "  else window.prompt(\"Копирай:\", t);\n",
  "}\n",
  "\n",
  "function profileCard(p) {\n",
  "  var pinSpan = h(\"b\", null, \"••••\");\n",
  "  var shown = false;\n",
  "  var eye = h(\"button\", { class: \"t\", onclick: function () {\n",
  "    shown = !shown; pinSpan.textContent = shown ? p.pin : \"••••\"; eye.textContent = shown ? \"скрий\" : \"покажи\";\n",
  "  } }, \"покажи\");\n",
  "  return h(\"div\", { class: \"card\" },\n",
  "    h(\"div\", { class: \"row\" },\n",
  "      h(\"b\", null, p.name), h(\"span\", { class: \"tag\" }, \"№ \" + p.n),\n",
  "      p.admin ? h(\"span\", { class: \"tag\" }, \"админ\") : null,\n",
  "      h(\"span\", { class: \"tag \" + (p.on ? \"g\" : \"r\") }, p.on ? \"активен\" : \"изключен\")),\n",
  "    h(\"div\", { class: \"row\", style: \"margin-top:6px\" }, h(\"span\", { class: \"dim\" }, \"ПИН\"), pinSpan, eye),\n",
  "    h(\"div\", { class: \"dim\", style: \"margin-top:6px\" }, \"Последно: \" + ago(p.seen) + (p.ver ? \" · версия \" + p.ver : \"\")),\n",
  "    p.warn.length ? h(\"div\", { class: \"warn\" }, \"⚠ \" + p.warn.join(\", \")) : null,\n",
  "    h(\"div\", { class: \"bar\", style: \"margin-bottom:0;margin-top:10px\" },\n",
  "      h(\"button\", { onclick: function () { editProfile(p); } }, \"Редактирай\"),\n",
  "      h(\"button\", { onclick: function () { copyText(\"TorrTV\\nНомер: \" + p.n + \"\\nПИН: \" + p.pin); } }, \"Копирай номер и ПИН\"),\n",
  "      p.admin ? null : h(\"button\", { onclick: function () {\n",
  "        api(\"profile\", { n: p.n, name: p.name, on: !p.on, set: p.set }).then(function () { loadMain(\"profiles\"); });\n",
  "      } }, p.on ? \"Изключи\" : \"Включи\"),\n",
  "      p.admin ? null : h(\"button\", { class: \"d\", onclick: function () {\n",
  "        if (confirm(\"Да се изтрие профилът „\" + p.name + \"“?\")) api(\"delete\", { n: p.n }).then(function () { gone[p.n] = 1; loadMain(\"profiles\"); });\n",
  "      } }, \"Изтрий\")));\n",
  "}\n",
  "\n",
  "// ---------- Форма с полетата ----------\n",
  "\n",
  "function buildForm(set, base) {\n",
  "  var parts = [];\n",
  "  var root = h(\"div\");\n",
  "\n",
  "  function field(name, label, ctl, get, hint) {\n",
  "    var cb = null;\n",
  "    var own = !base || (name in set);\n",
  "    if (base) cb = h(\"input\", { type: \"checkbox\", checked: own });\n",
  "    var wrap = h(\"div\", { class: \"fld\" },\n",
  "      h(\"div\", { class: \"fh\" }, h(\"span\", { class: \"fl\" }, label),\n",
  "        base ? h(\"label\", { class: \"own\" }, cb, \" своя стойност\") : null),\n",
  "      ctl, hint ? h(\"div\", { class: \"hint\" }, hint) : null);\n",
  "    function sync() {\n",
  "      var on = !base || cb.checked;\n",
  "      wrap.classList.toggle(\"dimmed\", !on);\n",
  "      [].forEach.call(ctl.querySelectorAll(\"input,button,select\"), function (x) { x.disabled = !on; });\n",
  "    }\n",
  "    if (cb) cb.addEventListener(\"change\", sync);\n",
  "    sync();\n",
  "    parts.push({ name: name, get: get, use: function () { return !base || cb.checked; } });\n",
  "    return wrap;\n",
  "  }\n",
  "  function val(name, dflt) { return name in set ? set[name] : (base && name in base ? base[name] : dflt); }\n",
  "\n",
  "  function textCtl(name, ph) {\n",
  "    var i = h(\"input\", { type: \"text\", value: val(name, \"\"), placeholder: ph || \"\", autocomplete: \"off\" });\n",
  "    return { el: h(\"div\", null, i), get: function () { return i.value.trim(); } };\n",
  "  }\n",
  "\n",
  "  function listCtl(kind, items, defaults, ph) {\n",
  "    var rows = h(\"div\");\n",
  "    function addRow(v) {\n",
  "      var inp = h(\"input\", { type: \"text\", value: v || \"\", placeholder: ph || \"\", autocomplete: \"off\" });\n",
  "      var st = h(\"span\", { class: \"st\" });\n",
  "      var r = h(\"div\", { class: \"lr\" }, inp, st, h(\"button\", { class: \"x\", title: \"Махни\", onclick: function () { r.remove(); } }, \"✕\"));\n",
  "      rows.appendChild(r);\n",
  "    }\n",
  "    (items || []).forEach(addRow);\n",
  "    function values() {\n",
  "      return [].map.call(rows.querySelectorAll(\"input\"), function (i) { return i.value.trim(); }).filter(Boolean);\n",
  "    }\n",
  "    var chk = h(\"button\", { onclick: function () {\n",
  "      var rs = [].filter.call(rows.children, function (r) { return r.querySelector(\"input\").value.trim(); });\n",
  "      if (!rs.length) return;\n",
  "      chk.disabled = true; chk.textContent = \"Проверява се…\";\n",
  "      rs.forEach(function (r) { r.querySelector(\".st\").textContent = \"…\"; r.querySelector(\".st\").className = \"st dim\"; });\n",
  "      api(\"check\", { items: rs.slice(0, 30).map(function (r) { return { kind: kind, url: r.querySelector(\"input\").value.trim() }; }) })\n",
  "        .then(function (j) {\n",
  "          chk.disabled = false; chk.textContent = \"Провери\";\n",
  "          (j.results || []).forEach(function (res, i) {\n",
  "            var s = rs[i].querySelector(\".st\");\n",
  "            if (res.skip) { s.textContent = \"UDP – не се проверява\"; s.className = \"st dim\"; }\n",
  "            else if (res.ok) { s.textContent = \"✓ \" + res.ms + \" мс\"; s.className = \"st ok\"; }\n",
  "            else if (res.maybe) { s.textContent = \"? \" + res.status; s.className = \"st maybe\"; s.title = \"Сайтът не пуска проверката, но от телевизора може да върви\"; }\n",
  "            else { s.textContent = \"✗ \" + (res.status || \"няма връзка\"); s.className = \"st bad\"; }\n",
  "          });\n",
  "        }).catch(function () { chk.disabled = false; chk.textContent = \"Провери\"; });\n",
  "    } }, \"Провери\");\n",
  "    var el = h(\"div\", null, rows,\n",
  "      h(\"div\", { class: \"bar\", style: \"margin:6px 0 0\" },\n",
  "        h(\"button\", { onclick: function () { addRow(\"\"); } }, \"+ Добави\"), chk,\n",
  "        defaults ? h(\"button\", { onclick: function () {\n",
  "          if (confirm(\"Да се заменят със стандартните?\")) { rows.textContent = \"\"; defaults.forEach(addRow); }\n",
  "        } }, \"Стандартни\") : null));\n",
  "    return { el: el, get: values };\n",
  "  }\n",
  "\n",
  "  // Свои торент сайтове: ред = име|адрес с {q}|json или rss|списък|заглавие|хеш|магнит|сийдъри|размер\n",
  "  function siteCtl(items) {\n",
  "    var rows = h(\"div\");\n",
  "    var PH = [\"списък (напр. data.items)\", \"заглавие\", \"хеш\", \"магнит\", \"сийдъри\", \"размер\"];\n",
  "    function lineOf(r) {\n",
  "      var v = [].map.call(r.querySelectorAll(\"input\"), function (i) { return i.value.trim(); });\n",
  "      var out = [v[0], v[1], r.querySelector(\"select\").value].concat(v.slice(2));\n",
  "      while (out.length > 3 && !out[out.length - 1]) out.pop();\n",
  "      return out.join(\"|\");\n",
  "    }\n",
  "    function addRow(line) {\n",
  "      var p = String(line || \"\").split(\"|\");\n",
  "      while (p.length < 9) p.push(\"\");\n",
  "      var name = h(\"input\", { type: \"text\", value: p[0], placeholder: \"Име\", style: \"max-width:130px\", autocomplete: \"off\" });\n",
  "      var url = h(\"input\", { type: \"text\", value: p[1], placeholder: \"https://сайт/search?q={q}\", autocomplete: \"off\" });\n",
  "      var fmt = h(\"select\", null, h(\"option\", { value: \"json\" }, \"JSON\"), h(\"option\", { value: \"rss\" }, \"RSS\"));\n",
  "      fmt.value = p[2].toLowerCase() === \"rss\" ? \"rss\" : \"json\";\n",
  "      var st = h(\"span\", { class: \"st\" });\n",
  "      var adv = PH.map(function (ph, i) { return h(\"input\", { type: \"text\", value: p[3 + i], placeholder: ph, autocomplete: \"off\" }); });\n",
  "      var r = h(\"div\", { class: \"site\" },\n",
  "        h(\"div\", { class: \"lr\" }, name, url, fmt, st, h(\"button\", { class: \"x\", title: \"Махни\", onclick: function () { r.remove(); } }, \"✕\")),\n",
  "        h(\"details\", null, h(\"summary\", { class: \"hint\" }, \"Полета на отговора (по желание, иначе се познават сами)\"), h(\"div\", { class: \"adv\" }, adv)));\n",
  "      rows.appendChild(r);\n",
  "    }\n",
  "    (items || []).forEach(addRow);\n",
  "    function filled() {\n",
  "      return [].filter.call(rows.children, function (r) { var i = r.querySelectorAll(\"input\"); return i[0].value.trim() && i[1].value.trim(); });\n",
  "    }\n",
  "    var chk = h(\"button\", { onclick: function () {\n",
  "      var rs = filled();\n",
  "      if (!rs.length) return;\n",
  "      chk.disabled = true; chk.textContent = \"Проверява се…\";\n",
  "      rs.forEach(function (r) { var s = r.querySelector(\".st\"); s.textContent = \"…\"; s.className = \"st dim\"; s.title = \"\"; });\n",
  "      api(\"check\", { items: rs.slice(0, 20).map(function (r) { return { kind: \"sites\", url: lineOf(r) }; }) }).then(function (j) {\n",
  "        chk.disabled = false; chk.textContent = \"Провери\";\n",
  "        (j.results || []).forEach(function (res, i) {\n",
  "          var s = rs[i].querySelector(\".st\");\n",
  "          if (res.ok) { s.textContent = \"✓ \" + res.count + \" записа\"; s.className = \"st ok\"; }\n",
  "          else if (res.maybe) { s.textContent = \"? \" + res.status; s.className = \"st maybe\"; s.title = \"Сайтът не пуска проверката, но от телевизора може да върви\"; }\n",
  "          else { s.textContent = \"✗ \" + (res.note || res.status || \"няма връзка\"); s.className = \"st bad\"; }\n",
  "        });\n",
  "      }).catch(function () { chk.disabled = false; chk.textContent = \"Провери\"; });\n",
  "    } }, \"Провери\");\n",
  "    return {\n",
  "      el: h(\"div\", null, rows, h(\"div\", { class: \"bar\", style: \"margin:6px 0 0\" },\n",
  "        h(\"button\", { onclick: function () { addRow(\"\"); } }, \"+ Добави сайт\"), chk,\n",
  "        h(\"button\", { onclick: function () {\n",
  "          if (confirm(\"Да се заменят със стандартните?\")) { rows.textContent = \"\"; DEF_SITES.forEach(addRow); }\n",
  "        } }, \"Стандартни\"))),\n",
  "      get: function () { return filled().map(lineOf); }\n",
  "    };\n",
  "  }\n",
  "\n",
  "  function toggleCtl(off) {\n",
  "    var boxes = SRC.map(function (s) {\n",
  "      var cb = h(\"input\", { type: \"checkbox\", checked: off.indexOf(s[0]) < 0 });\n",
  "      return { id: s[0], cb: cb, el: h(\"label\", { class: \"sw\" }, cb, s[1]) };\n",
  "    });\n",
  "    return {\n",
  "      el: h(\"div\", null, boxes.map(function (b) { return b.el; })),\n",
  "      get: function () { return boxes.filter(function (b) { return !b.cb.checked; }).map(function (b) { return b.id; }); }\n",
  "    };\n",
  "  }\n",
  "\n",
  "  function bgProviderCtl(values) {\n",
  "    var providers = [[\"NINE\",\"Filmi9\"],[\"TWO_K\",\"Filmi2K\"],[\"ZENO\",\"ZenoPlay\"],[\"ALL\",\"Всички филми\"],[\"FEN\",\"FilmiFen\"],[\"ZT\",\"FilmiZT\"],[\"ZIP\",\"FilmiZip\"]];\n",
  "    var boxes = providers.map(function(p) {\n",
  "      var cb = h(\"input\", {type:\"checkbox\",checked:values.length ? values.indexOf(p[0]) >= 0 : true});\n",
  "      return {id:p[0],cb:cb,el:h(\"label\",{class:\"sw\"},cb,p[1])};\n",
  "    });\n",
  "    return {el:h(\"div\",null,boxes.map(function(b){return b.el;})),get:function(){\n",
  "      var selected=boxes.filter(function(b){return b.cb.checked;}).map(function(b){return b.id;});\n",
  "      return selected.length ? selected : [\"-\"];\n",
  "    }};\n",
  "  }\n",
  "\n",
  "  function section(title) { root.appendChild(h(\"h2\", null, title)); }\n",
  "  function put(name, label, c, hint) { root.appendChild(field(name, label, c.el, c.get, hint)); }\n",
  "\n",
  "  section(\"Ключове и акаунти\");\n",
  "  STR.forEach(function (s) { put(s[0], s[1], textCtl(s[0])); });\n",
  "\n",
  "  section(\"Торент сайтове\");\n",
  "  put(\"src_off\", \"Кои са включени\", toggleCtl(val(\"src_off\", [])), \"Развалените махни от отметката – няма да се търси в тях.\");\n",
  "  DOMS.forEach(function (d) {\n",
  "    put(d[0], d[1] + \" – домейн, който се пробва първи (по желание)\", textCtl(d[0], d[2]));\n",
  "  });\n",
  "  put(\"sites\", \"Свои торент сайтове (JSON или RSS)\", siteCtl(val(\"sites\", [])),\n",
  "    \"Празно – приложението ползва стандартните (Magnetz, Bitsearch). Адресът има {q} (думите за търсене) или {imdb}. „Провери“ пуска пробно търсене и показва колко записа се разчитат. Махни реда – сайтът изчезва.\");\n",
  "  put(\"addons\", \"Допълнителни торент източници (Stremio addon-и)\", listCtl(\"addons\", val(\"addons\", []), null, \"https://адрес-на-addon\"),\n",
  "    \"Нов торент източник се добавя тук като адрес на Stremio addon (като Torrentio). Махни реда – източникът изчезва.\");\n",
  "\n",
  "  section(\"Български уеб източници\");\n",
  "  put(\"web_bg_providers\", \"Включени каталози за БГ аудио\", bgProviderCtl(val(\"web_bg_providers\", [])),\n",
  "    \"Наличността се проверява от приложението за конкретния филм или епизод. Празно наследено поле включва всички; махнати всички отметки изключват проверките. Не е нужен нов API ключ.\");\n",
  "  section(\"Уеб сайтове за гледане\");\n",
  "  put(\"web_movie\", \"За филми\", listCtl(\"web_movie\", val(\"web_movie\", []), DEF_MOVIE, \"https://сайт/…{tmdb}\"),\n",
  "    \"Празно – приложението ползва стандартните. Могат да се ползват {tmdb}, {imdb}, {season}, {episode}.\");\n",
  "  put(\"web_tv\", \"За сериали\", listCtl(\"web_tv\", val(\"web_tv\", []), DEF_TV, \"https://сайт/…{tmdb}/{season}/{episode}\"),\n",
  "    \"Празно – стандартните. Редът е важен: първият се пробва първи.\");\n",
  "\n",
  "  section(\"Тракери\");\n",
  "  put(\"trackers\", \"Добавят се към торент линковете\", listCtl(\"trackers\", val(\"trackers\", []), DEF_TRK, \"udp://адрес:порт/announce\"),\n",
  "    \"Празно – стандартният списък. UDP тракерите не могат да се проверят оттук – има проверка в приложението (Диагностика).\");\n",
  "\n",
  "  return {\n",
  "    el: root,\n",
  "    collect: function () {\n",
  "      var out = {};\n",
  "      parts.forEach(function (p) { if (p.use()) out[p.name] = p.get(); });\n",
  "      return out;\n",
  "    }\n",
  "  };\n",
  "}\n",
  "\n",
  "// ---------- Редакция на профил и общи настройки ----------\n",
  "\n",
  "function editProfile(p) {\n",
  "  var isNew = !p;\n",
  "  var name = h(\"input\", { type: \"text\", value: p ? p.name : \"\", placeholder: \"Име, например „Иванов, хол“\" });\n",
  "  var on = h(\"input\", { type: \"checkbox\", checked: p ? p.on : true, disabled: !!(p && p.admin) });\n",
  "  var newPin = false;\n",
  "  var pinBtn = h(\"button\", { onclick: function () { newPin = true; pinBtn.textContent = \"Нов ПИН при запис\"; pinBtn.disabled = true; } }, \"Нов ПИН\");\n",
  "  var form = buildForm(p ? p.set : {}, cache.global);\n",
  "  var msg = h(\"div\", { class: \"err\" });\n",
  "  var save = h(\"button\", { class: \"p\", onclick: function () {\n",
  "    save.disabled = true;\n",
  "    api(\"profile\", { n: p ? p.n : \"\", name: name.value, on: on.checked, set: form.collect(), newPin: newPin }).then(function (j) {\n",
  "      if (!j.ok) { msg.textContent = \"Не се записа.\"; save.disabled = false; return; }\n",
  "      var np = j.profile;\n",
  "      api(\"state\").then(function (s) {\n",
  "        s.profiles = s.profiles.filter(function (x) { return !gone[x.n] && x.n !== np.n; });\n",
  "        s.profiles.push(np);\n",
  "        s.profiles.sort(function (a, c) { return (c.admin - a.admin) || a.name.localeCompare(c.name, \"bg\"); });\n",
  "        cache = s;\n",
  "        if (isNew || newPin) {\n",
  "          show([h(\"h1\", null, isNew ? \"Профилът е създаден\" : \"Новият ПИН\"), topBar(\"profiles\"),\n",
  "            h(\"div\", { class: \"card\" }, h(\"b\", null, np.name),\n",
  "              h(\"div\", { class: \"dim\", style: \"margin-top:8px\" }, \"Номер\"), h(\"div\", { class: \"big\" }, np.n),\n",
  "              h(\"div\", { class: \"dim\", style: \"margin-top:8px\" }, \"ПИН\"), h(\"div\", { class: \"big\" }, np.pin),\n",
  "              h(\"div\", { class: \"bar\", style: \"margin-top:12px\" },\n",
  "                h(\"button\", { class: \"p\", onclick: function () { copyText(\"TorrTV\\nНомер: \" + np.n + \"\\nПИН: \" + np.pin); } }, \"Копирай\"),\n",
  "                h(\"button\", { onclick: function () { mainView(\"profiles\"); } }, \"Към профилите\")))]);\n",
  "        } else mainView(\"profiles\", h(\"div\", { class: \"msg\" }, \"Записано.\"));\n",
  "      });\n",
  "    });\n",
  "  } }, \"Запази\");\n",
  "  show([h(\"h1\", null, isNew ? \"Нов профил\" : \"Профил „\" + p.name + \"“\"), topBar(\"profiles\"),\n",
  "    h(\"div\", { class: \"card\" },\n",
  "      h(\"div\", { class: \"fl\" }, \"Име\"), name,\n",
  "      p ? h(\"div\", { class: \"row\", style: \"margin-top:10px\" }, h(\"span\", { class: \"dim\" }, \"Номер\"), h(\"b\", null, p.n), h(\"span\", { class: \"dim\" }, \"ПИН\"), h(\"b\", null, p.pin), pinBtn) : null,\n",
  "      h(\"label\", { class: \"sw\", style: \"margin-top:10px\" }, on, \"Активен\")),\n",
  "    h(\"div\", { class: \"hint\" }, \"Полетата без отметка „своя стойност“ идват от Общите настройки.\"),\n",
  "    form.el, msg,\n",
  "    h(\"div\", { class: \"bar\" }, save, h(\"button\", { onclick: function () { mainView(\"profiles\"); } }, \"Отказ\"))]);\n",
  "}\n",
  "\n",
  "function globalView() {\n",
  "  var form = buildForm(cache.global, null);\n",
  "  var msg = h(\"div\");\n",
  "  var save = h(\"button\", { class: \"p\", onclick: function () {\n",
  "    save.disabled = true;\n",
  "    api(\"global\", { set: form.collect() }).then(function (j) {\n",
  "      save.disabled = false;\n",
  "      msg.className = j.ok ? \"msg\" : \"err\"; msg.textContent = j.ok ? \"Записано. Приложенията го получават при следващото обновяване.\" : \"Не се записа.\";\n",
  "      if (j.ok) api(\"state\").then(function (s) { cache = s; });\n",
  "    });\n",
  "  } }, \"Запази\");\n",
  "  show([h(\"h1\", null, \"Общи настройки\"), topBar(\"global\"),\n",
  "    h(\"div\", { class: \"hint\" }, \"Важат за всички профили, освен за полетата, които профилът е променил със „своя стойност“.\"),\n",
  "    form.el, msg, h(\"div\", { class: \"bar\" }, save)]);\n",
  "}\n",
  "\n",
  "start();\n",
  "})();\n",
  "</script>\n",
  "</body>\n",
  "</html>\n",
  ""
].join("");
