/** Module-level registry so api.ts (outside React) can trigger toasts. */
type ShowErrorFn = (message: string) => void

let _showError: ShowErrorFn | null = null

export function registerShowError(fn: ShowErrorFn) {
  _showError = fn
}

export function unregisterShowError() {
  _showError = null
}

export function showError(message: string) {
  _showError?.(message)
}
