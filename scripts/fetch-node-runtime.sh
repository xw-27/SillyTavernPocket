#!/usr/bin/env bash
# 获取 Android arm64 的 Node.js 运行时(来自 Termux 预编译包),输出到指定目录。
#
# 做法:
#   1. 从 Termux 仓库索引解析 nodejs-lts 及其依赖闭包,下载 .deb 并校验 SHA256;
#   2. 解包后,从 node 的 NEEDED 出发,只拷贝真正需要的 .so(按 soname 命名);
#   3. node 改名为 libnode-exec.so(让 AGP 按 jniLibs 正常打包并解压)。
#
# 用法: scripts/fetch-node-runtime.sh <输出目录>
# 输出目录内容:libnode-exec.so 以及其依赖的共享库(不含 Termux 前缀路径依赖)。
set -euo pipefail

DEST="${1:?用法: $0 <输出目录>}"
command -v patchelf >/dev/null || { echo "需要 patchelf(pacman -S patchelf)" >&2; exit 1; }
REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
PKG_ROOT="${PKG_ROOT:-$HOME/.cache/sillytavernpocket/termux}"
TOP_PKG="${TOP_PKG:-nodejs-lts}"
PREFIX_REL="data/data/com.termux/files/usr"

mkdir -p "$PKG_ROOT/debs" "$PKG_ROOT/root"
INDEX="$PKG_ROOT/Packages.gz"
[ -f "$INDEX" ] || curl -fsSL -o "$INDEX" "$REPO/dists/stable/main/binary-aarch64/Packages.gz"

# 解析依赖闭包,输出: 包名 版本 文件名 SHA256
python3 - "$INDEX" "$TOP_PKG" > "$PKG_ROOT/closure.txt" <<'PY'
import gzip, sys
index, top = sys.argv[1], sys.argv[2]
pkgs = {}
for block in gzip.open(index, "rt").read().split("\n\n"):
    d = {}
    for line in block.splitlines():
        if ": " in line and not line.startswith(" "):
            k, v = line.split(": ", 1)
            d.setdefault(k, v)
    if "Package" in d:
        pkgs.setdefault(d["Package"], d)
seen, queue = [], [top]
while queue:
    name = queue.pop(0)
    if name in seen:
        continue
    if name not in pkgs:
        sys.exit(f"索引中找不到依赖: {name}")
    seen.append(name)
    deps = pkgs[name].get("Depends", "")
    for dep in deps.split(","):
        dep = dep.strip().split(" ")[0].split("|")[0].strip()
        if dep:
            queue.append(dep)
for name in seen:
    d = pkgs[name]
    print(name, d["Version"], d["Filename"], d["SHA256"], sep="\t")
PY

# 下载并校验、解包
while IFS=$'\t' read -r name version filename sha; do
  deb="$PKG_ROOT/debs/$(basename "$filename")"
  if [ ! -f "$deb" ] || ! echo "$sha  $deb" | sha256sum -c --status -; then
    curl -fsSL -o "$deb" "$REPO/$filename"
    echo "$sha  $deb" | sha256sum -c -
  fi
  tmp="$(mktemp -d)"
  (cd "$tmp" && ar x "$deb" && tar -xf data.tar.* -C "$PKG_ROOT/root")
  rm -rf "$tmp"
  echo "已解包: $name $version"
done < "$PKG_ROOT/closure.txt"

LIBDIR="$PKG_ROOT/root/$PREFIX_REL/lib"
BIN="$PKG_ROOT/root/$PREFIX_REL/bin/node"
[ -f "$BIN" ] || { echo "解包后找不到 node" >&2; exit 1; }

# 按 NEEDED 闭包收集共享库(用 soname 作为文件名)
rm -rf "$DEST" && mkdir -p "$DEST"
cp -L "$BIN" "$DEST/libnode-exec.so"
python3 - "$BIN" "$LIBDIR" "$DEST" <<'PY'
import os, re, shutil, subprocess, sys
binary, libdir, dest = sys.argv[1:4]
def needed(path):
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True, check=True).stdout
    return re.findall(r"NEEDED.*\[(.+?)\]", out)
# Android 系统库不需要打包
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so"}
queue, done = needed(binary), set()
while queue:
    name = queue.pop()
    if name in done or name in SYSTEM:
        continue
    done.add(name)
    src = os.path.join(libdir, name)
    if not os.path.exists(src):
        sys.exit(f"缺少依赖库: {name}")
    shutil.copyfile(src, os.path.join(dest, name))  # copyfile 跟随符号链接
    queue.extend(needed(src))
print("已收集共享库:", ", ".join(sorted(done)))

# AGP 只会打包名为 lib*.so 的文件。带版本后缀的 soname(如 libssl.so.3)需要改名为
# libssl_3.so,并用 patchelf 同步修改所有 NEEDED 与 SONAME,保证链接器能找到它们。
ren = {}
for n in done:
    m = re.match(r"^(lib.+)\.so\.(\d+)$", n)
    if m:
        ren[n] = f"{m.group(1)}_{m.group(2)}.so"
files = [os.path.join(dest, f) for f in os.listdir(dest)]
for path in files:
    for old, new in ren.items():
        if old in needed(path):
            subprocess.run(["patchelf", "--replace-needed", old, new, path], check=True)
    if os.path.basename(path) in ren:
        subprocess.run(["patchelf", "--set-soname", ren[os.path.basename(path)], path], check=True)
for old, new in ren.items():
    os.rename(os.path.join(dest, old), os.path.join(dest, new))
    print(f"改名: {old} -> {new}")
PY
chmod 755 "$DEST"/*
echo "运行时已输出到: $DEST"
