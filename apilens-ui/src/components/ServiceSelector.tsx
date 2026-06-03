// 서비스 셀렉터 — GET /v1/services 데이터.
// 헤더에서 silent 처리 (에러는 본문 ErrorState에서 알림).
import type { ChangeEvent, ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listServices } from '../api/traces';

interface Props {
  value: string | null;
  onChange: (next: string | null) => void;
}

export function ServiceSelector({ value, onChange }: Props): ReactNode {
  const { data, isLoading } = useQuery({
    queryKey: ['services'],
    queryFn: ({ signal }) => listServices(signal),
    staleTime: 30_000,
    retry: 1,
  });

  const services = data?.services ?? [];

  const handleChange = (e: ChangeEvent<HTMLSelectElement>): void => {
    const next = e.target.value;
    onChange(next === '' ? null : next);
  };

  return (
    <label className="inline-flex items-center gap-2 text-sm text-stone-500">
      <span>Service</span>
      <select
        aria-label="Service"
        value={value ?? ''}
        onChange={handleChange}
        disabled={isLoading}
        className="rounded-md border border-stone-200 bg-white px-2 py-1.5 text-sm text-stone-900 disabled:bg-stone-50"
      >
        <option value="">— select —</option>
        {services.map((s) => (
          <option key={s.name} value={s.name}>
            {s.name} ({s.traceCount})
          </option>
        ))}
      </select>
    </label>
  );
}
