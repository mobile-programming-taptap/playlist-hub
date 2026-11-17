#!/usr/bin/env bash
# TapTap 서버를 Tailscale Funnel로 공개하거나 비공개 Serve로 되돌린다.
# 사용법: tools/funnel.sh on | off | private | status
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="http://127.0.0.1:${PORT:-8080}"

ts() {
  if command -v tailscale >/dev/null 2>&1; then tailscale "$@"
  elif [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]; then /Applications/Tailscale.app/Contents/MacOS/Tailscale "$@"
  else echo "tailscale CLI를 찾지 못했어요. Tailscale 앱 설정에서 CLI를 설치해 주세요." >&2; exit 1; fi
}

public_url() {
  grep -E '^PUBLIC_BASE_URL=' "$ROOT/backend/.env" 2>/dev/null | tail -n1 | cut -d= -f2- | tr -d '"' | sed 's#/*$##'
}

check_local() {
  if ! curl -fsS --max-time 5 "$TARGET/health" >/dev/null; then
    echo "로컬 서버($TARGET)가 응답하지 않아요. backend/에서 서버를 먼저 실행해 주세요." >&2; exit 1
  fi
}

case "${1:-}" in
  on)
    check_local
    ts serve --https=443 off >/dev/null 2>&1 || true
    ts funnel --bg --https=443 "$TARGET"
    url="$(public_url)"
    echo "Funnel 켜짐: ${url:-https://<기기명>.ts.net} (공개 DNS 반영까지 최대 10분)"
    if [ -n "$url" ] && curl -fsS --max-time 10 "$url/health" >/dev/null 2>&1; then
      echo "공개 주소 /health 응답 확인"
    else
      echo "공개 주소 /health는 아직 응답이 없어요. 몇 분 뒤 Tailscale을 끈 휴대폰에서 확인해 주세요."
    fi
    echo "맥이 잠들지 않게 하려면 다른 터미널에서: caffeinate -dims"
    ;;
  off)
    ts funnel --https=443 off
    echo "Funnel 꺼짐. 공개 주소로는 더 이상 접속할 수 없어요."
    ;;
  private)
    ts funnel --https=443 off >/dev/null 2>&1 || true
    ts serve --bg --https=443 "$TARGET"
    echo "비공개 Serve로 복귀했어요. tailnet 기기에서만 접속할 수 있어요."
    ;;
  status)
    ts funnel status
    ;;
  *)
    echo "사용법: tools/funnel.sh on | off | private | status" >&2; exit 2
    ;;
esac
