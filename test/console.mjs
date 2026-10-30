#!/usr/bin/env node
/*
 * web/index.html 里那段纯逻辑（解析 / 行级编辑 / 校验）的测试。
 *
 * 重点是最后一组：把同一份配置同时喂给 JS 校验器和真实的 bin/wall.sh --check，
 * 两边结论必须一致。这两处逻辑一旦分叉，表现就是「页面说没问题，主机却不生效」，
 * 非常难查。
 *
 *   node test/console.mjs
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const HTML = path.join(ROOT, "web", "index.html");

/* ---------- 从 HTML 里抽出纯逻辑 ---------- */
function loadPure() {
  const src = fs.readFileSync(HTML, "utf8");
  const m = src.match(/\/\* ==PURE-BEGIN== \*\/([\s\S]*?)\/\* ==PURE-END== \*\//);
  if (!m) throw new Error("web/index.html 里没找到 ==PURE-BEGIN== / ==PURE-END== 标记");
  const factory = new Function(
    m[1] + "\nreturn { parseConf, entriesOf, formatEntry, applyEdit, validateEntries, validateText };"
  );
  return factory();
}

const { parseConf, entriesOf, formatEntry, applyEdit, validateEntries, validateText } = loadPure();

/* ---------- 迷你断言 ---------- */
let pass = 0;
const fails = [];

function eq(actual, expected, name) {
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a === b) {
    pass++;
  } else {
    fails.push(`${name}\n    期望 ${b}\n    实际 ${a}`);
  }
}
function ok(cond, name, detail) {
  if (cond) pass++;
  else fails.push(name + (detail ? `\n    ${detail}` : ""));
}
function group(title) {
  console.log(`\n${title}`);
}

/* ---------- 解析 ---------- */
group("解析");
{
  const text = [
    "# 头注释",
    "",
    "DP-1|https://a.example.com/x|1.0|0",
    "  HDMI-1 | https://b.example.com/y | 0.9 | 120  ",
    "# 中间注释",
    "DP-2|https://c.example.com/z||"
  ].join("\n");

  const rows = parseConf(text);
  eq(rows.length, 6, "行数（含注释和空行）");
  eq(rows[0].kind, "comment", "第一行是注释");
  eq(rows[1].kind, "blank", "第二行是空行");

  const es = entriesOf(rows);
  eq(es.length, 3, "有效配置 3 条");
  eq(es[1].iface, "HDMI-1", "字段前后空格被 trim");
  eq(es[1].zoom, "0.9", "缩放解析");
  eq(es[1].refresh, "120", "定时重启解析");
  eq(es[2].zoom, "", "空缩放");
}

{
  const rows = parseConf("DP-1|https://a/x|1.0|0\r\nHDMI-1|https://b/y|1.0|0\r\n");
  eq(entriesOf(rows).length, 2, "CRLF 换行也能解析");
  eq(entriesOf(rows)[0].refresh, "0", "CRLF 不会把 \\r 带进字段");
}

/* ---------- 序列化 ---------- */
group("序列化");
eq(formatEntry({ iface: "DP-1", url: "https://a/x", zoom: "", refresh: "" }), "DP-1|https://a/x", "尾部空字段去掉");
eq(formatEntry({ iface: "DP-1", url: "https://a/x", zoom: "0.9", refresh: "" }), "DP-1|https://a/x|0.9", "只去掉尾部空字段");
eq(formatEntry({ iface: "DP-1", url: "https://a/x", zoom: "", refresh: "120" }), "DP-1|https://a/x||120", "中间空字段保留");

/* ---------- 行级编辑 ---------- */
group("行级编辑（保留注释与顺序）");
{
  const text = [
    "# 这是头注释，不能丢",
    "",
    "DP-1|https://a/x|1.0|0",
    "# 这是 DP-2 的说明",
    "DP-2|https://c/z|1.0|0",
    ""
  ].join("\n");

  const out = applyEdit(text, "DP-1", { iface: "DP-1", url: "https://a/NEW", zoom: "1.0", refresh: "0" });
  const lines = out.split("\n");
  eq(lines[0], "# 这是头注释，不能丢", "头注释保留");
  eq(lines[1], "", "空行保留");
  eq(lines[2], "DP-1|https://a/NEW|1.0|0", "目标行被替换");
  eq(lines[3], "# 这是 DP-2 的说明", "中间注释保留");
  eq(lines[4], "DP-2|https://c/z|1.0|0", "其它行不动");
  eq(entriesOf(parseConf(out)).length, 2, "条数不变");
}
{
  const text = "DP-1|https://a/x|1.0|0\n";
  const out = applyEdit(text, "HDMI-1", { iface: "HDMI-1", url: "https://b/y", zoom: "", refresh: "" });
  eq(out, "DP-1|https://a/x|1.0|0\nHDMI-1|https://b/y\n", "追加到末尾，不多出空行");
}
{
  const text = "DP-1|https://a/x|1.0|0\nDP-2|https://c/z|1.0|0\n";
  const out = applyEdit(text, "DP-2", null);
  eq(out, "DP-1|https://a/x|1.0|0\n", "删除只删那一行");
}
{
  const text = "";
  const out = applyEdit(text, "X", { iface: "DP-1", url: "https://a/x", zoom: "", refresh: "" });
  eq(out, "DP-1|https://a/x\n", "空文件追加");
}
{
  // 接口名重复时，编辑只改第一条（和 wall.sh 的后一条覆盖前一条不同，
  // 但重复本身会被校验拦下，不会走到提交）
  const text = "DP-1|https://a/1|1.0|0\nDP-1|https://a/2|1.0|0\n";
  const out = applyEdit(text, "DP-1", { iface: "DP-1", url: "https://a/3", zoom: "", refresh: "" });
  eq(out.split("\n")[0], "DP-1|https://a/3", "重复时只改第一条");
  eq(out.split("\n")[1], "DP-1|https://a/2|1.0|0", "第二条原样保留");
}

/* ---------- 校验 ---------- */
group("校验");
const V = (t) => validateText(t).map((e) => e.msg);

ok(V("DP-1|https://a/x|1.0|0").length === 0, "合法配置通过");
ok(V("DP-1|http://a/x").length === 0, "省略缩放和重启也合法");
ok(V("DP-1|ftp://a/x").some((m) => m.includes("不是 http(s)")), "非 http(s) 被拦");
ok(V("DP-1|a/x").some((m) => m.includes("不是 http(s)")), "缺 scheme 被拦");
ok(V("DP-1|").some((m) => m.includes("缺少地址")), "缺地址被拦");
ok(V("DP-1|https://a/x|abc").some((m) => m.includes("缩放不是数字")), "缩放非数字被拦");
ok(V("DP-1|https://a/x|1.0|两小时").some((m) => m.includes("不是整数")), "定时重启非整数被拦");
ok(
  V("DP-1|https://a/x|1.0|0\nDP-1|https://a/y|1.0|0").some((m) => m.includes("重复")),
  "接口名重复被拦"
);
ok(V("# 只有注释\n\n").some((m) => m.includes("没有任何屏幕配置")), "空配置被拦");
ok(V("DP-1|https://a/x|0.9|120").length === 0, "0.9 和 120 合法");

/* ---------- 与 wall.sh 交叉验证 ---------- */
group("与 bin/wall.sh --check 交叉验证");

const WALL = path.join(ROOT, "bin", "wall.sh");
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), "walltest-"));
const BIN = path.join(TMP, "bin");
fs.mkdirSync(BIN, { recursive: true });
fs.writeFileSync(
  path.join(BIN, "xrandr"),
  '#!/bin/sh\nprintf "DP-1 connected primary 1920x1080+0+0 (normal) 527mm x 296mm\\nHDMI-1 connected 1920x1080+1920+0 (normal) 527mm x 296mm\\nDP-2 connected 1920x1080+3840+0 (normal) 527mm x 296mm\\n"\n',
  { mode: 0o755 }
);

function wallAccepts(text) {
  const home = path.join(TMP, "home");
  fs.rmSync(home, { recursive: true, force: true });
  fs.mkdirSync(home, { recursive: true });
  fs.writeFileSync(path.join(home, "screens.conf"), text);
  const out = execFileSync("bash", [WALL, "--check"], {
    env: {
      ...process.env,
      PATH: `${BIN}:${process.env.PATH}`,
      WALL_HOME: home,
      WALL_REPO: path.join(home, "norepo"),
      XDG_SESSION_TYPE: "x11",
    },
    encoding: "utf8",
  });
  return !out.includes("配置校验   未通过");
}

const cases = [
  ["合法三屏", "DP-1|https://a/1|1.0|0\nHDMI-1|https://a/2|1.0|120\nDP-2|https://a/3|0.9|0\n"],
  ["缺地址", "DP-1|\n"],
  ["缺 scheme", "DP-1|a/x|1.0|0\n"],
  ["缩放非数字", "DP-1|https://a/x|abc|0\n"],
  ["重启非整数", "DP-1|https://a/x|1.0|两小时\n"],
  ["接口名重复", "DP-1|https://a/1|1.0|0\nDP-1|https://a/2|1.0|0\n"],
  ["注释和空行夹杂", "# 说明\n\nDP-1|https://a/1|1.0|0\n# 中间\nHDMI-1|https://a/2\n"],
];

for (const [name, text] of cases) {
  const jsOk = validateText(text).length === 0;
  const bashOk = wallAccepts(text);
  ok(jsOk === bashOk, `结论一致：${name}`, `JS=${jsOk} wall.sh=${bashOk}`);
}

// 已知的有意分歧：wall.sh 的 validate_conf 不把"空文件"当错误，
// 但它的 reload_body 会因为"解析结果为空"拒绝应用。页面提前拦下，避免用户白提交。
{
  const text = "# 只有注释\n\n";
  const jsOk = validateText(text).length === 0;
  const bashOk = wallAccepts(text);
  ok(!jsOk && bashOk, "空配置：JS 更严格（有意为之，wall.sh 在 reload 时也会拒绝）", `JS=${jsOk} wall.sh=${bashOk}`);
}

fs.rmSync(TMP, { recursive: true, force: true });

/* ---------- 汇总 ---------- */
console.log("");
if (fails.length) {
  console.log(`✗ ${fails.length} 个失败，${pass} 个通过\n`);
  fails.forEach((f, i) => console.log(`  ${i + 1}. ${f}`));
  process.exit(1);
}
console.log(`✓ 全部 ${pass} 项通过`);
