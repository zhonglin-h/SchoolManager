import { useNotifications } from '../hooks/useNotifications'

export default function Notifications() {
  const { data, isLoading, isError } = useNotifications()

  return (
    <div>
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Notifications</h1>
      <p className="text-xs text-gray-400 mb-4">Auto-refreshes every 30 seconds.</p>

      {isLoading && <p className="text-gray-500">Loading notifications…</p>}
      {isError && <p className="text-red-500">Failed to load notifications.</p>}

      {!isLoading && !isError && (
        <div className="bg-white rounded-lg shadow overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="bg-gray-50 border-b">
              <tr>
                {['Status', 'Timestamp', 'Type', 'Recipient', 'Message', 'Channel'].map((h) => (
                  <th
                    key={h}
                    className="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wide"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data && data.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-gray-400">
                    No notifications yet.
                  </td>
                </tr>
              ) : (
                data?.map((n) => (
                  <tr key={n.id} className={n.success ? 'hover:bg-gray-50' : 'bg-red-50 hover:bg-red-100'}>
                    <td className="px-4 py-2">
                      {n.success ? (
                        <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-700">
                          Sent
                        </span>
                      ) : (
                        <span
                          className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-red-100 text-red-700 cursor-default"
                          title={n.failureReason ?? 'Unknown error'}
                        >
                          Failed
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-2 text-gray-600 whitespace-nowrap">{n.sentAt}</td>
                    <td className="px-4 py-2 text-gray-800 font-medium">{n.type}</td>
                    <td className="px-4 py-2 text-gray-800">
                      {n.studentId !== null ? `Student #${n.studentId}` : 'Principal'}
                    </td>
                    <td className="px-4 py-2 text-gray-700 max-w-xs">
                      <p className="truncate" title={n.message}>{n.message}</p>
                      {!n.success && n.failureReason && (
                        <p className="text-red-500 text-xs mt-0.5 truncate" title={n.failureReason}>
                          {n.failureReason}
                        </p>
                      )}
                    </td>
                    <td className="px-4 py-2 text-gray-600">{n.channel}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
