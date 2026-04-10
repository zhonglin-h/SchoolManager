import { useState } from 'react'
import { useAttendanceToday } from '../hooks/useAttendanceToday'
import type { StudentAttendanceEntry } from '../services/api'

function statusBadge(status: string | null) {
  if (status === 'PRESENT') return <span className="text-green-600 font-medium">Present</span>
  if (status === 'LATE') return <span className="text-amber-500 font-medium">Late</span>
  if (status === 'ABSENT') return <span className="text-red-600 font-medium">Absent</span>
  return <span className="text-gray-400">Not recorded</span>
}

function AttendanceCard({ event }: { event: import('../services/api').AttendanceSummaryResponse }) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="bg-white rounded-lg shadow p-4 mb-4">
      <button
        className="w-full text-left"
        onClick={() => setExpanded((prev) => !prev)}
      >
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-base font-semibold text-gray-800">{event.eventTitle}</h3>
            <p className="text-sm text-gray-500">
              {event.date} &nbsp;·&nbsp; {event.startTime} – {event.endTime}
            </p>
          </div>
          <div className="text-right">
            <span className="text-sm font-medium text-gray-700">
              {event.present} / {event.totalExpected} present
            </span>
            <span className="ml-3 text-gray-400 text-sm">{expanded ? '▲' : '▼'}</span>
          </div>
        </div>
      </button>

      {expanded && (
        <div className="mt-3 overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 border-b">
                <th className="pb-1 pr-6 font-medium">Name</th>
                <th className="pb-1 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {event.students.map((s: StudentAttendanceEntry) => (
                <tr key={s.studentId} className="border-b last:border-0">
                  <td className="py-1 pr-6 text-gray-800">{s.name}</td>
                  <td className="py-1">{statusBadge(s.status)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

export default function Dashboard() {
  const { data, isLoading, isError } = useAttendanceToday()

  return (
    <div>
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Dashboard</h1>
      <section>
        <h2 className="text-lg font-semibold text-gray-700 mb-3">Today's Attendance</h2>

        {isLoading && <p className="text-gray-500">Loading attendance…</p>}

        {isError && (
          <p className="text-red-500">Failed to load attendance. Is the backend running?</p>
        )}

        {data && data.length === 0 && (
          <p className="text-gray-500">No classes scheduled for today.</p>
        )}

        {data && data.map((event) => (
          <AttendanceCard key={event.calendarEventId} event={event} />
        ))}
      </section>
    </div>
  )
}
