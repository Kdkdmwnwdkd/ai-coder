#!/usr/bin/env bash
# ============================================================================
# AI编程助手 —— 一键部署到 GitHub + 自动编译出 APK
#
# 运行前准备（只需做一次）：
#   1) 安装 GitHub CLI (gh)：https://cli.github.com/
#       - macOS:   brew install gh
#       - Ubuntu:  (type -p wget >/dev/null || sudo apt-get update && sudo apt-get install wget -y) && mkdir -p -m 755 /etc/apt/keyrings && wget -nv -O /etc/apt/keyrings/githubcli-archive-keyring.gpg https://cli.github.com/packages/githubcli-archive-keyring.gpg && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null && sudo apt update && sudo apt install gh -y
#       - Windows: winget install GitHub.cli  （或去官网下载安装包）
#   2) 终端执行一次:  gh auth login
#       选 GitHub.com -> HTTPS -> 浏览器/粘贴 Token 都行，走完向导
#
# 然后直接：  bash deploy_github.sh
#
# 脚本会自动: 建仓库 -> 配 4 个签名 Secrets -> 推送代码 -> 等 Actions 编译 -> 给你下载链接
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ---------- 签名信息（我已生成，和 signing/xuedi_coder_release.jks 一一对应）----------
STORE_PASSWORD='XuediCoder@2026Release!'
KEY_ALIAS='aicoder_release'
KEY_PASSWORD='XuediCoder@2026Release!'
KS_B64_FILE="$SCRIPT_DIR/signing/xuedi_coder_release.jks.b64"

# ---------- 依赖检查 ----------
for CMD in gh git; do
  if ! command -v $CMD >/dev/null 2>&1; then
    echo "❌ 缺少命令: $CMD，请先安装。"
    exit 1
  fi
done

if [ ! -f "$KS_B64_FILE" ]; then
  # base64 文件没现成的，就现场从 jks 生成
  if [ ! -f "$SCRIPT_DIR/signing/xuedi_coder_release.jks" ]; then
    echo "❌ 找不到 signing/xuedi_coder_release.jks，请确保项目完整。"
    exit 1
  fi
  base64 -w0 "$SCRIPT_DIR/signing/xuedi_coder_release.jks" > "$KS_B64_FILE"
fi

# ---------- GitHub 登录检查 ----------
if ! gh auth status >/dev/null 2>&1; then
  echo ""
  echo "🔐 还没登录 GitHub。现在帮你打开登录向导："
  gh auth login --hostname github.com --git-protocol https --web
fi

gh auth refresh -h github.com -s repo,workflow,admin:repo_secrets >/dev/null 2>&1 || true

# ---------- Git 用户名邮箱兜底 ----------
GIT_NAME="${GIT_AUTHOR_NAME:-AI Coder Bot}"
GIT_EMAIL="${GIT_AUTHOR_EMAIL:-ai-coder-bot@users.noreply.github.com}"
if ! git config --global user.name >/dev/null 2>&1; then
  git config --global user.name "$GIT_NAME"
fi
if ! git config --global user.email >/dev/null 2>&1; then
  git config --global user.email "$GIT_EMAIL"
fi

# ---------- 仓库名 ----------
DEFAULT_OWNER="$(gh api user -q .login 2>/dev/null || echo "$USER")"
DEFAULT_REPO="ai-coder"
read -r -p "🏷  仓库所有者 (你的 GitHub 用户名，默认: $DEFAULT_OWNER): " OWNER
OWNER="${OWNER:-$DEFAULT_OWNER}"
read -r -p "📦 仓库名称 (默认: $DEFAULT_REPO): " REPO
REPO="${REPO:-$DEFAULT_REPO}"
VISIBILITY="${VISIBILITY:-private}"   # 改成 public 可公开
FULL_NAME="$OWNER/$REPO"
echo "→ 将创建仓库: https://github.com/$FULL_NAME ($VISIBILITY)"

# ---------- 建仓库（已存在就跳过）----------
if gh repo view "$FULL_NAME" >/dev/null 2>&1; then
  echo "✅ 仓库已存在，复用。"
else
  gh repo create "$FULL_NAME" --"$VISIBILITY" --description "AI编程助手：本地运行 GGUF 模型做代码生成" >/dev/null
  echo "✅ 仓库已创建: https://github.com/$FULL_NAME"
fi
REMOTE_URL="https://github.com/$FULL_NAME.git"

# ---------- 初始化 git ----------
if [ ! -d .git ]; then
  git init -b main
else
  # 已存在就保持当前分支
  true
fi
# 确保 origin 指向正确的远程
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REMOTE_URL"
else
  git remote add origin "$REMOTE_URL"
fi

# ---------- Secrets 写入（4个，与 .github/workflows/build.yml 对应）----------
echo "🔒 正在写入仓库 Secrets ..."
gh secret set SIGNING_JKS_BASE64    --repo "$FULL_NAME" --body "$(cat "$KS_B64_FILE")"
gh secret set SIGNING_STORE_PASSWORD --repo "$FULL_NAME" --body "$STORE_PASSWORD"
gh secret set SIGNING_KEY_ALIAS      --repo "$FULL_NAME" --body "$KEY_ALIAS"
gh secret set SIGNING_KEY_PASSWORD   --repo "$FULL_NAME" --body "$KEY_PASSWORD"
echo "✅ 4 个签名 Secrets 写入完成。"

# ---------- commit + push ----------
echo "🚀 提交并推送代码 ..."
git add -A
# 允许重复运行，本次没改动就跳过 commit
if ! git diff --cached --quiet || [ -z "$(git log -1 2>/dev/null)" ]; then
  git commit -m "feat: AI编程助手 V2.0-M1 (聊天/场景/设置/关于 4Tab 极简版)" \
    --allow-empty 2>&1 | tail -n 3
fi
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git push -u origin "$BRANCH" 2>&1 | tail -n 5

# ---------- 等 Actions 开始 ----------
echo ""
echo "⏳ 等待 GitHub Actions 启动 (通常 10-30s)..."
for i in $(seq 1 30); do
  RUN_ID=$(gh run list --repo "$FULL_NAME" --branch "$BRANCH" --workflow "build.yml" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || echo "")
  if [ -n "$RUN_ID" ] && [ "$RUN_ID" != "null" ]; then break; fi
  sleep 5
done
if [ -z "${RUN_ID:-}" ] || [ "$RUN_ID" = "null" ]; then
  echo "⚠ 没等到 run id，请到 Actions 页面手动查看："
  echo "  https://github.com/$FULL_NAME/actions"
  exit 0
fi
echo "✅ Actions 已启动: Run #$RUN_ID"
echo "   https://github.com/$FULL_NAME/actions/runs/$RUN_ID"
echo ""
echo "🧪 正在编译（Debug + Release），预计 6-15 分钟，请耐心等待 ..."

# 实时查看日志
gh run watch "$RUN_ID" --repo "$FULL_NAME" --exit-status || true

# 拿最终状态
STATUS=$(gh run view "$RUN_ID" --repo "$FULL_NAME" --json status --jq .status 2>/dev/null || echo "")
CONCLUSION=$(gh run view "$RUN_ID" --repo "$FULL_NAME" --json conclusion --jq .conclusion 2>/dev/null || echo "")
echo ""
echo "================================================================================"
echo "📋 结果: status=$STATUS  conclusion=$CONCLUSION"
echo "================================================================================"

echo ""
echo "📦 可用产物（点链接下载，或登录后点 Artifacts）："
echo "  Actions 运行页面: https://github.com/$FULL_NAME/actions/runs/$RUN_ID"

if [ "$CONCLUSION" = "success" ]; then
  # 如果成功就列 artifact id
  ARTIFACTS=$(gh api "repos/$FULL_NAME/actions/runs/$RUN_ID/artifacts" --jq '.artifacts[] | "  - \(.name)  →  https://github.com/'"$FULL_NAME"'/suites/\(.workflow_run.id)/artifacts/\(.id)"' 2>/dev/null || true)
  if [ -n "$ARTIFACTS" ]; then
    echo "$ARTIFACTS"
  fi
  echo ""
  echo "✅ 编译成功！回到上面 Actions 页面，拉到页面底部，下载："
  echo "   ① AI编程助手-debug-apk   （任意安装，最快能装；推荐先用这个验证）"
  echo "   ② AI编程助手-release-apk （正式签名，需开启允许未知来源后安装）"
  echo ""
else
  echo ""
  echo "❌ 有任务失败，请把 Actions 页面的失败日志贴给我，我立刻修。"
  echo "   通常第一次失败是 AGP / 依赖下载超时，Re-run 一次一般就好。"
fi

echo ""
echo "指纹信息（以后升级 APK 保持一致即可）："
echo "  SHA256: 8E:18:B6:6E:2E:C1:CC:35:2D:CC:3D:92:E9:3D:3D:80:47:98:70:E7:A8:5E:16:4F:39:77:28:42:D7:49:79:BA"
echo "  Alias : aicoder_release"
