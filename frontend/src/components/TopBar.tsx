import { useSettings } from '../hooks/useSettings'

export default function TopBar() {
  const { data: settings } = useSettings()
  const enabled = settings?.notificationsEnabled

  return (
    <header className="h-12 bg-white border-b border-gray-200 flex items-center justify-end px-6 shrink-0">
      <div className="flex items-center gap-2 text-sm">
        <span className="text-gray-500">Notifications</span>
        <span
          className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
            enabled
              ? 'bg-green-100 text-green-700'
              : 'bg-gray-100 text-gray-500'
          }`}
        >
          {enabled === undefined ? '—' : enabled ? 'On' : 'Off'}
        </span>
      </div>
    </header>
  )
}
