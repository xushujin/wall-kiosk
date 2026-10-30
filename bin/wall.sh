#!/usr/bin/env bash
#
# wall.sh - 多屏大屏自动编排 + 配置热更新（Ubuntu + GNOME + X11/XWayland）
#
# 屏幕数量不固定，1 块、2 块、3 块都行：配置里有几行就起几个 kiosk，
# 没接的屏跳过并 WARN，不会影响其他屏。
#
#   ./wall.sh              正常启动：拉配置 -> 每块屏各起一个 kiosk -> 持续监视仓库
#   ./wall.sh --doctor     环境自检：会话类型、依赖、仓库凭据、配置语法、运行状态
#   ./wall.sh --check      打印屏幕几何、配置解析、运行状态，不启动浏览器
#   ./wall.sh --reload     立即拉一次仓库并只重启配置变了的屏幕（不用等轮询）
#   ./wall.sh --stop       停掉所有屏幕实例
#   ./wall.sh --supervise  外层守护，整个脚本挂掉后 10 秒重来（给 autostart 用）
#
# 环境变量：
#   WALL_HOME     运行时目录，默认 ~/.wall
#   WALL_REPO     配置仓库路径，默认 $WALL_HOME/repo
#   WALL_BROWSER  浏览器命令，默认 chromium
#   WALL_POLL     轮询间隔秒数，默认 30
#   WALL_PROBE    页面可达性探测，1=开（默认）0=关；没有 curl 时自动关
#
set -uo pipefail

WALL_HOME="${WALL_HOME:-$HOME/.wall}"
REPO_DIR="${WALL_REPO:-$WALL_HOME/repo}"
REPO_CONF="$REPO_DIR/config/screens.conf"
CONF="$WALL_HOME/screens.conf"

# 每台主机接几块屏不一定一样（1~3 块都可能），所以配置仓库支持按主机名分文件：
#   config/hosts/<hostname>.conf  存在就用它
#   config/screens.conf           否则用这个默认文件
# 做成函数而不是启动时算一次，是因为 hosts/ 下的文件可能是运行中 push 过来的，
# 重启才能认出来不符合"改完 push 就生效"的约定。
repo_conf() {
  local h
  h="$(hostname 2>/dev/null)"
  if [[ -n "$h" && -f "$REPO_DIR/config/hosts/$h.conf" ]]; then
    printf '%s' "$REPO_DIR/config/hosts/$h.conf"
  else
    printf '%s' "$REPO_CONF"
  fi
}

NORM="$WALL_HOME/.screens.norm"
NORM_NEW="$WALL_HOME/.screens.norm.new"
RUN_DIR="$WALL_HOME/run"
BROWSER="${WALL_BROWSER:-chromium}"
POLL="${WALL_POLL:-30}"
LOG_TAG="wall"

# 页面可达性探测：浏览器打不开地址时会停在 chromium 自己的错误页上，
# 进程还活着，光看 PID 会以为一切正常。用 curl 探一下才知道那块屏是不是废的。
# curl 在 Ubuntu 上基本必装，没有就自动关掉探测，不影响其他功能。
PROBE="${WALL_PROBE:-1}"
if ! command -v curl >/dev/null 2>&1; then
  PROBE=0
fi

# 凭据没配好时 git 会卡在密码提示上，开机流程直接冻住。
# 这三个变量让它立刻失败而不是等人来输密码。
export GIT_TERMINAL_PROMPT=0
export GIT_ASKPASS=/bin/true
export GIT_SSH_COMMAND="${GIT_SSH_COMMAND:-ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new}"

# 轮询间隔必须是正整数，否则 sleep 失败会变成忙等，把 CPU 打满
case "$POLL" in
  '' | *[!0-9]*) POLL=30 ;;
esac
[[ "$POLL" -gt 0 ]] || POLL=30

log()  { printf '%s [%s] %s\n' "$(date '+%F %T')" "$LOG_TAG" "$*"; }
warn() { log "WARN: $*"; }
die()  { log "FATAL: $*"; exit 1; }

trim() {
  local s="${1:-}"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

# 从 xrandr 取某个接口的几何：输出 "x y w h"，找不到返回 1
geom_of() {
  local want="$1" line
  while IFS= read -r line; do
    [[ $line =~ ^([A-Za-z0-9._-]+)\ connected\ (primary\ )?([0-9]+)x([0-9]+)\+(-?[0-9]+)\+(-?[0-9]+) ]] || continue
    [[ ${BASH_REMATCH[1]} == "$want" ]] || continue
    printf '%s %s %s %s\n' "${BASH_REMATCH[5]}" "${BASH_REMATCH[6]}" "${BASH_REMATCH[3]}" "${BASH_REMATCH[4]}"
    return 0
  done < <(xrandr --query 2>/dev/null)
  return 1
}

# 把配置文件规范化成 "接口|地址|缩放|重启" 四列，去掉注释、空行、多余空格
normalize_conf() {
  local file="$1" output url zoom refresh
  [[ -f "$file" ]] || return 0
  while IFS='|' read -r output url zoom refresh || [[ -n "${output:-}" ]]; do
    output="$(trim "${output:-}")"
    url="$(trim "${url:-}")"
    zoom="$(trim "${zoom:-}")"
    refresh="$(trim "${refresh:-}")"
    [[ -z "$output" || "$output" == \#* ]] && continue
    printf '%s|%s|%s|%s\n' "$output" "$url" "$zoom" "$refresh"
  done <"$file"
}

# 配置体检：缺地址、地址不是 http(s)、接口名重复，都拦下来不让它生效
validate_conf() {
  local file="$1" bad=0 output url zoom refresh dup
  if [[ ! -f "$file" ]]; then
    warn "配置文件不存在：$file"
    return 1
  fi

  while IFS='|' read -r output url zoom refresh || [[ -n "${output:-}" ]]; do
    output="$(trim "${output:-}")"
    url="$(trim "${url:-}")"
    [[ -z "$output" || "$output" == \#* ]] && continue

    if [[ -z "$url" ]]; then
      warn "配置 [$output] 缺少地址"
      bad=1
    elif [[ "$url" != http://* && "$url" != https://* ]]; then
      warn "配置 [$output] 的地址不是 http(s)：$url"
      bad=1
    fi

    if [[ -n "$zoom" && ! "$zoom" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
      warn "配置 [$output] 的缩放不是数字：$zoom"
      bad=1
    fi

    if [[ -n "$refresh" && ! "$refresh" =~ ^[0-9]+$ ]]; then
      warn "配置 [$output] 的定时重启不是整数：$refresh"
      bad=1
    fi
  done <"$file"

  dup="$(normalize_conf "$file" | cut -d'|' -f1 | sort | uniq -d)"
  if [[ -n "$dup" ]]; then
    warn "接口名重复（后一条会覆盖前一条）：$(printf '%s' "$dup" | tr '\n' ' ')"
    bad=1
  fi

  return "$bad"
}

# 浏览器日志超过 2MB 就截断，只留最后 500 行，防止长期运行撑爆磁盘
rotate_log() {
  local f="$1" sz
  [[ -f "$f" ]] || return 0
  sz="$(wc -c <"$f" 2>/dev/null || echo 0)"
  [[ "$sz" -gt 2097152 ]] || return 0
  tail -n 500 "$f" >"$f.tmp" 2>/dev/null && mv -f "$f.tmp" "$f"
  return 0
}

# 单实例锁：防止 autostart 重启时旧进程没死透，导致同一块屏开两个浏览器
LOCK_DIR=""

acquire_lock() {
  LOCK_DIR="$WALL_HOME/.lock.d"
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    local old
    old="$(cat "$LOCK_DIR/pid" 2>/dev/null)"
    if [[ -n "$old" ]] && kill -0 "$old" 2>/dev/null; then
      die "已有实例在运行（pid=${old}），先执行 --stop 再启动"
    fi
    warn "清理残留锁（pid=${old:-未知} 已不存在）"
    rm -rf "$LOCK_DIR"
    mkdir "$LOCK_DIR" 2>/dev/null || die "无法获取运行锁 $LOCK_DIR"
  fi
  printf '%s\n' "$$" >"$LOCK_DIR/pid"
}

release_lock() {
  if [[ -n "$LOCK_DIR" ]]; then
    rm -rf "$LOCK_DIR"
    LOCK_DIR=""
  fi
}

# 先撤掉 trap 再清理，避免连按两次 Ctrl+C 时重入
on_stop() {
  trap - EXIT TERM INT
  stop_all >/dev/null
  release_lock
  exit 0
}

# 首次拉取：失败就退回上次成功的本地副本，绝不因为断网黑屏
sync_config() {
  local i pulled=1 rc tries=30

  # 已经有上次的配置时只重试 5 次（10 秒）：网络没起来的时候不该为了拉一次
  # 配置让屏幕黑 60 秒，先按缓存亮屏，后面每轮轮询会自己跟上。
  # 一次都没成功过才值得等满 60 秒，因为除了等没有别的办法。
  [[ -f "$CONF" ]] && tries=5

  if [[ -d "$REPO_DIR/.git" ]]; then
    log "同步配置仓库 $REPO_DIR"
    for ((i = 1; i <= tries; i++)); do
      if git -C "$REPO_DIR" pull --ff-only --quiet >/dev/null 2>&1; then
        pulled=0
        log "已同步到 $(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null)"
        break
      fi
      sleep 2
    done
    [[ $pulled -eq 1 ]] && warn "git 拉取失败（已重试 $((tries * 2))s），使用本地缓存配置"
  else
    warn "未找到 git 仓库 ${REPO_DIR}，使用本地缓存配置"
  fi

  rc="$(repo_conf)"
  if [[ $pulled -eq 0 && -f "$rc" ]]; then
    cp -f "$rc" "$CONF"
    log "使用配置 ${rc#"$REPO_DIR"/}"
  fi

  [[ -f "$CONF" ]] || die "无可用配置：$CONF"
}

quiet_gnome() {
  command -v gsettings >/dev/null || return 0
  gsettings set org.gnome.desktop.session idle-delay 0                      2>/dev/null
  gsettings set org.gnome.desktop.screensaver lock-enabled false            2>/dev/null
  gsettings set org.gnome.desktop.screensaver idle-activation-enabled false 2>/dev/null
  gsettings set org.gnome.desktop.notifications show-banners false          2>/dev/null
  gsettings set org.gnome.desktop.interface enable-hot-corners false        2>/dev/null

  command -v xset >/dev/null && xset s off -dpms s noblank 2>/dev/null

  if command -v unclutter >/dev/null; then
    pkill -x unclutter 2>/dev/null
    unclutter -idle 0 -root >/dev/null 2>&1 &
  fi
  return 0
}

# 单个屏幕的守护循环：浏览器退出就重启，refresh 分钟到了主动重启防内存泄漏
# 收到 TERM 时先杀掉自己拉起的浏览器再退出，避免留下孤儿进程
watch_screen() {
  local output="$1" gx="$2" gy="$3" gw="$4" gh="$5"
  local url="$6" zoom="$7" refresh="$8"
  local profile="$WALL_HOME/profiles/$output"
  local bpid="" rpid="" args

  trap 'kill "$bpid" 2>/dev/null; exit 0' TERM INT

  mkdir -p "$profile"

  args=(
    "--app=$url"
    "--user-data-dir=$profile"
    "--window-position=$gx,$gy"
    "--window-size=$gw,$gh"
    "--start-fullscreen"
    "--ozone-platform=x11"
    "--password-store=basic"

    # --- 首次运行 / 默认浏览器相关 ---
    "--no-first-run"
    "--no-default-browser-check"
    "--disable-default-apps"
    "--disable-sync"
    "--disable-search-engine-choice-screen"

    # --- 升级与后台联网 ---
    # 升级提示框、"发现新版本"气泡的源头都是后台检查，全部掐掉。
    # 注意：这只管 Chromium 自带的检查，Google Chrome 的提示来自系统更新服务，
    # 命令行掐不掉，见 docs/deploy.md「关掉浏览器升级提示」。
    "--check-for-update-interval=31536000"
    "--disable-component-update"
    "--disable-background-networking"
    "--disable-component-extensions-with-background-pages"

    # --- 崩溃与错误 ---
    # 崩溃后不弹"恢复页面"、不弹崩溃上报框、进程出错不弹窗
    "--disable-session-crashed-bubble"
    "--hide-crash-restore-bubble"
    "--disable-breakpad"
    "--noerrdialogs"
    "--disable-prompt-on-repost"

    # --- 各种会盖住看板的浮层 ---
    "--disable-infobars"
    "--disable-features=Translate,TranslateUI,MediaRouter,OptimizationHints,HardwareMediaKeyHandling"

    # --- 保证看板一直活着 ---
    # 窗口被通知/弹窗遮住时不要把渲染降频，否则图表定时刷新会停
    "--disable-backgrounding-occluded-windows"
    "--disable-renderer-backgrounding"
    "--autoplay-policy=no-user-gesture-required"
  )
  if [[ -n "$zoom" && "$zoom" != "1" && "$zoom" != "1.0" ]]; then
    args+=("--force-device-scale-factor=$zoom")
  fi

  local started ran
  while :; do
    rotate_log "$WALL_HOME/$output.log"
    started=$SECONDS

    # 上一次被 kill -9 时 profile 里会留下 Singleton 锁，chromium 有时会因此
    # 直接拒绝启动，那块屏就永远黑着。确认没有活着的实例占着这个 profile 再清。
    if ! pgrep -f -- "user-data-dir=$profile" >/dev/null 2>&1; then
      rm -f "$profile"/Singleton* 2>/dev/null
    fi

    "$BROWSER" "${args[@]}" >>"$WALL_HOME/$output.log" 2>&1 &
    bpid=$!
    log "$output 启动 pid=$bpid -> $url"

    rpid=""
    if [[ -n "$refresh" && "$refresh" != "0" ]]; then
      ( sleep $((refresh * 60)); kill "$bpid" 2>/dev/null ) &
      rpid=$!
    fi

    wait "$bpid"
    [[ -n "$rpid" ]] && kill "$rpid" 2>/dev/null

    ran=$((SECONDS - started))
    if [[ $ran -lt 15 ]]; then
      log "$output 启动 ${ran}s 就退出，退避 30s（多半是地址打不开）"
      sleep 30
    else
      log "$output 已退出，5s 后重启"
      sleep 5
    fi
  done
}

start_screen() {
  local output="$1" url="$2" zoom="$3" refresh="$4"
  local geom gx gy gw gh actual cpid

  if ! geom="$(geom_of "$output")"; then
    # 常见错法是大小写不对（DP-1 写成 dp-1），直接告诉实际名字
    actual="$(xrandr --query 2>/dev/null | awk -v k="$output" \
      '$2 == "connected" && tolower($1) == tolower(k) { print $1; exit }')"
    if [[ -n "$actual" ]]; then
      warn "屏幕 $output 未连接；接口名大小写写错了，实际是 $actual"
    else
      warn "屏幕 $output 未连接，跳过"
    fi
    return 1
  fi
  read -r gx gy gw gh <<<"$geom"

  log "屏 $output -> ${gw}x${gh}+${gx}+${gy}  $url"
  rotate_log "$WALL_HOME/wall.log"
  # 子进程输出必须重定向到文件：否则它会继承调用方的 stdout，
  # 用管道调用时（如 wall.sh --reload | tail）管道永远等不到 EOF
  watch_screen "$output" "$gx" "$gy" "$gw" "$gh" "$url" "$zoom" "$refresh" \
    >>"$WALL_HOME/wall.log" 2>&1 &
  cpid=$!
  # disown 掉，避免子进程被杀时 bash 往日志里打 "Killed: 9" 之类的作业消息
  disown "$cpid" 2>/dev/null
  printf '%s\n' "$cpid" >"$RUN_DIR/$output.pid"
}

stop_screen() {
  local output="$1"
  local pf="$RUN_DIR/$output.pid" pid i
  [[ -f "$pf" ]] || return 0
  pid="$(cat "$pf")"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null
    for ((i = 0; i < 40; i++)); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.1
    done
    kill -9 "$pid" 2>/dev/null
  fi
  # watch_screen 被 -9 强杀时来不及清理自己的浏览器，会留下一个全屏窗口
  # 继续占着那块屏。按 profile 路径兜底清一遍。
  pkill -f -- "user-data-dir=$WALL_HOME/profiles/$output" 2>/dev/null
  rm -f "$pf" "$RUN_DIR/$output.down"
  return 0
}

# 全部一起发 TERM 再统一等待，避免逐个等造成关机拖十几秒
stop_all() {
  local f pid list="" i alive
  for f in "$RUN_DIR"/*.pid; do
    [[ -f "$f" ]] || continue
    pid="$(cat "$f")"
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null
    list="$list $pid"
  done

  for ((i = 0; i < 40; i++)); do
    alive=0
    for pid in $list; do
      kill -0 "$pid" 2>/dev/null && alive=1
    done
    [[ $alive -eq 0 ]] && break
    sleep 0.1
  done

  for f in "$RUN_DIR"/*.pid; do
    [[ -f "$f" ]] || continue
    pid="$(cat "$f")"
    kill -9 "$pid" 2>/dev/null
    rm -f "$f"
  done
  rm -f "$RUN_DIR"/*.down

  printf '%s' "$(printf '%s' "$list" | wc -w | tr -d ' ')"
}

# 按当前 $CONF 起所有屏幕
apply_all() {
  local output url zoom refresh
  while IFS='|' read -r output url zoom refresh || [[ -n "${output:-}" ]]; do
    output="$(trim "${output:-}")"
    url="$(trim "${url:-}")"
    zoom="$(trim "${zoom:-}")"
    refresh="$(trim "${refresh:-}")"
    [[ -z "$output" || "$output" == \#* ]] && continue
    if [[ -z "$url" ]]; then
      warn "配置行缺少 url：$output"
      continue
    fi
    start_screen "$output" "$url" "$zoom" "$refresh" || true
  done <"$CONF"
}

# 主进程轮询和手动 --reload 可能同时触发重载，用目录锁串行化。
# 不锁的话两边共用 screens.conf.new / .screens.norm.new，
# 会把对方写了一半的文件读走，表现为「所有屏都从配置里消失了」
reload_lock() {
  local d="$WALL_HOME/.reload.lock.d" i holder
  for ((i = 0; i < 100; i++)); do
    if mkdir "$d" 2>/dev/null; then
      printf '%s\n' "$$" >"$d/pid"
      return 0
    fi
    holder="$(cat "$d/pid" 2>/dev/null)"
    if [[ -n "$holder" ]] && ! kill -0 "$holder" 2>/dev/null; then
      rm -rf "$d"
      continue
    fi
    sleep 0.1
  done
  return 1
}

reload_unlock() {
  rm -rf "$WALL_HOME/.reload.lock.d"
}

reload_from_repo() {
  if ! reload_lock; then
    warn "另一个重载正在进行，跳过本次"
    return 1
  fi
  reload_body
  local rc=$?
  reload_unlock
  return $rc
}

# 显示器比主机慢启动时，xrandr 一开始只报一两块屏，要等它认全再开始。
#
# 但屏数不固定（1~3 块都可能），所以不能死等配置里写的数量：配置写了 3 行、
# 实际只接了 2 块屏时，死等等于每次开机白等 30 秒，最后还是只起 2 块。
# 策略：给显示器 10 秒上电时间；10 秒后连上的屏数还在原地不动，就认为
# 物理上就这么多了，按实际数量启动。
XRANDR_GRACE=10

wait_xrandr() {
  local i need have last=-1 stable=0

  need="$(normalize_conf "$CONF" 2>/dev/null | cut -d'|' -f1 | sort -u | wc -l | tr -d ' ')"
  if [[ -z "$need" || "$need" -eq 0 ]]; then
    need=1
  fi

  for ((i = 1; i <= 30; i++)); do
    have="$(xrandr --query 2>/dev/null | grep -cE '^[A-Za-z0-9._-]+ connected' || true)"
    have="${have:-0}"

    [[ "$have" -ge "$need" ]] && return 0

    if [[ "$have" -eq "$last" ]]; then
      stable=$((stable + 1))
    else
      stable=0
      last="$have"
    fi

    # 一块都没认出来说明显卡/驱动还没起来，继续等满 30 秒
    if [[ "$i" -ge "$XRANDR_GRACE" && "$have" -gt 0 && "$stable" -ge 2 ]]; then
      warn "配置写了 ${need} 块屏，实际稳定在 ${have} 块（等了 ${i}s），按实际数量启动"
      return 0
    fi

    sleep 1
  done
  warn "等待 ${need} 块屏就绪超时，当前只检测到 ${have:-0} 块"
  return 1
}

# 探一下地址到底通不通。只取状态码，不下载页面内容。
#
# 为什么要探测：浏览器打不开地址时**不会退出**，而是停在 chromium 自己的
# 错误页上。进程活着、PID 正常、日志干净，看上去一切正常，实际上那块屏
# 早就废了 —— 断网恢复、后端重启之后它也不会自己好，会一直停在错误页。
#
# 判断口径：
#   2xx / 3xx        正常
#   401 / 403        服务器活着，只是要登录，也算"通"（重启也没用）
#   404 / 5xx / 超时 不通
probe_url() {
  local url="$1" code
  # 先用 HEAD，省流量；少数后端不支持 HEAD（405/501），退回 GET 只要状态码
  code="$(curl -s -o /dev/null -m 4 -w '%{http_code}' -I "$url" 2>/dev/null)"
  case "$code" in
    405 | 501) code="$(curl -s -o /dev/null -m 4 -w '%{http_code}' -r 0-0 "$url" 2>/dev/null)" ;;
  esac
  [[ -n "$code" && "$code" != "000" ]] || return 1
  [[ "$code" =~ ^[23] || "$code" == "401" || "$code" == "403" ]]
}

# 巡检：某屏的守护进程被 OOM 之类干掉时，没人会拉起它。
# 只处理已有 PID 文件的屏，不碰配置里从没起来过的，避免刷日志。
reconcile() {
  local f o pid line url zoom refresh skip flag
  for f in "$RUN_DIR"/*.pid; do
    [[ -f "$f" ]] || continue
    o="$(basename "$f" .pid)"
    pid="$(cat "$f")"
    flag="$RUN_DIR/$o.down"

    if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
      line="$(normalize_conf "$CONF" 2>/dev/null | awk -F'|' -v k="$o" '$1 == k { print; exit }')"
      if [[ -z "$line" ]]; then
        rm -f "$f" "$flag"
        continue
      fi

      IFS='|' read -r skip url zoom refresh <<<"$line"
      log "屏 $o 的守护进程已消失，重新拉起"
      stop_screen "$o"
      start_screen "$o" "$url" "$zoom" "$refresh" || true
      continue
    fi

    # 进程活着，但可能正停在错误页上。探测到"之前不通、现在通了"就重启它。
    [[ "$PROBE" == "1" ]] || continue
    line="$(normalize_conf "$CONF" 2>/dev/null | awk -F'|' -v k="$o" '$1 == k { print; exit }')"
    [[ -n "$line" ]] || continue
    IFS='|' read -r skip url zoom refresh <<<"$line"
    [[ -n "$url" ]] || continue

    if probe_url "$url"; then
      if [[ -f "$flag" ]]; then
        log "屏 $o 的页面恢复可达，重启该屏清掉错误页"
        stop_screen "$o"
        start_screen "$o" "$url" "$zoom" "$refresh" || true
      fi
    elif [[ ! -f "$flag" ]]; then
      # 只记一次，不然断网期间每 30 秒刷一条
      warn "屏 $o 的页面不可达（${url}），先保留当前画面，恢复后自动重启"
      : >"$flag"
    fi
  done
}

# 用仓库里的新配置替换本地，只重启内容变了的屏幕，没变的完全不闪
reload_body() {
  local rc
  rc="$(repo_conf)"

  if [[ ! -f "$rc" ]]; then
    warn "仓库中缺少 ${rc#"$REPO_DIR"/}，保持现有配置"
    return 1
  fi

  cp -f "$rc" "$WALL_HOME/screens.conf.new"

  if ! validate_conf "$WALL_HOME/screens.conf.new"; then
    warn "新配置未通过校验，保持现有配置，不重启任何屏幕"
    rm -f "$WALL_HOME/screens.conf.new"
    return 1
  fi

  normalize_conf "$WALL_HOME/screens.conf.new" >"$NORM_NEW"

  # 防御：解析结果为空说明文件被截断或全是注释，不能拿它去停屏
  if [[ ! -s "$NORM_NEW" ]]; then
    warn "解析出的配置为空，拒绝应用，保持现有配置"
    rm -f "$WALL_HOME/screens.conf.new" "$NORM_NEW"
    return 1
  fi

  if [[ -f "$NORM" ]] && cmp -s "$NORM_NEW" "$NORM"; then
    log "配置内容无变化，不重启任何屏幕"
    rm -f "$WALL_HOME/screens.conf.new"
    return 0
  fi

  mv -f "$WALL_HOME/screens.conf.new" "$CONF"

  local outputs o old_line new_line url zoom refresh geom skip
  outputs="$(cat "$NORM" "$NORM_NEW" 2>/dev/null | cut -d'|' -f1 | sort -u)"

  for o in $outputs; do
    old_line="$(awk -F'|' -v k="$o" '$1==k{print;exit}' "$NORM" 2>/dev/null)"
    new_line="$(awk -F'|' -v k="$o" '$1==k{print;exit}' "$NORM_NEW" 2>/dev/null)"
    [[ "$old_line" == "$new_line" ]] && continue

    if [[ -z "$new_line" ]]; then
      log "屏 $o 已从配置中移除，停止"
      stop_screen "$o"
      continue
    fi

    IFS='|' read -r skip url zoom refresh <<<"$new_line"
    log "屏 $o 配置变更 -> $url"
    stop_screen "$o"
    if geom="$(geom_of "$o")"; then
      start_screen "$o" "$url" "$zoom" "$refresh"
    else
      warn "屏 $o 未连接，跳过"
    fi
  done

  mv -f "$NORM_NEW" "$NORM"
  log "配置重载完成"
}

# 持续轮询远端，HEAD 变了才拉取并热更新
watch_repo() {
  local last_head new_head
  last_head="$(git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null)"

  log "开始监视配置仓库，每 ${POLL}s 检查一次，当前版本 ${last_head:0:7}"

  while :; do
    sleep "$POLL"
    reconcile

    git -C "$REPO_DIR" fetch --quiet >/dev/null 2>&1 || continue

    new_head="$(git -C "$REPO_DIR" rev-parse '@{u}' 2>/dev/null)"
    [[ -z "$new_head" ]] && continue
    [[ "$new_head" == "$last_head" ]] && continue

    log "远端有新提交 ${last_head:0:7} -> ${new_head:0:7}"

    if ! git -C "$REPO_DIR" merge --ff-only --quiet '@{u}' >/dev/null 2>&1; then
      warn "无法快进合并（可能被 force push），强制对齐远端"
      if ! git -C "$REPO_DIR" reset --hard '@{u}' >/dev/null 2>&1; then
        warn "对齐失败，等下次轮询重试"
        continue
      fi
    fi

    last_head="$new_head"
    reload_from_repo
  done
}

ok()  { printf '  [OK]  %s\n' "$*"; }
bad() { printf '  [!!]  %s\n' "$*"; }

# 部署后第一件事就该跑这个：把「机器没准备好」和「配置写错了」分开
do_doctor() {
  local fail=0 n f o p found=0

  printf '\n== 环境 ==\n'
  printf '  系统        %s\n' "$(uname -srm 2>/dev/null)"
  printf '  主机名      %s\n' "$(hostname 2>/dev/null)"
  printf '  WALL_HOME   %s\n' "$WALL_HOME"
  printf '  配置仓库    %s\n' "$REPO_DIR"
  printf '  DISPLAY     %s\n' "${DISPLAY:-未设置}"

  if [[ "${XDG_SESSION_TYPE:-}" == "x11" ]]; then
    ok "图形会话是 X11"
  else
    bad "图形会话是 ${XDG_SESSION_TYPE:-未知}，不是 X11 —— 窗口定位会失效"
    fail=1
  fi

  # 会话没起全时 DISPLAY 是空的，xrandr 会直接失败，启动会卡在这一步。
  # 但 SSH 进来跑 --doctor 时它本来就是空的，别当成故障报。
  if [[ -n "${DISPLAY:-}" ]]; then
    ok "DISPLAY=$DISPLAY"
  elif [[ "${XDG_SESSION_TYPE:-}" == "x11" ]]; then
    bad "会话是 x11 但 DISPLAY 为空，环境不完整（自启动太早？10 秒后会自动重试）"
    fail=1
  else
    printf '  [--]  DISPLAY 未设置（SSH 里跑会这样，进图形会话再跑一次更准）\n'
  fi

  if command -v xrandr >/dev/null; then
    n="$(xrandr --query 2>/dev/null | grep -cE '^[A-Za-z0-9._-]+ connected' || true)"
    if [[ "${n:-0}" -ge 1 ]]; then
      ok "xrandr 可用，检测到 ${n} 块已连接显示器"
    else
      bad "xrandr 可用但没有已连接的显示器"
      fail=1
    fi
  else
    bad "缺少 xrandr（apt install x11-xserver-utils）"
    fail=1
  fi

  if command -v "$BROWSER" >/dev/null; then
    ok "浏览器 $BROWSER 可用"
  else
    bad "找不到浏览器 ${BROWSER}（apt install chromium-browser）"
    fail=1
  fi

  if command -v git >/dev/null; then
    ok "git 可用"
  else
    bad "缺少 git"
    fail=1
  fi

  if [[ -d "$REPO_DIR/.git" ]]; then
    if git -C "$REPO_DIR" ls-remote --heads origin >/dev/null 2>&1; then
      ok "配置仓库可免密访问"
    else
      bad "配置仓库无法免密访问，热更新会一直静默失败（见 docs/deploy.md 凭据章节）"
      fail=1
    fi
  else
    bad "配置仓库不存在或不是 git 仓库：$REPO_DIR"
    fail=1
  fi

  printf '\n== 配置 ==\n'
  if [[ -f "$CONF" ]]; then
    ok "生效配置 $CONF"
    if validate_conf "$CONF"; then
      ok "配置语法检查通过"
    else
      bad "配置有问题，见上面的警告"
      fail=1
    fi
  else
    bad "生效配置不存在 $CONF"
    fail=1
  fi

  printf '\n== 可选组件 ==\n'
  if command -v unclutter >/dev/null; then
    ok "unclutter 已安装（隐藏鼠标）"
  else
    printf '  [--]  unclutter 未安装，鼠标会停在大屏上（apt install unclutter）\n'
  fi
  if command -v xset >/dev/null; then
    ok "xset 已安装（关屏保）"
  else
    printf '  [--]  xset 未安装，屏幕可能自动黑屏（apt install x11-xserver-utils）\n'
  fi
  if command -v curl >/dev/null; then
    ok "curl 已安装（页面可达性探测，断网恢复后能自动清掉错误页）"
  else
    printf '  [--]  curl 未安装，关闭页面探测；断网恢复后停在错误页的屏不会自愈（apt install curl）\n'
  fi

  printf '\n== 运行状态 ==\n'
  for f in "$RUN_DIR"/*.pid; do
    [[ -f "$f" ]] || continue
    found=1
    o="$(basename "$f" .pid)"
    p="$(cat "$f")"
    if ! kill -0 "$p" 2>/dev/null; then
      bad "屏 $o 已退出 pid=$p"
    elif [[ -f "$RUN_DIR/$o.down" ]]; then
      bad "屏 $o 进程在跑，但页面不可达（可能停在错误页）pid=$p"
      fail=1
    else
      ok "屏 $o 运行中 pid=$p"
    fi
  done
  [[ $found -eq 0 ]] && printf '  [--]  当前没有屏幕实例在运行\n'

  printf '\n'
  if [[ $fail -eq 0 ]]; then
    printf '结论：环境检查通过\n\n'
  else
    printf '结论：有检查项未通过，按上面 [!!] 逐条处理\n\n'
  fi
  return "$fail"
}

do_check() {
  local rc need have
  rc="$(repo_conf)"

  log "运行时目录 $WALL_HOME"
  log "配置仓库   $REPO_DIR"
  log "轮询间隔   ${POLL}s"
  if [[ -d "$REPO_DIR/.git" ]]; then
    log "仓库版本   $(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || echo 未知)"
  fi

  if [[ -f "$rc" ]]; then
    log "仓库配置   ${rc#"$REPO_DIR"/}"
  else
    warn "仓库配置缺失 ${rc#"$REPO_DIR"/}"
  fi
  if [[ -f "$CONF" ]]; then
    log "生效配置   $CONF"
  else
    warn "尚无生效配置"
  fi

  log "已连接显示器："
  xrandr --query 2>/dev/null | while IFS= read -r line; do
    [[ $line =~ ^([A-Za-z0-9._-]+)\ connected ]] && printf '  %s\n' "$line"
  done

  need="$(normalize_conf "$CONF" 2>/dev/null | cut -d'|' -f1 | sort -u | wc -l | tr -d ' ')"
  have="$(xrandr --query 2>/dev/null | grep -cE '^[A-Za-z0-9._-]+ connected' || true)"
  if [[ "${need:-0}" -eq "${have:-0}" ]]; then
    log "屏数       配置 ${need:-0} 块 / 实际连接 ${have:-0} 块"
  else
    warn "屏数       配置 ${need:-0} 块 / 实际连接 ${have:-0} 块，不一致的屏会被跳过"
  fi

  log "配置解析："
  if [[ -f "$CONF" ]]; then
    local output url zoom refresh geom
    while IFS='|' read -r output url zoom refresh || [[ -n "${output:-}" ]]; do
      output="$(trim "${output:-}")"
      url="$(trim "${url:-}")"
      [[ -z "$output" || "$output" == \#* ]] && continue
      if geom="$(geom_of "$output")"; then
        printf '  %-8s %-18s %s\n' "$output" "$geom" "$url"
      else
        printf '  %-8s %-18s %s  [未连接]\n' "$output" "-" "$url"
      fi
    done <"$CONF"

    if validate_conf "$CONF"; then
      log "配置校验   通过"
    else
      warn "配置校验   未通过，见上面的 WARN"
    fi
  else
    printf '  （无生效配置）\n'
  fi

  log "运行状态："
  local f o p
  for f in "$RUN_DIR"/*.pid; do
    [[ -f "$f" ]] || continue
    o="$(basename "$f" .pid)"
    p="$(cat "$f")"
    if kill -0 "$p" 2>/dev/null; then
      printf '  %-8s pid=%-7s 运行中\n' "$o" "$p"
    else
      printf '  %-8s pid=%-7s 已退出\n' "$o" "$p"
    fi
  done
}

do_reload() {
  mkdir -p "$RUN_DIR"

  local main_pid
  main_pid="$(cat "$WALL_HOME/.lock.d/pid" 2>/dev/null)"
  if [[ -z "$main_pid" ]] || ! kill -0 "$main_pid" 2>/dev/null; then
    warn "主进程未在运行，本次重载起的实例不受守护管理；建议直接执行 wall.sh 启动"
  fi

  if [[ -d "$REPO_DIR/.git" ]]; then
    git -C "$REPO_DIR" fetch --quiet 2>/dev/null || warn "fetch 失败，使用本地已有提交"
    git -C "$REPO_DIR" merge --ff-only --quiet '@{u}' >/dev/null 2>&1 \
      || git -C "$REPO_DIR" reset --hard '@{u}' >/dev/null 2>&1 \
      || warn "无法更新到远端最新，使用本地提交"
  else
    warn "仓库不是 git 仓库，直接按现有文件重载"
  fi

  [[ -f "$NORM" ]] || normalize_conf "$CONF" >"$NORM"
  reload_from_repo
}

main() {
  mkdir -p "$WALL_HOME/profiles" "$RUN_DIR"

  acquire_lock
  trap on_stop EXIT TERM INT
  stop_all >/dev/null

  sync_config
  quiet_gnome

  if ! wait_xrandr; then
    xrandr --query >/dev/null 2>&1 \
      || die "xrandr 不可用，确认当前是 X11 会话（echo \$XDG_SESSION_TYPE）"
  fi

  # 首次启动没有旧配置可退回，校验不过也只警告，能起几块是几块
  validate_conf "$CONF" || warn "配置存在上述问题，仍按可解析的部分启动"

  normalize_conf "$CONF" >"$NORM"
  apply_all

  local n
  n="$(ls "$RUN_DIR"/*.pid 2>/dev/null | wc -l | tr -d ' ')"
  [[ "$n" -gt 0 ]] || die "没有任何屏幕启动成功，检查配置和 xrandr"
  log "已启动 $n 个屏幕实例"

  watch_repo
}

case "${1:-}" in
  --doctor) do_doctor ;;
  --check)  do_check ;;
  --reload) do_reload ;;
  --stop)
    mkdir -p "$RUN_DIR"
    log "已停止 $(stop_all) 个屏幕实例"
    ;;
  --supervise)
    mkdir -p "$WALL_HOME"
    if [[ -f "$WALL_HOME/wall.log" ]] && [[ "$(wc -c <"$WALL_HOME/wall.log" 2>/dev/null || echo 0)" -gt 5242880 ]]; then
      mv -f "$WALL_HOME/wall.log" "$WALL_HOME/wall.log.1"
    fi
    exec >>"$WALL_HOME/wall.log" 2>&1
    # 连崩时退避，避免"配置一直缺失"这类永久故障每 10 秒刷一遍日志、
    # 每次又白跑一遍 git 重试。跑够 30 秒就算正常退出，退避归零。
    quick=0
    while :; do
      started=$SECONDS
      "$0"
      if [[ $((SECONDS - started)) -lt 30 ]]; then
        quick=$((quick + 1))
      else
        quick=0
      fi
      if [[ $quick -ge 6 ]]; then
        delay=60
      elif [[ $quick -ge 3 ]]; then
        delay=30
      else
        delay=10
      fi
      log "编排退出（连续快速退出 ${quick} 次），${delay}s 后重启"
      sleep "$delay"
    done
    ;;
  "")
    main
    ;;
  *)
    printf '用法: %s [--doctor|--check|--reload|--stop|--supervise]\n' "$0"
    exit 2
    ;;
esac
