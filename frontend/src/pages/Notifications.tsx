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
                {['Timestamp', 'Type', 'Recipient', 'Message', 'Channel'].map((h) => (
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
                  <td colSpan={5} className="px-4 py-6 text-center text-gray-400">
                    No notifications yet.
                  </td>
                </tr>
              ) : (
                data?.map((n) => (
                  <tr key={n.id} className="hover:bg-gray-50">
                    <td className="px-4 py-2 text-gray-600 whitespace-nowrap">{n.sentAt}</td>
                    <td className="px-4 py-2 text-gray-800 font-medium">{n.type}</td>
                    <td className="px-4 py-2 text-gray-800">
                      {n.studentId !== null ? `Student #${n.studentId}` : 'Principal'}
                    </td>
                    <td className="px-4 py-2 text-gray-700 max-w-xs truncate" title={n.message}>
                      {n.message}
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
