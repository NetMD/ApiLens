#!/usr/bin/env bash
# ApiLens 지원 라인 가드 — 카탈로그의 Spring Boot 판이 OSS 지원 기간 안인지 본다.
#
# [Phase R30] R30/AC-R30-29 — 막지 않는 1차 방어다: 종료 코드는 늘 0 이고, CI 주석은 늘 정확히 1 줄이다.
#   - 지원 기간 안      → ::notice  title=support-line:: … within OSS support until {날짜}
#   - 지원 기간 밖      → ::warning title=support-line:: … OSS support ended {날짜}
#   - 표에 없는 계열     → ::warning title=support-line:: … unknown support line
#   - 카탈로그를 못 읽음 → ::warning title=support-line:: … version unknown
#
# 사용: scripts/check-support-line.sh [오늘 날짜 YYYY-MM-DD] [카탈로그 경로]
#   날짜를 안 주면 시스템 날짜(UTC). 날짜를 인자로 받는 것은 경계값 시험이 시계를 바꾸지 않고 돌게 하려는 것이다.
#
# ★지원 기간 표의 정본 자리는 이 파일 아래 case 문 하나뿐이다. 손으로 넣은 값이라 낡는다 — 다음 판올림 라운드가 갱신한다.
#   공식 확인: https://api.spring.io/projects/spring-boot/generations (ossSupportEndDate) · 2026-10-28 21:24 KST 확인.
#   아직 GA 가 아닌 계열(4.2.x 등)은 넣지 않는다 — 넣으면 「표에 없는 계열」 경고가 늦게 나온다.
set -u

TODAY="${1:-$(date -u +%F)}"
CATALOG="${2:-gradle/libs.versions.toml}"

if ! [[ "$TODAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "::warning title=support-line::date argument is not YYYY-MM-DD — support line not checked"
  exit 0
fi

VERSION=""
if [ -r "$CATALOG" ]; then
  VERSION=$(sed -n 's/^springBoot = "\([^"]*\)".*/\1/p' "$CATALOG" | head -n 1)
fi
if [ -z "$VERSION" ]; then
  echo "::warning title=support-line::spring-boot version unknown — could not read springBoot from the catalog"
  exit 0
fi

LINE=$(echo "$VERSION" | cut -d. -f1-2)
case "$LINE" in
  3.4) END="2025-12-31" ;;
  3.5) END="2026-06-30" ;;
  4.0) END="2026-12-31" ;;
  4.1) END="2027-07-31" ;;
  *)
    echo "::warning title=support-line::spring-boot ${VERSION} — unknown support line"
    exit 0
    ;;
esac

if [[ "$TODAY" > "$END" ]]; then
  echo "::warning title=support-line::spring-boot ${VERSION} — OSS support ended ${END}"
else
  echo "::notice title=support-line::spring-boot ${VERSION} within OSS support until ${END}"
fi
exit 0
