#!/usr/bin/env bash
# =============================================================================
#  📋 一键打印某版本的 5 条国内下载直链（只读，不出包、不重跑 workflow）
# =============================================================================
#  用法： bash scripts/show-links.sh [tag]   # tag 默认 v1.3.0
#
#  输出：
#    4 条 gh-proxy 加速链（基于 GitHub Release asset 拼前缀）
#    1 条 tmpfiles.org / litterbox 国内直链（从 mirror-url.txt asset 读）
#
#  下次新会话开局只要说"跑一下 scripts/show-links.sh"就完事，1 次工具调用，
#  不重新编译、不重新上传、不浪费 token。
# =============================================================================
set -euo pipefail

TAG="${1:-v1.3.0}"
REPO="${GITHUB_REPO:-Kdkdmwnwdkd/ai-coder}"
TOKEN="${GITHUB_TOKEN:-}"

AUTH=()
[ -n "$TOKEN" ] && AUTH=(-H "Authorization: Bearer $TOKEN")

API="https://api.github.com/repos/${REPO}/releases/tags/${TAG}"

# ---------- 1. 拉这个 tag 的所有 asset 名 ----------
JSON="$(curl -sS "${AUTH[@]}" "$API")"

# 找第一个 .apk asset（v1.3.0 是 AI.-release-*.apk）
APK_URL="$(printf '%s' "$JSON" \
  | grep -oE '"browser_download_url":\s*"https://[^"]+\.apk"' \
  | head -1 \
  | sed -E 's/.*"([^"]+)"/\1/')"

if [ -z "$APK_URL" ]; then
  echo "❌ Release $TAG 上没找到 .apk asset，先确认 Release 已发布" >&2
  exit 1
fi

# ---------- 2. 找 mirror-url.txt asset（tmpfiles/litterbox 直链） ----------
MIRROR_URL="$(printf '%s' "$JSON" \
  | grep -oE '"browser_download_url":\s*"https://[^"]*mirror-url\.txt"' \
  | head -1 \
  | sed -E 's/.*"([^"]+)"/\1/')"

MIRROR_LINK=""
if [ -n "$MIRROR_URL" ]; then
  MIRROR_LINK="$(curl -sSL "$MIRROR_URL" 2>/dev/null | head -1 || true)"
fi

# ---------- 3. 打印 5 条链 ----------
echo ""
echo "🎉 ${REPO} ${TAG} APK 下载链接"
echo "──────────────────────────────────────────────────────────────"
echo ""
echo "📦 APK 原始 GitHub Release URL："
echo "   $APK_URL"
echo ""
echo "🇨🇳 4 条 gh-proxy 国内加速链（任选其一）："
echo ""
echo "   1️⃣  https://ghproxy.com/$APK_URL"
echo "   2️⃣  https://mirror.ghproxy.com/$APK_URL"
echo "   3️⃣  https://gh.llkk.cc/$APK_URL"
echo "   4️⃣  https://gh.api.99988866.xyz/$APK_URL"
echo ""

if [ -n "$MIRROR_LINK" ]; then
  echo "🚀 第 5 条 国内直链（GitHub runner 上传，tmpfiles.org 30天 / litterbox 72h）："
  echo ""
  echo "   5️⃣  $MIRROR_LINK"
else
  echo "⚠️  第 5 条直链：mirror-url.txt 未挂回 Release"
  echo "   重跑触发：gh workflow run build.yml --repo $REPO --ref main"
fi
echo ""
echo "──────────────────────────────────────────────────────────────"
