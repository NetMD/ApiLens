// [R21] 3상태 축 선택기 — 신규 패턴 P-R21-1 (지시 없음 / 줄이기 / 기동값으로 되돌리기).
//
// R21/AC-02-1 (Plan verbatim): "boolean 3축 각각 3상태(지시 없음 / 줄이기 / 기동값으로 되돌리기)".
// R21/AC-02-2 — 축을 살리는 방향의 짧은 동사 라벨(금지 목록 UX §9.3-1)은 화면 어디에도 없다
// (entryRoot 축은 방향 반전이라 그 라벨이 거짓말이 된다 — 3상태 이름은 T-01 셋뿐).
//
// 시맨틱 (설계 §2.3 확정 — UX §4.3 위임 회수): 3택1 배타 선택이므로 **라디오 시맨틱** —
// 숨긴 native <input type="radio">(sr-only peer) + 보이는 라벨을 segmented 로 스타일링.
// 배타성·화살표 이동이 브라우저 기본 (aria-pressed 는 배타성이 전달되지 않는다 — JS roving
// tabindex 재발명 금지, 설계 §11-5). 포커스 링은 라벨에 peer-focus-visible 로 표출
// (SH-15 — outline-none 단독 금지). 축 라벨 연결 = fieldset+legend.
//
// 시각: 분석 구간 segmented 동형 (InstrumentAnalysis 전례 — 테두리 박스 p-0.5,
// 선택 = bg-stone-900 text-white, 비선택 = text-stone-500 hover). 신규 시각 규격 발명 0.
import { useId } from 'react';
import type { ReactNode } from 'react';
import type { AxisDirective } from '../../lib/instrument-config-directive';

/** T-01 — 축 상태 라벨 3종 (PM 확정값 그대로 — "ON/OFF" 금지, UX §9.3-5). */
const DIRECTIVE_OPTIONS: ReadonlyArray<{ value: AxisDirective; label: string }> = [
  { value: 'none', label: '지시 없음' },
  { value: 'reduce', label: '줄이기' },
  { value: 'restore', label: '기동값으로 되돌리기' },
];

interface Props {
  /** 축 한국어 라벨 (legend 본문). */
  label: string;
  /** 원 키 병기 (회색 소문자 — curl 시절 사용자·docs 와 어긋나지 않게, UX §4.3). */
  axisKey: string;
  /** 방향 설명 1줄 (T-02 / T-03 / T-04 — 축별로 따로 적는다, W-2). */
  direction: ReactNode;
  value: AxisDirective;
  onChange: (next: AxisDirective) => void;
  /** C-02 — configLoading || mutating. */
  disabled: boolean;
  /** 축 아래 상시 참고 문구 (T-14 — captureParams 축만). */
  note?: ReactNode;
}

export function AxisDirectiveSelect({
  label,
  axisKey,
  direction,
  value,
  onChange,
  disabled,
  note,
}: Props): ReactNode {
  // 라디오 그룹 name — 축마다 고유 (한 화면에 3그룹 공존).
  const groupName = useId();
  return (
    <fieldset disabled={disabled} className="space-y-1.5">
      <legend className="text-sm font-medium text-stone-900">
        {label} <span className="font-normal lowercase text-stone-400">({axisKey})</span>
      </legend>
      <p className="text-xs text-stone-500">{direction}</p>
      <div className="inline-flex flex-wrap rounded-md border border-stone-200 bg-white p-0.5">
        {DIRECTIVE_OPTIONS.map((opt) => {
          const active = opt.value === value;
          return (
            <label key={opt.value} className={disabled ? 'cursor-not-allowed' : 'cursor-pointer'}>
              <input
                type="radio"
                name={groupName}
                value={opt.value}
                checked={active}
                onChange={() => onChange(opt.value)}
                disabled={disabled}
                className="peer sr-only"
              />
              {/* SH-15 — 포커스 링 의무 (숨긴 radio 의 focus-visible 을 보이는 라벨에 표출). */}
              <span
                className={
                  active
                    ? 'block rounded bg-stone-900 px-3 py-1.5 text-sm font-medium text-white peer-focus-visible:ring-1 peer-focus-visible:ring-stone-900 peer-focus-visible:ring-offset-2 peer-disabled:opacity-50'
                    : 'block rounded px-3 py-1.5 text-sm text-stone-500 hover:text-stone-900 peer-focus-visible:ring-1 peer-focus-visible:ring-stone-900 peer-focus-visible:ring-offset-2 peer-disabled:opacity-50'
                }
              >
                {opt.label}
              </span>
            </label>
          );
        })}
      </div>
      {note !== undefined && <p className="text-xs text-stone-500">{note}</p>}
    </fieldset>
  );
}
