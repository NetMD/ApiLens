#!/usr/bin/env bash
# Phase: G1 (Release polish 1/2)
# AC: AC-03-1 ~ AC-03-3
# 비협상: Apache 2.0 — CLAUDE.md "프로젝트 정체성" 인용 ("라이선스: Apache 2.0, 무료 오픈소스")
# CLAUDE.md 룰: "프로젝트 정체성 — 라이선스 Apache 2.0" + "코드 컨벤션 — javadoc 영어" 인용
#
# Apache 2.0 라이선스 헤더를 모든 .java 파일에 멱등(idempotent)으로 적용합니다.
# 기존에 헤더가 있는 파일(`Licensed under the Apache License` marker 보유)은 건너뜁니다.
#
# Usage:
#   bash scripts/apply-license-header.sh             # 모든 .java 파일 검사 + 헤더 없는 파일에 prepend
#   bash scripts/apply-license-header.sh --dry-run   # 변경 없이 prepend 대상 파일 enumerate
#   bash scripts/apply-license-header.sh --check     # prepend 대상이 1건 이상이면 exit 1 (CI fail-fast)
#
# 2회 연속 실행 시 두 번째는 0 file modified 보고 — BL-01 idempotent 자기증명.

set -euo pipefail

# 스크립트 위치 기준 프로젝트 root 산출
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# 17 line 표준 Apache 2.0 헤더 (Copyright 2026 ApiLens Contributors)
# 본 헤더는 라이선스 marker 자체를 포함하므로 idempotent 검사에 사용됨.
read -r -d '' LICENSE_HEADER <<'EOF' || true
/*
 * Copyright 2026 ApiLens Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

EOF

# 스캔 대상 디렉터리 (모노레포 4 모듈 + sample-app)
SCAN_DIRS=(
  "$PROJECT_ROOT/apilens-common/src"
  "$PROJECT_ROOT/apilens-agent/src"
  "$PROJECT_ROOT/apilens-server/src"
  "$PROJECT_ROOT/examples/sample-app/src"
)

MARKER='Licensed under the Apache License'

# 모드 파싱
DRY_RUN=0
CHECK_MODE=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --check)   CHECK_MODE=1; DRY_RUN=1 ;;
    -h|--help)
      cat <<USAGE
사용법: bash scripts/apply-license-header.sh [--dry-run|--check]

  (no option)   헤더 없는 .java 파일에 prepend (in-place).
  --dry-run     변경 없이 대상 파일을 표준 출력으로 enumerate.
  --check       대상 파일이 1건 이상이면 exit 1 (CI fail-fast).
USAGE
      exit 0
      ;;
    *)
      echo "ERROR: 알 수 없는 옵션 '$arg' — --help 참조." >&2
      exit 2
      ;;
  esac
done

modified=0
missing=0
checked=0

# 임시 헤더 파일 (concat 효율)
TMP_HEADER="$(mktemp)"
trap 'rm -f "$TMP_HEADER"' EXIT
printf '%s\n' "$LICENSE_HEADER" > "$TMP_HEADER"

for dir in "${SCAN_DIRS[@]}"; do
  [ ! -d "$dir" ] && continue
  # build 디렉터리 제외, .java 파일만
  while IFS= read -r -d '' file; do
    checked=$((checked + 1))
    # 첫 17 라인 안에 marker 가 있으면 skip (idempotent)
    if head -n 17 "$file" | grep -q "$MARKER"; then
      continue
    fi
    missing=$((missing + 1))
    if [ "$DRY_RUN" -eq 1 ]; then
      echo "[would prepend] ${file#$PROJECT_ROOT/}"
    else
      # atomic prepend (mv 는 atomic)
      cat "$TMP_HEADER" "$file" > "${file}.tmp"
      mv "${file}.tmp" "$file"
      echo "[prepended] ${file#$PROJECT_ROOT/}"
      modified=$((modified + 1))
    fi
  done < <(find "$dir" -type f -name '*.java' -not -path '*/build/*' -print0)
done

echo ""
echo "Summary: checked=${checked}, missing=${missing}, modified=${modified}."

if [ "$CHECK_MODE" -eq 1 ] && [ "$missing" -gt 0 ]; then
  echo "ERROR: ${missing} files missing license header. Run 'bash scripts/apply-license-header.sh' to fix." >&2
  exit 1
fi

exit 0
