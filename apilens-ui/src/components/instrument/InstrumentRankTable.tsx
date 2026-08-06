// [Phase R19] 3축 순위표 — 세 축 상시 표시 + 각 수치 옆 그 축의 순위 병기 (UX 갈림길 4).
//
// 이 표가 존재하는 이유는 "세 기준이 서로 다른 순위를 준다" 는 사실이다. 그래서 **어긋남 자체가
// 화면에 보여야 한다** — `span #2 / 바이트 #16` 이 한 줄에서 읽혀야 "span 으로는 위인데 용량으로는
// 바닥" 이 한 번에 이해된다.
//
// 비협상 규약:
//   ⛔ 세 축 컬럼을 정렬 축에 따라 숨기지 않는다 (항상 셋 다).
//   ⛔ 순위(#N)는 **서버가 준 전체 집합 기준 값**을 그대로 그린다. 화면에서 순위를 다시 매기지
//      않는다 — 상한에 걸려 잘린 목록 안에서 매기면 "이 목록 안 1위" 가 "전체 1위" 로 둔갑한다.
//   ⛔ 절감 축과 제외 가능성 축을 섞지 않는다 (양방향):
//        · 수치 셀 class 계산에 excludeStatus 가 들어가지 않는다.
//        · 배지 class 계산에 절감·수치 크기가 들어가지 않는다.
//      두 축은 방향이 반대다 — 하나는 "빼면 이만큼 줄어요"(권함), 하나는 "이건 못 빼요"(말림).
//   ⛔ "뺄 수 있는 것만 위로" 같은 정렬을 만들지 않는다 (그 순간 두 축이 하나로 합쳐진다).
//   ⛔ 불가(NOT_EXCLUDABLE) 행은 체크박스를 **렌더하지 않는다** (비활성이 아니라 DOM 자체 부재 —
//      삭제 불가 룰 행에서 버튼을 아예 안 그리는 기존 전례 계승).
//   ⛔ 불확실(UNKNOWN)은 고를 수 있다. "뺄 수 있어요" 로 반올림하지 않는다.
import type { ReactNode } from 'react';
import type { ExcludeReasonCode, ExcludeStatus, InstrumentClassStat } from '../../types/api';
import { SORT_AXIS_LABELS } from '../../lib/instrumentThresholds';
import type { SortAxis } from '../../lib/instrumentThresholds';
import { classPackage, classSimpleName, formatBytes } from '../../lib/format';

interface Props {
  /** 서버가 준 items 그대로 (세 축 상위 N 의 합집합 + 고정 합계 행). */
  items: InstrumentClassStat[];
  /** 목록이 상한에 걸려 잘렸는가 (T-23 · T-60 표시 조건). */
  truncated: boolean;
  sortAxis: SortAxis;
  onSortAxisChange: (axis: SortAxis) => void;
  selected: ReadonlySet<string>;
  onToggleClass: (className: string) => void;
  onSelectPackage: (packageName: string) => void;
  onClearSelection: () => void;
  /** 진행 중이면 선택 조작을 막는다 (C-08 · C-09 · C-23). 정렬 전환은 막지 않는다 (C-07). */
  busy: boolean;
}

/** 사유 코드 → 화면 문구 (문구 단일 거주지 = UX 문구표. 서버는 코드만 보낸다). */
const REASON_TEXT: Readonly<Record<ExcludeReasonCode, string>> = {
  // T-27 — 이름이 하나뿐인 계측(고정 합계 행)의 대안 안내.
  NO_CLASS_NAME:
    '이 항목은 계측 제외 옵션으로 뺄 수 없어요. 저장되는 결과 자료를 끄는 다른 옵션을 보세요',
  // [Phase R20] R20/AC-11-2/AC-12-1 — T-31 불가 사유(ⓐ) + 원격 게이트 대안 안내(ⓑ) 2요소 통합.
  // 사용자 명시 비협상 결정 (Q-U9 — "weaving 제외는 불가, 원격 게이트로는 개별 제외 가능" 취지).
  // 설계 §1-⑧ 확정 문구 바이트 그대로 — dev 임시 창작 금지. ⓐ부 앞 문장 = R19 확정 문구 verbatim 보존.
  // 옵션 문자열(-D 키명·API 경로·FQN) 화면 노출 0 — 문서로 안내 (T-27 NO_CLASS_NAME "…보세요" 전례 동형).
  PROXY_INSTRUMENTED:
    '이 계층은 계측이 걸리는 이름이 화면에 보이는 이름과 달라서, 이 이름으로는 빠지지 않아요. 대신 원격 계측 설정으로는 이 이름 그대로 뺄 수 있어요 — 방법은 agent 옵션 문서를 보세요',
  // T-32 — 불확실 사유.
  UNVERIFIED_PATH: '이 계층이 실제로 빠지는지 확인하지 못했어요. 적용 뒤 직접 확인해 주세요',
};

/**
 * 3분류 배지 규격 (T-28 · T-29 · T-30).
 *
 * 색 + 글자 + 기호 3중 구분 (색만으로 정보 전달 금지 규약 계승).
 * "가능" 을 초록·강조색으로 칠하지 않는다 — 칠하면 "가능 = 권장" 으로 읽혀 절감 축과 섞인다.
 * 가장 조용한 표시(흰 배경·회색 글자)로 두고, **불확실만 배경 틴트**를 줘서 훑을 때 배경으로도 구분되게 한다.
 */
const STATUS_BADGE: Readonly<
  Record<ExcludeStatus, { label: string; mark: string; className: string }>
> = {
  EXCLUDABLE: {
    label: '뺄 수 있어요',
    mark: '✓',
    className: 'border border-stone-200 bg-white text-stone-500',
  },
  NOT_EXCLUDABLE: {
    label: '뺄 수 없어요',
    mark: '✕',
    className:
      'border border-[var(--color-status-error)] bg-white text-[var(--color-status-error)]',
  },
  UNKNOWN: {
    label: '확인 안 됨',
    mark: '?',
    className: 'border border-amber-200 bg-amber-50 text-amber-900',
  },
};

const SORT_AXES: readonly SortAxis[] = ['span', 'payloadCount', 'payloadBytes'];

/** 그 축의 순위 (서버 값). 고정 합계 행은 세 축 모두 null 이다. */
function rankOf(item: InstrumentClassStat, axis: SortAxis): number | null {
  switch (axis) {
    case 'span':
      return item.spanRank;
    case 'payloadCount':
      return item.payloadCountRank;
    case 'payloadBytes':
      return item.payloadBytesRank;
  }
}

/** 고정 합계 행인가 (operation_name 에 `#` 가 없어 클래스로 나눌 수 없는 span 묶음). */
function isFixedTotalRow(item: InstrumentClassStat): boolean {
  return item.className === '';
}

/**
 * 표시 순서.
 *
 * 고정 합계 행은 **정렬 대상이 아니라 맨 위 고정**이다 (정렬을 바꿔도 자리가 안 움직인다 —
 * 움직이면 운영자가 "사라졌다" 고 오해한다). 나머지는 **서버가 준 축별 순위**를 정렬 키로 쓴다.
 * 화면이 수치로 순위를 다시 계산하는 자리는 없다.
 */
function orderedRows(items: InstrumentClassStat[], axis: SortAxis): InstrumentClassStat[] {
  const fixed = items.filter(isFixedTotalRow);
  const classes = items.filter((item) => !isFixedTotalRow(item));
  const sorted = [...classes].sort((a, b) => {
    const ra = rankOf(a, axis);
    const rb = rankOf(b, axis);
    if (ra === null && rb === null) return 0;
    if (ra === null) return 1; // 그 축 순위가 없는 항목은 뒤로 (서버 순서 보존).
    if (rb === null) return -1;
    return ra - rb;
  });
  return [...fixed, ...sorted];
}

export function InstrumentRankTable({
  items,
  truncated,
  sortAxis,
  onSortAxisChange,
  selected,
  onToggleClass,
  onSelectPackage,
  onClearSelection,
  busy,
}: Props): ReactNode {
  const rows = orderedRows(items, sortAxis);

  return (
    <div className="space-y-3">
      {/* 정렬 세그먼트 + 선택 요약 — 라벨은 컬럼 머리와 같은 문자열 (T-17 · T-16) */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="text-xs text-stone-500">정렬 기준</span>
          <div
            role="group"
            aria-label="정렬 기준"
            className="inline-flex rounded-md border border-stone-200 bg-white p-0.5"
          >
            {SORT_AXES.map((axis) => {
              const active = axis === sortAxis;
              return (
                <button
                  key={axis}
                  type="button"
                  aria-pressed={active}
                  onClick={() => onSortAxisChange(axis)}
                  className={
                    active
                      ? 'rounded px-3 py-1.5 text-sm font-medium bg-stone-900 text-white'
                      : 'rounded px-3 py-1.5 text-sm text-stone-500 hover:text-stone-900'
                  }
                >
                  {SORT_AXIS_LABELS[axis]}
                </button>
              );
            })}
          </div>
        </div>
        <div className="flex items-center gap-2">
          {/* [Phase R19] 수치 아래 #숫자 가 무슨 뜻인지 화면에 설명이 없어 물음이 나왔다
              (릴리스 전 사용자 확인). 설명이 화면 읽기용(sr-only)에만 있었다. */}
          <span className="text-xs text-stone-400">
            수치 아래 <span className="font-mono text-stone-500">#숫자</span> 는 그 기준에서 전체 몇 번째인지를 뜻해요
          </span>
          {/* T-61 */}
          <span className="text-xs text-stone-500">{selected.size}개 선택됨</span>
          {/* T-62 · C-09 */}
          <button
            type="button"
            onClick={onClearSelection}
            disabled={selected.size === 0 || busy}
            className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-900 hover:bg-stone-50 disabled:opacity-50"
          >
            선택 해제
          </button>
        </div>
      </div>

      <div className="overflow-x-auto rounded-lg border border-stone-200">
        <table className="w-full min-w-[720px] text-left">
          <thead className="bg-stone-50">
            <tr className="text-xs font-medium text-stone-500">
              <th className="w-10 px-3 py-2">
                <span className="sr-only">선택</span>
              </th>
              <th className="px-4 py-2">대상</th>
              {SORT_AXES.map((axis) => (
                <th
                  key={axis}
                  // 정렬 축을 색만으로 표현하지 않는다 — aria-sort 와 세그먼트 aria-pressed 로 이중 표시.
                  aria-sort={axis === sortAxis ? 'descending' : 'none'}
                  className={
                    axis === sortAxis
                      ? 'bg-stone-100 px-4 py-2 text-right'
                      : 'px-4 py-2 text-right'
                  }
                >
                  {SORT_AXIS_LABELS[axis]}
                </th>
              ))}
              <th className="px-4 py-2">제외 가능성</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((item) => (
              <RankRow
                key={item.className === '' ? '__fixed_total__' : item.className}
                item={item}
                sortAxis={sortAxis}
                checked={selected.has(item.className)}
                onToggleClass={onToggleClass}
                onSelectPackage={onSelectPackage}
                busy={busy}
              />
            ))}
          </tbody>
        </table>
      </div>

      {/* T-23 — 잘렸을 때만. T-60 은 "보이는 목록 안에서만 선택된다" 는 사실을 같은 조건에서 알린다. */}
      {truncated && (
        <div className="space-y-1 text-xs text-stone-500">
          <p>상위 일부만 보여 주고 있어요</p>
          <p>보이는 목록 안에서만 선택돼요.</p>
        </div>
      )}
    </div>
  );
}

interface RowProps {
  item: InstrumentClassStat;
  sortAxis: SortAxis;
  checked: boolean;
  onToggleClass: (className: string) => void;
  onSelectPackage: (packageName: string) => void;
  busy: boolean;
}

function RankRow({
  item,
  sortAxis,
  checked,
  onToggleClass,
  onSelectPackage,
  busy,
}: RowProps): ReactNode {
  const fixed = isFixedTotalRow(item);
  // 불가 행은 체크박스 DOM 자체가 없다 (비활성이 아님). 고정 합계 행도 마찬가지.
  const selectable = !fixed && item.excludeStatus !== 'NOT_EXCLUDABLE';
  const badge = STATUS_BADGE[item.excludeStatus];
  const pkg = fixed ? '' : classPackage(item.className);
  const reason = item.excludeReasonCode === null ? null : REASON_TEXT[item.excludeReasonCode];

  return (
    <tr
      className={
        fixed
          ? 'border-t border-stone-200 border-b-2 bg-stone-50 text-sm text-stone-900'
          : 'border-t border-stone-200 text-sm text-stone-900'
      }
    >
      <td className="px-3 py-3 align-top">
        {selectable && (
          <input
            type="checkbox"
            checked={checked}
            disabled={busy}
            onChange={() => onToggleClass(item.className)}
            aria-label={`${item.className} 선택`}
            className="h-4 w-4 accent-stone-900 focus:ring-2 focus:ring-stone-900 focus:ring-offset-2"
          />
        )}
      </td>

      <td className="px-4 py-3 align-top">
        <div className="flex items-center gap-2">
          <span className="font-mono text-sm text-stone-900">
            {/* T-26 — 이름이 하나뿐인 계측 행 */}
            {fixed ? 'DB 접근 (전체)' : classSimpleName(item.className)}
          </span>
          {item.backgroundWorker && (
            // T-18 · T-19 — 배지 설명은 못 읽어도 손해가 없는 부가 정보라 title 로 둔다.
            <span
              title="요청 흐름 밖에서 스스로 시작하는 작업이에요"
              className="rounded bg-stone-100 px-1.5 py-0.5 text-[10px] font-medium text-stone-500"
            >
              백그라운드
            </span>
          )}
        </div>

        {fixed ? (
          // T-70
          <p className="mt-0.5 text-xs text-stone-500">서비스 전체 합계 · 클래스로 나눌 수 없어요</p>
        ) : (
          <div className="mt-0.5 flex items-center gap-2">
            <span className="font-mono text-xs text-stone-500">{pkg}</span>
            {pkg !== '' && (
              // T-59 · C-23 — 패키지 "선택 상태" 를 따로 두지 않는다. 보이는 목록 안 같은 패키지
              // 클래스 체크박스를 한 번에 켜는 **단축키**일 뿐이라 서버로 가는 값은 언제나 클래스 목록이다.
              <button
                type="button"
                onClick={() => onSelectPackage(pkg)}
                disabled={busy}
                className="rounded px-1.5 py-0.5 text-[10px] text-stone-500 hover:bg-stone-100 hover:text-stone-900 disabled:opacity-50"
              >
                이 패키지 전체
              </button>
            )}
          </div>
        )}

        {/* 사유는 툴팁이 아니라 행 안 상시 노출 (키보드·터치에서도 반드시 읽히게) */}
        {reason !== null && <p className="mt-1 text-xs text-stone-500">{reason}</p>}
      </td>

      <MetricCell
        value={item.spanCount.toLocaleString()}
        rank={item.spanRank}
        axis="span"
        highlighted={sortAxis === 'span'}
      />
      <MetricCell
        value={item.payloadCount.toLocaleString()}
        rank={item.payloadCountRank}
        axis="payloadCount"
        highlighted={sortAxis === 'payloadCount'}
      />
      <MetricCell
        value={formatBytes(item.payloadBytes)}
        rank={item.payloadBytesRank}
        axis="payloadBytes"
        highlighted={sortAxis === 'payloadBytes'}
      />

      <td className="px-4 py-3 align-top">
        <span
          className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium ${badge.className}`}
        >
          <span aria-hidden>{badge.mark}</span>
          {badge.label}
        </span>
      </td>
    </tr>
  );
}

interface MetricCellProps {
  value: string;
  /** 서버가 준 전체 집합 기준 순위. 고정 합계 행은 null 이라 #N 을 붙이지 않는다. */
  rank: number | null;
  axis: SortAxis;
  highlighted: boolean;
}

/**
 * 수치 셀 — 값 + 그 축의 순위(#N).
 *
 * ⚠️ class 계산에 excludeStatus 가 **들어가지 않는다** (절감 축 ⊥ 제외 가능성 축).
 *    정렬 축 강조만 반영한다.
 */
function MetricCell({ value, rank, axis, highlighted }: MetricCellProps): ReactNode {
  return (
    <td className={highlighted ? 'bg-stone-50 px-4 py-3 text-right align-top' : 'px-4 py-3 text-right align-top'}>
      <span className="font-mono text-sm text-stone-900">{value}</span>
      {rank !== null && (
        <span className="mt-0.5 block">
          {/* T-58 — 화면은 기호(#N), 화면 읽기용은 전문 */}
          <span aria-hidden className="font-mono text-[10px] text-stone-400">
            #{rank}
          </span>
          <span className="sr-only">
            {SORT_AXIS_LABELS[axis]} 기준 {rank}위
          </span>
        </span>
      )}
    </td>
  );
}
