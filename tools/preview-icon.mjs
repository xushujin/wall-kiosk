// 把生成的 VectorDrawable 反解成 SVG，用浏览器渲染，检查「XML 里的坐标/颜色」是否和设计一致。
//
//   node tools/gen-icon.mjs android/app/src/main/res/drawable
//   node tools/preview-icon.mjs
//   open -a "Google Chrome" /tmp/wall-kiosk-icon-preview.html
//
// 为什么需要这一步：预览稿好看 ≠ 写进 XML 的坐标对。这一步抓到过
//   - aapt:attr 没被识别 → 渐变全丢，渲染成一坨黑
//   - 两层 SVG 的 clipPath id 冲突 → 背景层整个消失
// 生成 icon-preview.png 也是用这个页面截的图。
//
// 只支持 gen-icon.mjs 会产出的子集：path / group / clip-path / radial gradient。
import fs from "node:fs";
import path from "node:path";

const RES = process.argv[3] || "android/app/src/main/res/drawable";
const OUT = process.argv[2] || "/tmp/wall-kiosk-icon-preview.html";

function xml(name) {
  const file = path.join(RES, name);
  if (!fs.existsSync(file)) throw new Error(`找不到 ${file}，先跑 tools/gen-icon.mjs`);
  return fs.readFileSync(file, "utf8");
}

const FILES = {
  background: xml("ic_launcher_background.xml"),
  foreground: xml("ic_launcher_foreground.xml"),
  monochrome: xml("ic_launcher_monochrome.xml"),
};

const html = `<!doctype html>
<html><head><meta charset="utf-8"><title>wall-kiosk 图标预览</title>
<style>
  body{margin:0;background:#0f1216;font:12px -apple-system,sans-serif;color:#8b95a3}
  .wrap{padding:20px;display:flex;flex-direction:column;gap:16px}
  .row{display:flex;align-items:center;gap:16px}
  .label{width:170px;text-align:right;font-size:11px}
  .cell{display:flex;flex-direction:column;align-items:center;gap:3px}
  .cap{font-size:10px;color:#525b68}
  .strip{display:flex;gap:14px;padding:14px 18px;border-radius:10px}
</style></head><body>
<div class="wrap" id="g"></div>
<script>
const FILES = ${JSON.stringify(FILES)};
const NS = "http://schemas.android.com/apk/res/android";
let uid = 0;

function argb(v) {
  const s = (v || "#00000000").replace("#", "");
  if (s.length === 8) return { color: "#" + s.slice(2), opacity: parseInt(s.slice(0, 2), 16) / 255 };
  return { color: "#" + s, opacity: 1 };
}

function convert(node, defs) {
  const tag = node.localName;
  if (tag === "vector") return null;

  if (tag === "group") {
    const clip = [...node.children].find((c) => c.localName === "clip-path");
    let attr = "";
    if (clip) {
      const id = "c" + uid++;
      defs.push('<clipPath id="' + id + '"><path d="' + clip.getAttributeNS(NS, "pathData") + '"/></clipPath>');
      attr = ' clip-path="url(#' + id + ')"';
    }
    const inner = [...node.children].filter((c) => c.localName !== "clip-path")
      .map((c) => convert(c, defs)).filter(Boolean).join("");
    return "<g" + attr + ">" + inner + "</g>";
  }

  if (tag === "path") {
    const d = node.getAttributeNS(NS, "pathData");
    let url = null, fill = node.getAttributeNS(NS, "fillColor"), fo = 1;
    const attr = [...node.children].find((c) => c.localName === "attr");
    if (attr) {
      const grad = [...attr.children].find((c) => c.localName === "gradient");
      if (grad) {
        const id = "g" + uid++;
        const stops = [...grad.children].filter((c) => c.localName === "item").map((it) => {
          const { color, opacity } = argb(it.getAttributeNS(NS, "color"));
          return '<stop offset="' + it.getAttributeNS(NS, "offset") + '" stop-color="' + color +
            '" stop-opacity="' + opacity + '"/>';
        }).join("");
        defs.push('<radialGradient id="' + id + '" gradientUnits="userSpaceOnUse" cx="' +
          grad.getAttributeNS(NS, "centerX") + '" cy="' + grad.getAttributeNS(NS, "centerY") +
          '" r="' + grad.getAttributeNS(NS, "gradientRadius") + '">' + stops + "</radialGradient>");
        url = "url(#" + id + ")";
      }
    }
    if (!url) { const p = argb(fill); fill = p.color; fo = p.opacity; }
    return '<path d="' + d + '"' + (url ? ' fill="' + url + '"' : ' fill="' + fill + '" fill-opacity="' + fo + '"') + "/>";
  }
  return null;
}

function render(text, maskKind, size) {
  const doc = new DOMParser().parseFromString(text, "application/xml");
  const defs = [];
  const u = "u" + uid++;
  const body = [...doc.documentElement.children].map((c) => convert(c, defs)).filter(Boolean).join("");
  const mask = maskKind === "circle"
    ? '<clipPath id="' + u + 'M"><circle cx="54" cy="54" r="36"/></clipPath>'
    : '<clipPath id="' + u + 'M"><rect x="18" y="18" width="72" height="72" rx="18"/></clipPath>';
  return '<svg width="' + size + '" height="' + size + '" viewBox="18 18 72 72"><defs>' +
    defs.join("") + mask + '</defs><g clip-path="url(#' + u + 'M)">' + body + "</g></svg>";
}

function composite(mask, size) {
  return '<div style="position:relative;width:' + size + 'px;height:' + size + 'px">' +
    '<div style="position:absolute;inset:0">' + render(FILES.background, mask, size) + "</div>" +
    '<div style="position:absolute;inset:0">' + render(FILES.foreground, mask, size) + "</div></div>";
}

const g = document.getElementById("g");
function row(label, html) {
  const r = document.createElement("div");
  r.className = "row";
  r.innerHTML = '<div class="label">' + label + "</div>" + html;
  g.appendChild(r);
}
const cell = (inner, cap) => '<div class="cell">' + inner + (cap ? '<div class="cap">' + cap + "</div>" : "") + "</div>";

row("圆 mask · 尺寸阶梯", [108, 72, 48, 32, 24]
  .map((s) => cell(composite("circle", s), String(s))).join(""));
row("圆角方 mask", [72, 48, 32]
  .map((s) => cell(composite("squircle", s), String(s))).join(""));
row("深 / 浅壁纸",
  '<div class="strip" style="background:#16181c">' + [72, 48].map((s) => cell(composite("circle", s))).join("") + "</div>" +
  '<div class="strip" style="background:#F2F4F7">' + [72, 48].map((s) => cell(composite("circle", s))).join("") + "</div>");
row("monochrome（主题图标）", [72, 48].map((s) =>
  '<div class="cell"><div style="background:#3E6DF0;border-radius:50%;display:inline-flex">' +
  render(FILES.monochrome, "circle", s) + "</div></div>").join(""));
</script></body></html>`;

fs.writeFileSync(OUT, html);
console.log(`已生成 ${OUT}`);
console.log("用 Chrome 打开它，逐个尺寸看；改了 drawable 就重跑本脚本。");
