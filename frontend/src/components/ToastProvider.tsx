import { useEffect, useState, useCallback } from 'react'
import { registerShowError, unregisterShowError } from '../context/toastStore'

interface Toast {
  id: number
  message: string
}

let nextId = 0

export default function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const addToast = useCallback((message: string) => {
    const id = nextId++
    setToasts((prev) => [...prev, { id, message }])
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, 6000)
  }, [])

  const dismiss = (id: number) => setToasts((prev) => prev.filter((t) => t.id !== id))

  useEffect(() => {
    registerShowError(addToast)
    return () => unregisterShowError()
  }, [addToast])

  return (
    <>
      {children}
      {toasts.length > 0 && (
        <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2 w-80">
          {toasts.map((t) => (
            <div
              key={t.id}
              className="flex items-start gap-3 bg-red-50 border border-red-200 text-red-800 rounded-lg shadow-lg px-4 py-3 text-sm"
            >
              <span className="flex-1 break-words">{t.message}</span>
              <button
                onClick={() => dismiss(t.id)}
                className="shrink-0 text-red-400 hover:text-red-600 font-bold leading-none mt-0.5"
                aria-label="Dismiss"
              >
                ✕
              </button>
            </div>
          ))}
        </div>
      )}
    </>
  )
}
