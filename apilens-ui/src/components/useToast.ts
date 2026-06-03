// Toast hook — Provider 와 분리 (fast refresh 정합).
import { useContext } from 'react';
import { ToastContext } from './toast-context';
import type { ToastApi } from './toast-context';

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (ctx === null) {
    throw new Error('useToast must be used inside <ToastProvider>');
  }
  return ctx;
}
