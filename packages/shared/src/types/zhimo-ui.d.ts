declare module 'zhimo-ui' {
  export interface ToastOptions {
    type?: 'default' | 'success' | 'error' | 'warning'
    duration?: number
  }
  export function toast(message: string, options?: ToastOptions): HTMLElement
}

declare module 'zhimo-ui/tokens.css'
