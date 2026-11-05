// 生成 adaptive icon 的三个 vector 文件
import fs from "node:fs";

const n = (v) => {
  const s = Math.round(v * 100) / 100;
  return String(s);
};

// 圆角矩形 path
function rr(x, y, w, h, r) {
  return [
    `M${n(x + r)},${n(y)}`,
    `H${n(x + w - r)}`,
    `A${n(r)},${n(r)} 0 0 1 ${n(x + w)},${n(y + r)}`,
    `V${n(y + h - r)}`,
    `A${n(r)},${n(r)} 0 0 1 ${n(x + w - r)},${n(y + h)}`,
    `H${n(x + r)}`,
    `A${n(r)},${n(r)} 0 0 1 ${n(x)},${n(y + h - r)}`,
    `V${n(y + r)}`,
    `A${n(r)},${n(r)} 0 0 1 ${n(x + r)},${n(y)}`,
    "Z",
  ].join(" ");
}

// 椭圆（用两段弧）
function ellipse(cx, cy, rx, ry) {
  return [
    `M${n(cx - rx)},${n(cy)}`,
    `A${n(rx)},${n(ry)} 0 0 1 ${n(cx + rx)},${n(cy)}`,
    `A${n(rx)},${n(ry)} 0 0 1 ${n(cx - rx)},${n(cy)}`,
    "Z",
  ].join(" ");
}

// #RRGGBB + alpha → #AARRGGBB
function argb(hex, alpha) {
  const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255)
    .toString(16)
    .padStart(2, "0")
    .toUpperCase();
  return `#${a}${hex.replace("#", "").toUpperCase()}`;
}

const HEAD = `<?xml version="1.0" encoding="utf-8"?>
<!--
  柔光拟态：单一光源在左上，体积全靠明暗过渡，没有描边、没有分割线。
  所有坐标都是 108×108 画布，系统会把中心 72×72 放进 mask 视口。
  这个文件由 tools/gen-icon.mjs 生成，改参数后重跑，不要手改。
-->`;

/* ---------------- background：一块温润的浅色底 ---------------- */
const background = `${HEAD}
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path android:pathData="M0,0 H108 V108 H0 Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="44"
                android:centerY="42"
                android:gradientRadius="82">
                <item android:offset="0" android:color="#FFD9D4C9" />
                <item android:offset="0.55" android:color="#FFD0CABE" />
                <item android:offset="1" android:color="#FFC3BDB1" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
`;

/* ---------------- foreground：那块横躺的软材料 ---------------- */
const X = 25, Y = 38, W = 58, H = 32, R = 10;

// 外阴影：逐层放大 + alpha 递减，近似高斯模糊（vector 不支持 blur）
const shadowLayers = [
  [5.2, 0.032],
  [4.0, 0.047],
  [2.9, 0.060],
  [1.8, 0.074],
  [0.9, 0.084],
]
  .map(([d, a]) => {
    const x = X - d, y = Y - d + d * 0.5, w = W + d * 2, h = H + d * 2, r = R + d;
    return `    <path
        android:fillColor="${argb("#635B4B", a)}"
        android:pathData="${rr(x, y, w, h, r)}" />`;
  })
  .join("\n");

const foreground = `${HEAD}
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- 投在底上的柔和暗部，让这块材料"放"在托盘里 -->
${shadowLayers}

    <!-- 主体 -->
    <path android:pathData="${rr(X, Y, W, H, R)}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="${n(X + W * 0.26)}"
                android:centerY="${n(Y + H * 0.16)}"
                android:gradientRadius="${n(W * 1.25)}">
                <item android:offset="0" android:color="#FFF5F1E7" />
                <item android:offset="0.40" android:color="#FFE4DED1" />
                <item android:offset="1" android:color="#FFB0A898" />
            </gradient>
        </aapt:attr>
    </path>

    <group>
        <clip-path android:pathData="${rr(X, Y, W, H, R)}" />

        <!-- 左上高光 -->
        <path android:pathData="${rr(X, Y, W, H, R)}">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="radial"
                    android:centerX="${n(X + W * 0.28)}"
                    android:centerY="${n(Y + H * 0.10)}"
                    android:gradientRadius="${n(W * 0.9)}">
                    <item android:offset="0" android:color="${argb("#FFFFFF", 0.48)}" />
                    <item android:offset="0.45" android:color="${argb("#FFFFFF", 0.08)}" />
                    <item android:offset="1" android:color="${argb("#FFFFFF", 0)}" />
                </gradient>
            </aapt:attr>
        </path>

        <!-- 右下暗部 -->
        <path android:pathData="${rr(X, Y, W, H, R)}">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="radial"
                    android:centerX="${n(X + W * 1.02)}"
                    android:centerY="${n(Y + H * 1.10)}"
                    android:gradientRadius="${n(W * 0.85)}">
                    <item android:offset="0" android:color="${argb("#635B4B", 0.26)}" />
                    <item android:offset="0.5" android:color="${argb("#635B4B", 0.07)}" />
                    <item android:offset="1" android:color="${argb("#635B4B", 0)}" />
                </gradient>
            </aapt:attr>
        </path>

        <!-- 中间微鼓的弧面 -->
        <path android:pathData="${ellipse(54, Y + H * 0.40, W * 0.33, H * 0.40)}">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="radial"
                    android:centerX="${n(X + W * 0.28)}"
                    android:centerY="${n(Y + H * 0.10)}"
                    android:gradientRadius="${n(W * 0.9)}">
                    <item android:offset="0" android:color="${argb("#FFFFFF", 0.26)}" />
                    <item android:offset="1" android:color="${argb("#FFFFFF", 0)}" />
                </gradient>
            </aapt:attr>
        </path>
    </group>
</vector>
`;

/* ---------------- monochrome：Android 13 主题图标 ---------------- */
// 只留形状，系统自己上色。必须只有一块，否则主题图标会糊成一团。
const monochrome = `${HEAD}
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="${rr(X, Y, W, H, R)}" />
</vector>
`;

const out = process.argv[2];
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(`${out}/ic_launcher_background.xml`, background);
fs.writeFileSync(`${out}/ic_launcher_foreground.xml`, foreground);
fs.writeFileSync(`${out}/ic_launcher_monochrome.xml`, monochrome);

console.log("生成完成：");
for (const f of ["ic_launcher_background.xml", "ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"]) {
  const s = fs.readFileSync(`${out}/${f}`, "utf8");
  console.log(`  ${f}  ${s.length} 字节`);
}
console.log("\n主体 path:", rr(X, Y, W, H, R));
