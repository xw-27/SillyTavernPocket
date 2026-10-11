#!/usr/bin/env bash
# 生成打过补丁的内核副本,供 Gradle 打进 APK。
#
#   kernel/                 上游 SillyTavern(submodule,固定 tag,不直接修改)
#   patches/kernel/         我们的补丁,按 series 顺序应用
#   <输出目录>/              补丁后的源码 + 生产依赖(node_modules)
#
# 产物(写入 <assets目录>):
#   kernel.zip      内核全部文件(单个文件,安装时顺序解压,比逐个 asset 快得多)
#   kernel.version  版本标识(tag 或 commit),用于判断是否需要重新安装
#   kernel.size     解压后的总字节数(进度条用)
#
# 用法: scripts/prepare-kernel.sh <assets目录>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="${1:?用法: $0 <assets目录>}"
OUT="$ROOT/build/kernel-patched"
KERNEL="$ROOT/kernel"
PATCH_DIR="$ROOT/patches/kernel"

[ -e "$KERNEL/.git" ] || { echo "kernel/ 不是 submodule,请先 git submodule update --init" >&2; exit 1; }
command -v npm >/dev/null || { echo "需要 npm" >&2; exit 1; }

REV="$(git -C "$KERNEL" rev-parse HEAD)"
TAG="$(git -C "$KERNEL" describe --tags --exact-match 2>/dev/null || echo "$REV")"
echo "内核: $TAG ($REV)"

# 1. 导出上游干净树(git archive 只含已提交内容,不含工作区改动)
rm -rf "$OUT" && mkdir -p "$OUT"
git -C "$KERNEL" archive "$REV" | tar -x -C "$OUT"

# 2. 按 series 顺序应用补丁(在输出目录内执行)
if [ -f "$PATCH_DIR/series" ]; then
  while IFS= read -r patch || [ -n "$patch" ]; do
    case "$patch" in ''|\#*) continue ;; esac
    echo "应用补丁: $patch"
    (cd "$OUT" && git apply -p1 "$PATCH_DIR/$patch")
  done < "$PATCH_DIR/series"
fi

# 3. 安装生产依赖(忽略安装脚本;ST 的生产依赖是纯 JS)
(cd "$OUT" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund)

# 4. 打包为单个 zip,并写入版本标识
mkdir -p "$ASSETS"
rm -f "$ASSETS/kernel.zip"
python3 - "$OUT" "$ASSETS/kernel.zip" <<'PY'
import os, sys, zipfile
src, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for dirpath, dirnames, filenames in os.walk(src):
        for name in dirnames + filenames:
            full = os.path.join(dirpath, name)
            arc = os.path.relpath(full, src)
            if os.path.isdir(full) and not os.path.islink(full):
                if not os.listdir(full):
                    z.write(full, arc + "/")  # 保留空目录
                continue
            if os.path.isfile(full):
                z.write(full, arc)
# 解压后的总字节数,供安装进度条计算百分比
total = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(src) for f in fs)
with open(os.path.join(os.path.dirname(dest), "kernel.size"), "w") as f:
    f.write(str(total))
PY
echo "$TAG" > "$ASSETS/kernel.version"
echo "内核已打包: $ASSETS/kernel.zip ($(du -h "$ASSETS/kernel.zip" | cut -f1))"
