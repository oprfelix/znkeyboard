// znkeyboard.2amz.com: the website (static assets, the same files GitHub Pages serves), the latest
// installers from R2, and the anonymous usage counter.
//
//   GET  /download/latest.json            versions, files, sizes and SHA-256 of the latest installers
//   GET  /download/{android|macos|windows} the latest installer (counted as a download)
//   GET  /download/{platform}/{file}       that file, while it is the latest
//   POST /api/ping                         { id, platform, version, uses } once a day per install
//   GET  /api/stats                        users, active installs, downloads and uses, cached 5 min
//
// A ping carries a random id the app made, its platform, version and how many times it polished or
// translated since the last ping; never any text, and no IP is stored.
//
// Bindings: ASSETS (the site), DB (D1, schema.sql), DOWNLOADS (R2 bucket znkeyboard-downloads).

const PLATFORMS = new Set(["android", "macos", "windows"]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const VERSION = /^\d+(\.\d+){0,3}$/;
/** Uses one ping may add: a day of heavy use, not a script. */
const MAX_USES_PER_PING = 1000;
/** GitHub's own download counts are fetched again after this long. */
const GITHUB_REFRESH_MS = 6 * 3600_000;
const REPOS = ["znkeyboard", "Znkeyboard-for-macOS", "Znkeyboard-for-Windows"];
const CONTENT_TYPES = {
  ".apk": "application/vnd.android.package-archive",
  ".dmg": "application/x-apple-diskimage",
  ".exe": "application/vnd.microsoft.portable-executable",
};
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

export default {
  async fetch(request, env, ctx) {
    const { pathname } = new URL(request.url);
    if (pathname.startsWith("/api/") || pathname.startsWith("/download/")) {
      if (request.method === "OPTIONS") return new Response(null, { headers: CORS });
      if (pathname === "/api/ping" && request.method === "POST") return ping(request, env);
      if (pathname === "/api/stats" && request.method === "GET") return stats(request, env, ctx);
      if (pathname.startsWith("/download/") && (request.method === "GET" || request.method === "HEAD")) {
        return download(request, env, ctx, pathname.slice("/download/".length));
      }
      return json({ error: "not found" }, 404);
    }
    return env.ASSETS.fetch(request);
  },
};

// ---- Usage counter ----

/** Records that an install was used today, and how often since its last ping. */
async function ping(request, env) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "bad json" }, 400);
  }
  const id = String(body?.id ?? "").toLowerCase();
  const platform = String(body?.platform ?? "").toLowerCase();
  const version = String(body?.version ?? "");
  const uses = Math.min(Math.max(Math.floor(Number(body?.uses) || 0), 0), MAX_USES_PER_PING);
  if (!UUID.test(id) || !PLATFORMS.has(platform) || !VERSION.test(version)) return json({ error: "bad ping" }, 400);
  const today = new Date().toISOString().slice(0, 10);
  // `days` counts the different days an install was seen, for telling real use from one-off runs.
  await env.DB.prepare(
    `INSERT INTO installs (id, platform, version, first_seen, last_seen, days, uses) VALUES (?1, ?2, ?3, ?4, ?4, 1, ?5)
     ON CONFLICT(id) DO UPDATE SET platform = ?2, version = ?3, days = days + (last_seen <> ?4), last_seen = ?4, uses = uses + ?5`
  ).bind(id, platform, version, today, uses).run();
  return json({ ok: true });
}

/**
 * Users, active installs (30 days, today) per platform, downloads (GitHub and here) and uses.
 * Kept 5 minutes at the edge and 1 minute in browsers, so the page is never far behind.
 */
async function stats(request, env, ctx) {
  const cache = caches.default;
  const cached = await cache.match(request);
  if (cached) return cached;
  const daysAgo = (n) => new Date(Date.now() - n * 86_400_000).toISOString().slice(0, 10);
  const [installs, local, github] = await env.DB.batch([
    env.DB.prepare(
      `SELECT platform, COUNT(*) AS total, SUM(last_seen >= ?1) AS active30d, SUM(last_seen >= ?2) AS active1d,
              SUM(uses) AS uses
       FROM installs GROUP BY platform`
    ).bind(daysAgo(29), daysAgo(0)),
    env.DB.prepare(`SELECT COALESCE(SUM(count), 0) AS count FROM downloads`),
    env.DB.prepare(`SELECT value, updated FROM meta WHERE key = 'github_downloads'`),
  ]);
  const out = { users: 0, active30d: 0, active1d: 0, uses: 0, downloads: 0, platforms: {}, updated: new Date().toISOString() };
  for (const row of installs.results) {
    out.users += row.total;
    out.active30d += row.active30d;
    out.active1d += row.active1d;
    out.uses += row.uses ?? 0;
    out.platforms[row.platform] = { users: row.total, active30d: row.active30d };
  }
  const githubRow = github.results[0];
  out.downloads = local.results[0].count + Number(githubRow?.value ?? 0);
  if (!githubRow || Date.now() - Date.parse(githubRow.updated) > GITHUB_REFRESH_MS) ctx.waitUntil(refreshGitHubDownloads(env));
  const response = json(out, 200, { "Cache-Control": "public, max-age=60, s-maxage=300" });
  ctx.waitUntil(cache.put(request, response.clone()));
  return response;
}

/** Sums every release asset's download count across the three repositories; keeps the old total on failure. */
async function refreshGitHubDownloads(env) {
  let total = 0;
  for (const repo of REPOS) {
    for (let page = 1; page <= 10; page++) {
      const response = await fetch(`https://api.github.com/repos/oprfelix/${repo}/releases?per_page=100&page=${page}`, {
        headers: { "User-Agent": "znkeyboard-stats", Accept: "application/vnd.github+json" },
      });
      if (!response.ok) {
        console.log(`GitHub ${repo}: HTTP ${response.status} ${response.headers.get("x-ratelimit-remaining") ?? ""}`);
        return;
      }
      const releases = await response.json();
      for (const release of releases) for (const asset of release.assets ?? []) total += asset.download_count ?? 0;
      if (releases.length < 100) break;
    }
  }
  await env.DB.prepare(
    `INSERT INTO meta (key, value, updated) VALUES ('github_downloads', ?1, ?2)
     ON CONFLICT(key) DO UPDATE SET value = ?1, updated = ?2`
  ).bind(String(total), new Date().toISOString()).run();
}

// ---- Downloads ----

async function download(request, env, ctx, path) {
  if (path === "latest.json") {
    const object = await env.DOWNLOADS.get("latest.json");
    if (!object) return json({ error: "not found" }, 404);
    return new Response(object.body, {
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=300", ...CORS },
    });
  }
  const [platform, file] = path.split("/");
  if (!PLATFORMS.has(platform)) return json({ error: "not found" }, 404);
  const latest = await env.DOWNLOADS.get("latest.json").then((o) => o?.json());
  const entry = latest?.[platform];
  // Only the latest installer is kept here; older ones are on GitHub.
  if (!entry || (file && file !== entry.file)) {
    return Response.redirect(`https://github.com/oprfelix/${REPOS[["android", "macos", "windows"].indexOf(platform)]}/releases`, 302);
  }
  const key = `${platform}/${entry.file}`;
  const object = request.method === "HEAD" ? await env.DOWNLOADS.head(key) : await env.DOWNLOADS.get(key);
  if (!object) return json({ error: "not found" }, 404);
  if (request.method === "GET") {
    ctx.waitUntil(
      env.DB.prepare(`INSERT INTO downloads (platform, count) VALUES (?1, 1) ON CONFLICT(platform) DO UPDATE SET count = count + 1`)
        .bind(platform).run()
    );
  }
  const extension = entry.file.slice(entry.file.lastIndexOf("."));
  return new Response(request.method === "GET" ? object.body : null, {
    headers: {
      "Content-Type": CONTENT_TYPES[extension] ?? "application/octet-stream",
      "Content-Length": String(object.size),
      "Content-Disposition": `attachment; filename="${entry.file}"`,
      "Cache-Control": "public, max-age=300",
      ETag: object.httpEtag,
    },
  });
}

function json(value, status = 200, headers = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...CORS, ...headers },
  });
}
