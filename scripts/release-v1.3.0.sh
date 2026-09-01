#!/usr/bin/env bash
# =============================================================================
#  🚀 AI 编程助手 v1.3.0 一键出包脚本（复制即跑 · 含安全护栏 · dry-run 模式）
# =============================================================================
#  用法（3 选 1）：
#    1. 直接跑（交互填 token）：  bash scripts/release-v1.3.0.sh
#    2. 环境变量塞 token：         GITHUB_TOKEN="ghp_xxx" bash scripts/release-v1.3.0.sh
#    3. DRY-RUN 只看步骤不操作：   bash scripts/release-v1.3.0.sh --dry-run
#
#  输出：
#    ✅ git push + tag v1.3.0 触发 GitHub Actions 构建
#    ⏳ 自动轮询 Release 出包状态（每 60s 查一次，最多等 40 分钟）
#    📦 APK 挂好后自动打印 4 条 gh-proxy 加速链接
#    🔗 如果本机有 curl 自动上传 litterbox 拿 72h 国内直链（没有就只给上传命令）
# =============================================================================
set -euo pipefail

# --------------------------- 0. 参数 & 安全护栏 -------------------------------
DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
  echo -e "\033[1;33m[DRY-RUN] 只打印步骤，不执行任何写操作（commit/push/tag）\033[0m"
fi

# 仓库常量（99% 场景不用改，要改直接改下面 3 个变量）
GITHUB_USER="Kdkdmwnwdkd"
GITHUB_REPO="ai-coder"
VERSION_TAG="v1.3.0"
VERSION_CODE=12
VERSION_NAME="2.2.0-M11-TRAE-UI"
BUILD_FILE="app/build.gradle.kts"
COMMIT_MSG="v1.3.0 TRAE-minimal-UI + status-bar + timeout + mutex-anti-OOM"

# Token 读取优先级：环境变量 > 用户输入（DRY-RUN 可以跳过）
if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  if $DRY_RUN; then
    echo "[DRY-RUN] 跳过 GITHUB_TOKEN 检查"
    GITHUB_TOKEN="DRY-RUN-TOKEN-PLACEHOLDER"
  else
    read -r -p "👉 请输入 GitHub PAT（repo + workflow 权限）：" GITHUB_TOKEN
    if [[ -z "$GITHUB_TOKEN" ]]; then
      echo -e "\033[1;31m❌ 必须提供 GITHUB_TOKEN 才能 push 触发 CI，退出。\033[0m" >&2
      exit 1
    fi
  fi
fi

# --------------------------- 1. 环境检查（不通过直接退，避免半路挂）-----------
echo -e "\033[1;36m=== 🛠️  阶段 1/6：环境检查 ===\033[0m"
require_cmd() {
  if ! command -v "$1" &>/dev/null; then
    echo -e "\033[1;31m❌ 缺少命令：$1，请先安装再运行脚本\033[0m" >&2
    exit 1
  fi
}
require_cmd git
require_cmd curl

# 确认仓库根目录正确
if [[ ! -d ".git" ]] || [[ ! -f "$BUILD_FILE" ]]; then
  echo -e "\033[1;31m❌ 请在仓库根目录（含 .git 和 $BUILD_FILE）执行本脚本，当前目录：$(pwd)\033[0m" >&2
  exit 1
fi
echo "✅ 仓库目录正确：$(pwd)"

# Java 版本检查（Android Gradle 8.x 要 JDK 17）
JAVA_VER=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"' || echo "0")
if [[ "$JAVA_VER" -lt 17 ]] && ! $DRY_RUN; then
  echo -e "\033[1;33m⚠️  当前 JDK 版本=$JAVA_VER，推荐 JDK 17+（AGP 8.x 要求），你可以继续但 build.gradle 版本号 bump 可能跳过\033[0m"
fi
echo "✅ 基础环境 OK（git/curl/JDK$JAVA_VER）"

# --------------------------- 2. 版本号 Bump（build.gradle.kts）---------------
echo -e "\n\033[1;36m=== 🔢  阶段 2/6：版本号 bump（code=$VERSION_CODE, name=$VERSION_NAME）===\033[0m"
bump_version() {
  # 非空校验，避免空值导致替换成 versionCode=
  [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" ]] && { echo "❌ versionCode/Name 空值，拒绝替换" >&2; exit 1; }
  sed -i \
    -e "s/versionCode = [0-9]*/versionCode = $VERSION_CODE/" \
    -e "s/versionName = \".*\"/versionName = \"$VERSION_NAME\"/" \
    "$BUILD_FILE"
  # 校验是否替换成功（sed -i 静默失败会翻车）
  grep -q "versionCode = $VERSION_CODE" "$BUILD_FILE" && grep -q "versionName = \"$VERSION_NAME\"" "$BUILD_FILE"
}
if $DRY_RUN; then
  echo "[DRY-RUN] 将执行 sed 替换 $BUILD_FILE 中的 versionCode/versionName"
else
  bump_version && echo "✅ $BUILD_FILE 版本号替换成功（code=$VERSION_CODE, name=$VERSION_NAME）"
fi

# --------------------------- 3. Git Commit + Tag（失败自动回滚）-------------
echo -e "\n\033[1;36m=== 📝  阶段 3/6：Git Commit + 打 Tag $VERSION_TAG（失败自动回滚）===\033[0m"
if $DRY_RUN; then
  echo "[DRY-RUN] 将执行：git add -A && git commit -m \"$COMMIT_MSG\" && git tag $VERSION_TAG"
else
  git add -A
  # commit message 用纯 ASCII + 简短语义，避免 zsh/中文特殊字符解析错
  if git commit -m "$COMMIT_MSG" --allow-empty; then
    echo "✅ git commit 成功"
  else
    echo -e "\033[1;33m⚠️  git commit 无改动（可能上一版已提交），继续尝试打 Tag\033[0m"
  fi
  # 打 Tag 前先删旧 tag（避免 tag already exists 冲突）
  if git rev-parse "$VERSION_TAG" &>/dev/null; then
    echo "⚠️  检测到本地已存在 $VERSION_TAG，自动删除旧 tag 后重建"
    git tag -d "$VERSION_TAG"
  fi
  git tag "$VERSION_TAG" && echo "✅ git tag $VERSION_TAG 创建成功"
fi

# 安全清理函数：push 失败时自动删本地 tag + 回滚最近一次 commit
rollback_on_fail() {
  echo -e "\n\033[1;31m❌ Push 失败，触发安全回滚：删本地 tag + 回滚 commit\033[0m"
  git tag -d "$VERSION_TAG" 2>/dev/null || true
  # 只回滚我们刚才那一条 commit（判断 commit message 匹配才回滚，避免误回滚用户之前的 commit）
  LAST_MSG=$(git log -1 --pretty=%B)
  if [[ "$LAST_MSG" == "$COMMIT_MSG" ]]; then
    echo "🔙 回滚 commit：git reset --mixed HEAD~1（保留你工作区改动，不丢代码）"
    git reset --mixed HEAD~1
  else
    echo "ℹ️  最近一条 commit 不是本脚本创建的，跳过 commit 回滚（只删 tag）"
  fi
  exit 1
}
trap rollback_on_fail ERR INT

# --------------------------- 4. Git Push（main + tag，触发 CI）--------------
echo -e "\n\033[1;36m=== 🚀  阶段 4/6：Push 代码 + Tag 触发 GitHub Actions CI 构建 ===\033[0m"
GIT_REMOTE_URL="https://$GITHUB_USER:$GITHUB_TOKEN@github.com/$GITHUB_USER/$GITHUB_REPO.git"
if $DRY_RUN; then
  echo "[DRY-RUN] 将执行：git push origin main && git push origin $VERSION_TAG（走 https + token 认证，不走 ssh）"
else
  echo "🌐 Push 到远程仓库（https 方式，token 安全认证不输出到 log）"
  git push "$GIT_REMOTE_URL" main
  git push "$GIT_REMOTE_URL" "$VERSION_TAG"
  trap - ERR INT # push 成功了就取消回滚 trap
  echo "✅ Push 成功！GitHub Actions 已触发，接下来自动轮询 Release 出包状态"
fi

# --------------------------- 5. 轮询 Release 出包（最多 40 分钟）------------
echo -e "\n\033[1;36m=== ⏳  阶段 5/6：轮询 Release Assets（每 60s 查一次，最多 240 次 = 40 分钟）===\033[0m"
RELEASE_API="https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/tags/$VERSION_TAG"
APK_ASSET_NAME_PATTERN="arm64-v8a.apk"
MAX_WAIT=240
WAITED=0
APK_DOWNLOAD_URL=""
if $DRY_RUN; then
  echo "[DRY-RUN] 将循环调用 GitHub API 查 Release 页，等 APK asset 挂好后打印加速链接"
else
  while [[ $WAITED -lt $MAX_WAIT ]]; do
    # GitHub Token 认证调 API，避免未认证 60 次/小时限流
    RESP=$(curl -sSL -u "$GITHUB_USER:$GITHUB_TOKEN" "$RELEASE_API" || echo "{}")
    APK_URL=$(echo "$RESP" | grep -oE '"browser_download_url": *"[^"]*'"$APK_ASSET_NAME_PATTERN"'"' | head -1 | cut -d'"' -f4 || true)
    if [[ -n "$APK_URL" ]]; then
      APK_DOWNLOAD_URL="$APK_URL"
      echo -e "\033[1;32m✅ APK Assets 挂好了！耗时 $((WAITED * 60 / 60)) 分钟\033[0m"
      break
    fi
    STATE=$(echo "$RESP" | grep -oE '"message": *"[^"]*"' | head -1 | cut -d'"' -f4 || echo "Release 未出现在 API（CI 还在构建）")
    echo -e "⏳ 已等 $((WAITED * 60 / 60)) 分 / 40 分，当前状态：$STATE"
    WAITED=$((WAITED + 1))
    sleep 60
  done
  if [[ -z "$APK_DOWNLOAD_URL" ]]; then
    echo -e "\033[1;31m❌ 超过 40 分钟 APK 还没挂好，请手动打开 GitHub Actions 查构建日志：https://github.com/$GITHUB_USER/$GITHUB_REPO/actions\033[0m" >&2
    exit 1
  fi
fi

# --------------------------- 6. 输出 4 条加速链接 + litterbox 直链 --------
echo -e "\n\033[1;36m=== 📦  阶段 6/6：生成下载链接（4 条 gh-proxy + 国内 72h 直链）===\033[0m"
if [[ -z "$APK_DOWNLOAD_URL" ]] && ! $DRY_RUN; then
  echo "❌ 没有 APK URL，跳过生成链接" >&2
  exit 1
fi
# DRY-RUN 模式用占位符代替真实 URL 演示格式
$DRY_RUN && APK_DOWNLOAD_URL="https://github.com/$GITHUB_USER/$GITHUB_REPO/releases/download/$VERSION_TAG/AI.-release-XXXXXXXX-arm64-v8a.apk"

echo -e "\n\033[1;32m🎉 v1.3.0 APK 下载链接（按加速成功率排序）：\033[0m"
echo "1️⃣  gh-proxy 主站：   https://ghproxy.com/$APK_DOWNLOAD_URL"
echo "2️⃣  gh-proxy 镜像1：  https://mirror.ghproxy.com/$APK_DOWNLOAD_URL"
echo "3️⃣  gh.llkk.cc：      https://gh.llkk.cc/$APK_DOWNLOAD_URL"
echo "4️⃣  99988866 CDN：    https://gh.api.99988866.xyz/$APK_DOWNLOAD_URL"

if $DRY_RUN; then
  echo -e "\n\033[1;33m[DRY-RUN] 真实出包后，运行以下命令拿 litterbox 72h 国内直链：\033[0m"
  echo "    APK_LOCAL_PATH=\"/tmp/ai-coder-$VERSION_TAG.apk\" && \\"
  echo "    curl -sSL -o \"\$APK_LOCAL_PATH\" \"https://ghproxy.com/$APK_DOWNLOAD_URL\" && \\"
  echo "    curl -sSL -F \"time=72h\" -F \"reqtype=fileupload\" -F \"fileToUpload=@\$APK_LOCAL_PATH\" https://litterbox.catbox.moe/resources/internals/api.php"
else
  # 尝试自动下 APK 传 litterbox，拿国内直链（curl 失败就只给命令，不阻塞）
  echo -e "\n\033[1;34m🔗 尝试自动生成 litterbox 72h 国内直链（不需要请 Ctrl+C 跳过）...\033[0m"
  TMP_APK="/tmp/ai-coder-$VERSION_TAG-$$.apk"
  LITTER_URL=""
  if curl -sSL --max-time 600 -o "$TMP_APK" "https://ghproxy.com/$APK_DOWNLOAD_URL" && [[ -s "$TMP_APK" ]]; then
    echo "✅ APK 本地下载完成（$(du -h "$TMP_APK" | cut -f1)），上传 litterbox..."
    LITTER_URL=$(curl -sSL --max-time 120 -F "time=72h" -F "reqtype=fileupload" -F "fileToUpload=@$TMP_APK" https://litterbox.catbox.moe/resources/internals/api.php || true)
    rm -f "$TMP_APK"
  fi
  if [[ -n "$LITTER_URL" && "$LITTER_URL" == https* ]]; then
    echo -e "\n\033[1;32m🚀 国内 72h 直链（魅族 20 直接点开就能下，不用翻墙）：\033[0m"
    echo "5️⃣  litterbox 直链：    $LITTER_URL"
  else
    echo -e "\n\033[1;33mℹ️  自动上传 litterbox 失败（网络原因），请手动执行以下命令拿国内直链：\033[0m"
    echo "    APK_LOCAL_PATH=\"/tmp/ai-coder-$VERSION_TAG.apk\" && \\"
    echo "    curl -sSL -o \"\$APK_LOCAL_PATH\" \"https://ghproxy.com/$APK_DOWNLOAD_URL\" && \\"
    echo "    curl -sSL -F \"time=72h\" -F \"reqtype=fileupload\" -F \"fileToUpload=@\$APK_LOCAL_PATH\" https://litterbox.catbox.moe/resources/internals/api.php"
  fi
fi

echo -e "\n\033[1;32m=====================================================================\033[0m"
echo -e "\033[1;32m 🎉 v1.3.0 出包流程全跑完，用户验证清单看项目报告第 7 节（4 张截图）\033[0m"
echo -e "\033[1;32m=====================================================================\033[0m"
