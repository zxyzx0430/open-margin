#!/usr/bin/env bash
# 清衡语音记账：准备离线 ASR 资产（流式 CTC 模型 + sherpa-onnx 原生库）。
#
# 产物（都不进 git，见 .gitignore）：
#   android/app/src/main/assets/asr/zipformer-medium-ctc/{ctc-chunk-16-left-64.int8.onnx,tokens.txt}
#   android/app/libs/sherpa-onnx-static-link-onnxruntime-1.13.5.aar
#
# 设计要点：
#   * 幂等：文件已存在且 sha256/字节数一致就跳过，重复执行只做校验。
#   * 校验：下载完成后按 sha256 + 字节数双重校验，失败重试；校验不过直接报错退出。
#   * 可重试：curl --retry，先写 .part 再原子改名，中断不会留下半个文件。
#   * 缓存：压缩包放在 ~/.cache/open-margin-asr（可用 OPEN_MARGIN_ASR_CACHE 覆盖），
#     CI 用 actions/cache 缓存同一目录，避免每次都拉 71MB。
#
# 用法：
#   bash android/app/download-asr-model.sh            # 准备/校验
#   bash android/app/download-asr-model.sh --force    # 强制重新下载
#   bash android/app/download-asr-model.sh --check     # 只校验，不下载（CI 校验用）
set -euo pipefail

MODEL_ID="zipformer-medium-ctc"
MODEL_REPO="https://huggingface.co/pkufool/zipformer-medium-streaming/resolve/main"

# 只打包一个变体：chunk-16（首字延迟最低、数字最准）。
# VPS 单核实测（100ms 一批流式喂入、单线程）：首个 Partial 落在 1.2s 音频处，
# chunk-32/chunk-64 都要到 1.5s；实时节奏下算力 chunk-16 RTF 0.36~0.39，
# chunk-64 只要 0.15~0.26，但 chunk-64 会吞字（“三十块钱”→“三十”、
# “怎么”→“琢么”）。记账短句里 0.3s 的延迟换准确率是划算的。
# 三个变体体积几乎一样（71 MiB），换档只需改这里的 MODEL_NAME 与哈希。
# 详见 docs/VOICE_ASR_ENGINE.md。
MODEL_NAME="ctc-chunk-16-left-64.int8.onnx"
MODEL_URL="${MODEL_REPO}/${MODEL_NAME}"
MODEL_BYTES=74410051
MODEL_SHA256="7cb84949b8d4f40d72df137e2e3529a1aadfc897e5257460f720bb73c99f10f1"

TOKENS_NAME="tokens.txt"
TOKENS_URL="${MODEL_REPO}/data/${TOKENS_NAME}"
TOKENS_BYTES=86313
TOKENS_SHA256="e2f16836a4c712a9a01a1656f4610f66093fe283159883ee66b73ebb6e80364b"

# 注意：这里刻意用「静态链接 onnxruntime」的 AAR，而不是 JitPack 上的
# com.github.k2-fsa:sherpa-onnx。普通 AAR 自带 libonnxruntime.so (ORT 1.27.1)，
# 会和仓库里 paddle-ocr 的 libonnxruntime.so (ORT 1.21.1) 撞名；两者的 JNI 都
# 硬编码了带版本号的符号（OrtGetApiBase@VERS_1.2x.x），pickFirst 只能让其中
# 一个引擎加载失败。静态链接版把 ORT 编进 libsherpa-onnx-jni.so，arm64-v8a
# 目录下没有 libonnxruntime.so，两套 native 可以共存。
AAR_NAME="sherpa-onnx-static-link-onnxruntime-1.13.5.aar"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/${AAR_NAME}"
AAR_BYTES=37749854
AAR_SHA256="508b79be1aeef3cbb92b8d4325b9b1dad0fa9a4eb1991de0d3d1826b8a09c358"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ANDROID="$(cd "${SCRIPT_DIR}/.." && pwd)"
ASSET_DIR="${SCRIPT_DIR}/src/main/assets/asr/${MODEL_ID}"
LIB_DIR="${SCRIPT_DIR}/libs"
CACHE_DIR="${OPEN_MARGIN_ASR_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/open-margin-asr}"

FORCE=0
CHECK_ONLY=0
for arg in "$@"; do
  case "${arg}" in
    --force) FORCE=1 ;;
    --check) CHECK_ONLY=1 ;;
    -h|--help) sed -n '1,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: ${arg}" >&2; exit 2 ;;
  esac
done

log() { printf '[asr] %s\n' "$*"; }
die() { printf '[asr] 错误: %s\n' "$*" >&2; exit 1; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

file_bytes() { wc -c < "$1" | tr -d ' '; }

# 0 = 文件正确；1 = 缺失或不匹配
verify_file() {
  local path="$1" want_sha="$2" want_bytes="$3"
  [ -f "${path}" ] || return 1
  [ "$(file_bytes "${path}")" = "${want_bytes}" ] || return 1
  [ "$(sha256_of "${path}")" = "${want_sha}" ] || return 1
  return 0
}

download() {
  local url="$1" dest="$2" want_sha="$3" want_bytes="$4"
  local part="${dest}.part"
  mkdir -p "$(dirname "${dest}")"
  rm -f "${part}"
  log "下载 $(basename "${dest}") ($((want_bytes / 1024 / 1024)) MiB)…"
  if ! curl -fL --http1.1 --retry 5 --retry-delay 3 --retry-all-errors \
      --connect-timeout 20 --max-time 1800 -o "${part}" "${url}"; then
    rm -f "${part}"
    die "下载失败: ${url}"
  fi
  if ! verify_file "${part}" "${want_sha}" "${want_bytes}"; then
    local got_sha got_bytes
    got_sha="$(sha256_of "${part}")"
    got_bytes="$(file_bytes "${part}")"
    rm -f "${part}"
    die "校验失败 $(basename "${dest}")：期望 ${want_bytes}B/${want_sha}，实际 ${got_bytes}B/${got_sha}"
  fi
  mv -f "${part}" "${dest}"
}

ensure_archive() {
  local path="$1" url="$2" sha="$3" bytes="$4"
  if [ "${FORCE}" -eq 0 ] && verify_file "${path}" "${sha}" "${bytes}"; then
    log "复用缓存 $(basename "${path}")"
    return 0
  fi
  [ "${CHECK_ONLY}" -eq 1 ] && die "缓存缺失或损坏: ${path}（--check 模式不下载）"
  download "${url}" "${path}" "${sha}" "${bytes}"
}

# 产物目录里已有且正确 → 跳过；否则从缓存/网络准备，复制后重新校验。
ensure_asset() {
  local name="$1" url="$2" sha="$3" bytes="$4" dest_dir="$5"
  local dest="${dest_dir}/${name}"
  local cached="${CACHE_DIR}/${name}"

  if [ "${FORCE}" -eq 0 ] && verify_file "${dest}" "${sha}" "${bytes}"; then
    log "已就绪 ${dest#"${REPO_ANDROID}/"}"
    return 0
  fi
  [ "${CHECK_ONLY}" -eq 1 ] && die "缺失或损坏: ${dest}"
  mkdir -p "${dest_dir}"
  ensure_archive "${cached}" "${url}" "${sha}" "${bytes}"
  cp -f "${cached}" "${dest}.part"
  mv -f "${dest}.part" "${dest}"
  verify_file "${dest}" "${sha}" "${bytes}" || die "复制后校验失败: ${dest}"
  log "安装 ${dest#"${REPO_ANDROID}/"}"
}

mkdir -p "${CACHE_DIR}"
ensure_asset "${MODEL_NAME}" "${MODEL_URL}" "${MODEL_SHA256}" "${MODEL_BYTES}" "${ASSET_DIR}"
ensure_asset "${TOKENS_NAME}" "${TOKENS_URL}" "${TOKENS_SHA256}" "${TOKENS_BYTES}" "${ASSET_DIR}"

mkdir -p "${LIB_DIR}"
AAR_DEST="${LIB_DIR}/${AAR_NAME}"
if [ "${FORCE}" -eq 0 ] && verify_file "${AAR_DEST}" "${AAR_SHA256}" "${AAR_BYTES}"; then
  log "已就绪 ${AAR_DEST#"${REPO_ANDROID}/"}"
else
  [ "${CHECK_ONLY}" -eq 1 ] && die "缺失或损坏: ${AAR_DEST}"
  ensure_archive "${CACHE_DIR}/${AAR_NAME}" "${AAR_URL}" "${AAR_SHA256}" "${AAR_BYTES}"
  cp -f "${CACHE_DIR}/${AAR_NAME}" "${AAR_DEST}.part"
  mv -f "${AAR_DEST}.part" "${AAR_DEST}"
  verify_file "${AAR_DEST}" "${AAR_SHA256}" "${AAR_BYTES}" || die "AAR 校验失败: ${AAR_DEST}"
  log "安装 ${AAR_DEST#"${REPO_ANDROID}/"}"
fi

TOTAL=$((MODEL_BYTES + TOKENS_BYTES))
log "完成：模型 ${TOTAL} 字节（$((TOTAL / 1024 / 1024)) MiB）+ AAR ${AAR_BYTES} 字节"
log "模型目录: ${ASSET_DIR#"${REPO_ANDROID}/"}"
log "原生库:   ${AAR_DEST#"${REPO_ANDROID}/"}"
