// Toast context + types — Toast.tsx (Provider) 와 useToast.ts (hook) 양쪽이 import.
//
// Fast refresh 가 동작하도록 컴포넌트 모듈과 분리되어 있다.
import { createContext } from 'react';

export type ToastKind = 'success' | 'error';

export interface ToastItem {
  id: number;
  kind: ToastKind;
  message: string;
}

export interface ToastApi {
  success: (message: string) => void;
  error: (message: string) => void;
}

export const ToastContext = createContext<ToastApi | null>(null);
