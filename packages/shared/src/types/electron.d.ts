export {}

declare global {
  interface Window {
    electronAPI?: {
      getCallbackUrl?: () => Promise<string | null>
      openExternal?: (url: string) => Promise<{ ok: boolean; error?: string }>
      onAuthCallback?: (cb: (data: any) => void) => void
      onMicToggle?: (cb: () => void) => (() => void) | void
    }
    hotkey?: {
      get: () => Promise<{ toggleWindow?: string; toggleMic?: string }>
      set: (key: string, accelerator: string) => Promise<{ ok: boolean; error?: string }>
    }
  }
}
