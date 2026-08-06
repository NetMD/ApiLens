// [R21] `-D` 옵션 문자열 생성기 카드 — 원격 설정 화면과 같은 표면의 카드 3 (US-05, R-U2 확정).
//
// R21/AC-05-1 (Plan verbatim): "원격 설정 화면과 같은 표면에 붙는다 — Setup wizard 화면이 아니다
// (표면 분리 — NFR-08·G-16). wizard 빌더에 새 `-D` 키 배선 0." — 조립은 별도 순수 함수
// instrument-option-generator.ts (wizard agent-option-builder.ts **무접촉**).
// R21/AC-05-5 — 로컬 문자열 조립, 서버 호출 0. 입력은 상시 활성 (C-08 — 저장/철회 진행과 무관).
//
// 위 카드 1(원격 지시) 폼 상태와 **연동하지 않는다** (초기값 자동 이관 0 — UX §4.6): 위는 3상태
// 지시, 여기는 boolean `-D` 값 — 자동 이관은 "지시 없음" 을 boolean 으로 오역할 자리를 만든다.
//
// 복사 UX = Setup Step4 전례 그대로 (SH-02 라벨 "복사됨 ✓" 2초 + toast / SH-14 select-all 폴백 /
// 실패 문구 동일 — T-21 재사용).
import { useId, useState } from 'react';
import type { ReactNode } from 'react';
import { Toggle } from '../Toggle';
import { useToast } from '../useToast';
import {
  MYBATIS_MAPPER_PROXY,
  buildInstrumentOptionString,
} from '../../lib/instrument-option-generator';
import { ExcludeListEditor } from './ExcludeListEditor';

export function InstrumentOptionGeneratorCard(): ReactNode {
  const toast = useToast();
  const mybatisId = useId();
  // 기본값: 파라미터 캡처 = 옵션 기본 true / ResultSet 캡처 = 기본 false / 목록 빈 / MyBatis 해제 (UX §4.6).
  const [captureParams, setCaptureParams] = useState(true);
  const [captureResultSet, setCaptureResultSet] = useState(false);
  const [excludePackages, setExcludePackages] = useState<string[]>([]);
  const [mybatisAll, setMybatisAll] = useState(false);
  const [copied, setCopied] = useState(false);

  const generated = buildInstrumentOptionString({
    captureParams,
    captureResultSet,
    excludePackages,
    mybatisAll,
  });

  const handleCopy = async (): Promise<void> => {
    if (generated === '') return;
    try {
      await navigator.clipboard.writeText(generated);
      // SH-02 — 버튼 라벨 + toast 둘 다 (T-21 재사용).
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
      toast.success('붙여넣기용으로 복사했어요');
    } catch {
      toast.error('복사 실패 — 박스 내용을 직접 선택해 복사해 주세요');
    }
  };

  return (
    <section className="rounded-lg border border-stone-200 bg-white p-6">
      {/* U-40 */}
      <h2 className="text-base font-medium text-stone-900">-D 옵션 문자열 생성기 (영구화)</h2>
      {/* T-18 — 존재 이유 (시험 → 좋은 조합 확정 → -D 로 영구화의 마지막 칸). */}
      <p className="mt-1 text-xs text-stone-500">
        여기서 시험해 좋은 조합을 찾았다면, 마지막 칸은 영구화예요 — 원격 값은 메모리에만 있어 JVM
        을 재시작하면 사라져요. 아래에서 -D 문자열을 만들어 시작 옵션에 붙여 주세요.
      </p>

      <div className="mt-4 space-y-4">
        {/* C-08 — 생성기 입력 전부 상시 활성 (로컬 조립 — 서버 상태 무관). */}
        <div className="divide-y divide-stone-200 rounded-md border border-stone-200">
          <div className="px-3">
            <Toggle
              id="generator-capture-params"
              label="JDBC 파라미터 캡처 (-Dapilens.jdbc.capture-params)"
              // T-20 — 트레이드오프 ⓑ 상시 표시 (조건 분기 0).
              description="파라미터 캡처를 그대로 두면 비밀번호류가 평문으로 남을 수 있어요 — 이름 기반 마스킹 룰이 JDBC 파라미터 키에는 걸리지 않아요."
              checked={captureParams}
              onChange={setCaptureParams}
            />
          </div>
          <div className="px-3">
            <Toggle
              id="generator-capture-result-set"
              label="JDBC ResultSet 캡처 (-Dapilens.jdbc.capture-result-set)"
              // U-42
              description="결과(row) 캡처는 payload 가 커질 수 있어요"
              checked={captureResultSet}
              onChange={setCaptureResultSet}
            />
          </div>
        </div>

        <div className="space-y-2">
          <p className="text-sm font-medium text-stone-900">
            계측 제외 패키지{' '}
            <span className="font-normal lowercase text-stone-400">
              (-Dapilens.instrument.exclude-packages)
            </span>
          </p>
          {/* 검증 규칙이 gateExcludes 쪽과 다른 것이 의도다 (설계 §2.5) — 콤마 금지 + trim 만,
              개수·길이 상한 없음 (서버 검증 없는 로컬 조립). maxItems/maxItemLength 미지정. */}
          <ExcludeListEditor
            items={excludePackages}
            onItemsChange={setExcludePackages}
            disabled={false}
            removeDisabled={false}
            placeholder="패키지 prefix 입력"
            inputLabel="계측 제외 패키지 추가"
          />
          {/* U-36 */}
          <p className="text-xs text-stone-500">
            패키지 prefix 는 끝에 점(.)을 붙이면 경계가 명확해요 (예: com.acme.)
          </p>
          {/* U-35 — mapper 이관 무효 안내 (상시). */}
          <p className="text-xs text-stone-500">
            위 개별 제외 목록(gateExcludes)은 여기로 자동으로 옮기지 않아요 — 특히 MyBatis mapper
            인터페이스 이름은 -D 계측 제외에 적어도 효과가 없어요. 통째로 빼려면 아래 MyBatis 전량
            제외를 쓰세요.
          </p>
        </div>

        <div className="space-y-1">
          <div className="flex items-start gap-2">
            <input
              id={mybatisId}
              type="checkbox"
              checked={mybatisAll}
              onChange={(e) => setMybatisAll(e.target.checked)}
              className="mt-0.5 h-4 w-4 rounded border-stone-300 accent-stone-900 focus:ring-1 focus:ring-stone-900"
            />
            {/* T-22 — MYBATIS_MAPPER_PROXY 상수를 그대로 렌더 (단일 출처). */}
            <label htmlFor={mybatisId} className="text-sm text-stone-900">
              MyBatis 전량 제외{' '}
              <span className="font-mono text-xs text-stone-500">({MYBATIS_MAPPER_PROXY} 추가)</span>
            </label>
          </div>
          {/* T-19 — 트레이드오프 ⓐ. */}
          <p className="pl-6 text-xs text-stone-500">
            MyBatis 를 통째로 빼면 SQL 과 mapper 메서드의 대응이 사라져요 (SQL 자체는 DB 구간에
            남아요).
          </p>
        </div>

        <div className="relative">
          {/* SH-14 — code 박스 select-all (clipboard 권한 거부 fallback). Setup Step4 동형. */}
          <code
            className="block max-h-60 select-all overflow-auto whitespace-pre-wrap break-all rounded-md bg-stone-900 p-4 pr-20 font-mono text-xs leading-relaxed text-stone-50"
            aria-label="-D 옵션 문자열"
          >
            {generated === '' ? (
              // U-34 — 결핍 어휘 아님, "(정상)" 병기.
              <span className="text-stone-400">기동 기본값 그대로예요 — 붙일 -D 가 없어요 (정상)</span>
            ) : (
              generated
            )}
          </code>
          <button
            type="button"
            onClick={() => void handleCopy()}
            // C-07 — 생성 결과가 빈 문자열이면 [복사] 비활성.
            disabled={generated === ''}
            className="absolute right-2 top-2 rounded bg-stone-700 px-2 py-1 text-xs text-stone-50 hover:bg-stone-600 disabled:cursor-not-allowed disabled:opacity-50"
            aria-label="-D 옵션 문자열 복사"
          >
            {copied ? '복사됨 ✓' : '복사'}
          </button>
        </div>

        {/* U-37 — require-entry-root 비범위 안내 (의문 사전 차단). */}
        <p className="text-xs text-stone-500">
          require-entry-root 는 이 생성기에 없어요 — 필요하면 docs 의 옵션 표를 보고 직접 붙여
          주세요.
        </p>
      </div>
    </section>
  );
}
