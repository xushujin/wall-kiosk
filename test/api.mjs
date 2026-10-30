#!/usr/bin/env node
/*
 * 仓库读写适配层的测试：起一个假的 GitHub / GitLab API，验证
 *   - 列目录、读文件（含中文注释的 UTF-8 往返）
 *   - 写文件时 base64 编码、sha 透传、GitLab 的 POST/PUT 分支
 *   - 404 当成"文件不存在"而不是抛错
 *
 *   node test/api.mjs
 */
import fs from "node:fs";
import path from "node:path";
import http from "node:http";

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const HTML = path.join(ROOT, "web", "index.html");

function loadApi() {
  const src = fs.readFileSync(HTML, "utf8");
  const m = src.match(/\/\* ==API-BEGIN== \*\/([\s\S]*?)\/\* ==API-END== \*\//);
  if (!m) throw new Error("web/index.html 里没找到 ==API-BEGIN== / ==API-END== 标记");
  return new Function(m[1] + "\nreturn { ghAdapter, giteeAdapter, glAdapter, adapterFor, b64encode, b64decode };")();
}

const api = loadApi();

let pass = 0;
const fails = [];
const ok = (cond, name, detail) => {
  if (cond) pass++;
  else fails.push(name + (detail ? `\n    ${detail}` : ""));
};
const eq = (a, b, name) => ok(JSON.stringify(a) === JSON.stringify(b), name, `期望 ${JSON.stringify(b)} 实际 ${JSON.stringify(a)}`);

/* ---------- 假仓库 ---------- */
const CONF = "# 大厅的大屏配置 —— 中文注释也要能原样读回来\nDP-1|https://bi.example.com/sales|1.0|0\nHDMI-1|https://bi.example.com/prod|1.0|120\n";
const store = new Map([
  ["config/screens.conf", { text: CONF, sha: "sha-screens-1" }],
  ["config/hosts/lobby.conf", { text: "DP-1|https://a/1|1.0|0\n", sha: "sha-lobby-1" }],
]);

const seen = []; // 记录服务端收到的请求，用来断言

const server = http.createServer((req, res) => {
  const u = new URL(req.url, "http://x");
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    seen.push({ method: req.method, path: u.pathname, query: u.search, body });

    const json = (code, obj) => {
      res.writeHead(code, { "Content-Type": "application/json" });
      res.end(JSON.stringify(obj));
    };

    // ---------- GitHub Contents API ----------
    let m = u.pathname.match(/^\/repos\/o\/r\/contents\/(.+)$/);
    if (m) {
      const p = decodeURIComponent(m[1]);

      if (req.method === "GET") {
        if (p === "config/hosts") {
          return json(200, [
            { name: "lobby.conf", type: "file", path: "config/hosts/lobby.conf" },
            { name: "floor2.conf", type: "file", path: "config/hosts/floor2.conf" },
            { name: "README.md", type: "file", path: "config/hosts/README.md" },
          ]);
        }
        if (p === "config/nohosts") return json(404, { message: "Not Found" });
        const f = store.get(p);
        if (!f) return json(404, { message: "Not Found" });
        return json(200, { content: Buffer.from(f.text, "utf8").toString("base64"), sha: f.sha, encoding: "base64" });
      }

      if (req.method === "PUT") {
        const b = JSON.parse(body);
        if (b.sha && store.get(p) && store.get(p).sha !== b.sha) {
          return json(409, { message: "sha does not match" });
        }
        const text = Buffer.from(b.content, "base64").toString("utf8");
        const sha = "sha-new-" + (store.size + 1);
        store.set(p, { text, sha });
        return json(200, { content: { sha, path: p }, commit: { message: b.message } });
      }
    }

    // ---------- Gitee Contents API（形状同 GitHub，鉴权和写入方法不同）----------
    m = u.pathname.match(/^\/api\/v5\/repos\/([^/]+)\/([^/]+)\/contents\/(.+)$/);
    if (m) {
      const owner = decodeURIComponent(m[1]);
      const repoName = decodeURIComponent(m[2]);
      if (owner !== "o" || repoName !== "r") return json(404, { message: "Not Found" });
      if (req.method === "GET" && u.searchParams.get("access_token") !== "t0ken") {
        return json(401, { message: "401 Unauthorized" });
      }

      const p = decodeURIComponent(m[3]);

      if (req.method === "GET") {
        if (p === "config/hosts") {
          return json(200, [
            { name: "lobby.conf", type: "file", path: "config/hosts/lobby.conf", sha: "g-sha-1" },
          ]);
        }
        const f = store.get(p);
        if (!f) return json(404, { message: "Not Found" });
        return json(200, {
          type: "file",
          encoding: "base64",
          size: Buffer.byteLength(f.text, "utf8"),
          name: p.split("/").pop(),
          path: p,
          content: Buffer.from(f.text, "utf8").toString("base64"),
          sha: f.sha,
        });
      }

      if (req.method === "POST" || req.method === "PUT") {
        const b = JSON.parse(body);
        if (b.access_token !== "t0ken") return json(401, { message: "401 Unauthorized" });
        const exists = store.has(p);
        // Gitee：新建只能 POST，更新只能 PUT，走错方法会 400
        if (req.method === "POST" && exists) {
          return json(400, { message: "文件已存在，请用 PUT 更新" });
        }
        if (req.method === "PUT" && !exists) {
          return json(400, { message: "文件不存在，请用 POST 新建" });
        }
        if (req.method === "PUT" && b.sha && store.get(p).sha !== b.sha) {
          return json(409, { message: "sha does not match" });
        }
        const text = Buffer.from(b.content, "base64").toString("utf8");
        const sha = "g-sha-" + (store.size + 10);
        store.set(p, { text, sha });
        return json(201, { content: { sha, path: p }, commit: { message: b.message } });
      }
    }

    // ---------- GitLab Repository Files API ----------
    m = u.pathname.match(/^\/api\/v4\/projects\/([^/]+)\/repository\/(tree|files\/.+)$/);
    if (m) {
      const repo = decodeURIComponent(m[1]);
      if (repo !== "group/proj") return json(404, { message: "404 Project Not Found" });

      if (m[2] === "tree") {
        return json(200, [
          { name: "lobby.conf", type: "blob", path: "config/hosts/lobby.conf" },
        ]);
      }

      const raw = m[2].replace(/^files\//, "");
      const isRaw = raw.endsWith("/raw");
      const p = decodeURIComponent(isRaw ? raw.slice(0, -4) : raw);

      if (req.method === "GET" && isRaw) {
        const f = store.get(p);
        if (!f) {
          res.writeHead(404);
          return res.end("404");
        }
        res.writeHead(200, { "Content-Type": "text/plain" });
        return res.end(f.text);
      }

      if (req.method === "PUT" || req.method === "POST") {
        const b = JSON.parse(body);
        const exists = store.has(p);
        if (req.method === "PUT" && !exists) return json(400, { message: "A file with this name doesn't exist" });
        if (req.method === "POST" && exists) return json(400, { message: "A file with this name already exists" });
        store.set(p, { text: b.content, sha: "gl-" + (store.size + 1) });
        return json(200, { file_path: p, branch: b.branch });
      }
    }

    json(404, { message: "no route " + req.method + " " + u.pathname });
  });
});

await new Promise((r) => server.listen(0, "127.0.0.1", r));
const port = server.address().port;
const base = `http://127.0.0.1:${port}`;

/* ---------- base64 / UTF-8 ---------- */
{
  const s = "# 中文注释 —— 含 emoji 🔒\nDP-1|https://a/x|1.0|0\n";
  eq(api.b64decode(api.b64encode(s)), s, "UTF-8 经 base64 往返不变形");
}

/* ---------- GitHub ---------- */
const ghSettings = {
  provider: "github",
  baseUrl: base,
  repo: "o/r",
  branch: "main",
  token: "t0ken",
};

{
  const files = await api.ghAdapter.listDir(ghSettings, "config/hosts");
  eq(files.length, 3, "GitHub 列目录返回 3 个条目");
  eq(files[0].path, "config/hosts/lobby.conf", "路径正确");
}

{
  const missing = await api.ghAdapter.listDir(ghSettings, "config/nohosts");
  eq(missing, [], "404 的目录当成空目录，不抛错");
}

{
  const f = await api.ghAdapter.readFile(ghSettings, "config/screens.conf");
  ok(f.text === CONF, "GitHub 读回的文件内容完全一致（含中文注释）");
  eq(f.version, "sha-screens-1", "读回 sha");
}

{
  const f = await api.ghAdapter.readFile(ghSettings, "config/nope.conf");
  eq(f, null, "不存在的文件返回 null");
}

{
  const newText = CONF + "DP-2|https://bi.example.com/new|0.9|0\n";
  const v = await api.ghAdapter.writeFile(ghSettings, "config/screens.conf", newText, "配置: 加一屏", "sha-screens-1");
  eq(v, "sha-new-3", "写回返回新 sha");
  eq(store.get("config/screens.conf").text, newText, "服务端收到的内容正确");

  const last = seen[seen.length - 1];
  const sent = JSON.parse(last.body);
  eq(sent.message, "配置: 加一屏", "提交说明透传");
  eq(sent.sha, "sha-screens-1", "sha 透传（乐观锁）");
  eq(sent.branch, "main", "分支透传");
  eq(Buffer.from(sent.content, "base64").toString("utf8"), newText, "content 是正确的 base64(UTF-8)");
}

{
  // 带过期 sha 提交应该被服务端拒绝，适配层要把错误抛出来
  let threw = null;
  try {
    await api.ghAdapter.writeFile(ghSettings, "config/screens.conf", "x", "m", "sha-stale");
  } catch (e) {
    threw = e;
  }
  ok(threw && threw.status === 409, "sha 过期时抛错并带上状态码", String(threw));
}

/* ---------- GitLab ---------- */
// 上一段改过 screens.conf，这里重置，避免测试互相影响
store.set("config/screens.conf", { text: CONF, sha: "sha-screens-1" });

const glSettings = {
  provider: "gitlab",
  baseUrl: base + "/api/v4",
  repo: "group/proj",
  branch: "main",
  token: "glpat",
};

{
  const files = await api.glAdapter.listDir(glSettings, "config/hosts");
  eq(files.length, 1, "GitLab 列目录");
  eq(files[0].type, "file", "blob 映射成 file");
}

{
  const f = await api.glAdapter.readFile(glSettings, "config/screens.conf");
  ok(f.text === CONF, "GitLab 读回内容一致");
  eq(f.version, "exists", "GitLab 用 exists 当版本标记");
}

{
  const f = await api.glAdapter.readFile(glSettings, "config/missing.conf");
  eq(f, null, "GitLab 404 返回 null");
}

{
  const before = seen.length;
  await api.glAdapter.writeFile(glSettings, "config/screens.conf", "DP-1|https://a/1\n", "改地址", "exists");
  eq(seen[before].method, "PUT", "已存在的文件用 PUT");
  eq(store.get("config/screens.conf").text, "DP-1|https://a/1\n", "GitLab 内容已更新");
}

{
  const before = seen.length;
  await api.glAdapter.writeFile(glSettings, "config/hosts/new.conf", "DP-1|https://a/1\n", "新建", null);
  eq(seen[before].method, "POST", "新文件用 POST");
  ok(store.has("config/hosts/new.conf"), "新文件已创建");
}

/* ---------- Gitee ---------- */
// 形状和 GitHub 一样，但鉴权走 access_token 参数、新建和更新是两个方法
store.set("config/screens.conf", { text: CONF, sha: "sha-screens-1" });

const giteeSettings = {
  provider: "gitee",
  baseUrl: base + "/api/v5",
  repo: "o/r",
  branch: "main",
  token: "t0ken",
};

{
  const files = await api.giteeAdapter.listDir(giteeSettings, "config/hosts");
  eq(files.length, 1, "Gitee 列目录");
  eq(files[0].path, "config/hosts/lobby.conf", "Gitee 路径正确");
}

{
  const f = await api.giteeAdapter.readFile(giteeSettings, "config/screens.conf");
  ok(f.text === CONF, "Gitee 读回内容一致（含中文注释）");
  eq(f.version, "sha-screens-1", "Gitee 读回 sha");
}

{
  const f = await api.giteeAdapter.readFile(giteeSettings, "config/nope.conf");
  eq(f, null, "Gitee 404 返回 null");
}

{
  // 未带 access_token 的 GET 应该被服务端拒掉，说明适配层确实把 token 放进了 URL
  const res = await fetch(base + "/api/v5/repos/o/r/contents/config/screens.conf");
  eq(res.status, 401, "Gitee GET 必须带 access_token");
}

{
  const before = seen.length;
  const newText = CONF + "DP-2|https://bi.example.com/new|0.9|0\n";
  const sha = await api.giteeAdapter.writeFile(
    giteeSettings, "config/screens.conf", newText, "配置: 加一屏", "sha-screens-1"
  );
  eq(seen[before].method, "PUT", "Gitee 更新已存在的文件用 PUT");
  ok(!!sha && sha !== "sha-screens-1", "Gitee 更新后返回新 sha", String(sha));
  eq(store.get("config/screens.conf").text, newText, "Gitee 内容已更新");
}

{
  const before = seen.length;
  const sha = await api.giteeAdapter.writeFile(
    giteeSettings, "config/hosts/gitee-new.conf", "DP-1|https://a/1\n", "新建主机", null
  );
  eq(seen[before].method, "POST", "Gitee 新建文件用 POST");
  ok(store.has("config/hosts/gitee-new.conf"), "Gitee 新文件已创建");
  ok(!!sha, "Gitee 新建后拿到 sha，否则下次更新会走成新建", String(sha));
}

{
  // token 错了要报 401，不能静默成功
  let threw = null;
  try {
    await api.giteeAdapter.readFile({ ...giteeSettings, token: "wrong" }, "config/screens.conf");
  } catch (e) {
    threw = e;
  }
  ok(threw && threw.status === 401, "Gitee token 错误时抛 401", String(threw));
}

server.close();

/* ---------- 汇总 ---------- */
console.log("");
if (fails.length) {
  console.log(`✗ ${fails.length} 个失败，${pass} 个通过\n`);
  fails.forEach((f, i) => console.log(`  ${i + 1}. ${f}`));
  process.exit(1);
}
console.log(`✓ 全部 ${pass} 项通过`);
