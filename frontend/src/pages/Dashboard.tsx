import { useState } from 'react'
import { useAttendanceToday } from '../hooks/useAttendanceToday'
import { useScheduledChecks } from '../hooks/useScheduledChecks'
import QuickAddModal from '../components/QuickAddModal'
import type { AttendanceEntry, GuestEntry, AttendanceSummaryResponse } from '../services/api'

const CHECK_TYPE_LABELS: Record<string, string> = {
  MEETING_NOT_STARTED_15: 'Meeting-started check (T−15)',
  PRE_CLASS_JOINS: 'Pre-class join check (T−3)',
  SESSION_START: 'Session start poll',
  SESSION_FINALIZE: 'Session finalize',
}

function formatRelativeTime(scheduledAt: string): string {
  const diff = Math.floor((new Date(scheduledAt).getTime() - Date.now()) / 1000)
  if (diff <= 0) return 'now'
  const h = Math.floor(diff / 3600)
  const m = Math.floor((diff % 3600) / 60)
  const s = diff % 60
  if (h > 0) return `in ${h}h ${m}m`
  if (m > 0) return `in ${m}m ${s}s`
  return `in ${s}s`
}

function statusBadge(status: string | null, personType: string | null, registered: boolean, inMeetNow: boolean) {
  if (!registered) return <span className="text-gray-400 italic text-xs">Not registered</span>
  if (status === 'PRESENT') return <span className="text-green-600 font-medium">Present</span>
  if (status === 'LATE') return <span className="text-amber-500 font-medium">Late</span>
  if (status === 'ABSENT') return <span className="text-red-600 font-medium">Absent</span>
  if (inMeetNow) return <span className="text-green-600 font-medium">In meeting</span>
  if (personType === 'TEACHER') return <span className="text-gray-400 text-xs">Teacher · not recorded</span>
  return <span className="text-gray-400">Not recorded</span>
}

function MeetingActiveDot({ active }: { active: boolean | null }) {
  if (active === null) return null
  if (active) {
    return (
      <span className="relative flex h-2.5 w-2.5 ml-2 mt-0.5">
        <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75" />
        <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-green-500" />
      </span>
    )
  }
  return <span className="inline-flex rounded-full h-2.5 w-2.5 bg-gray-300 ml-2 mt-0.5" />
}

function AttendanceCard({ event }: { event: AttendanceSummaryResponse }) {
  const [expanded, setExpanded] = useState(false)
  const [quickAdd, setQuickAdd] = useState<{ email: string; initialName?: string; mode: 'student' | 'teacher' } | null>(null)

  return (
    <div className="bg-white rounded-lg shadow p-4 mb-4">
      <button
        className="w-full text-left"
        onClick={() => setExpanded((prev) => !prev)}
      >
        <div className="flex items-center justify-between">
          <div className="flex items-start gap-1">
            <h3 className="text-base font-semibold text-gray-800">{event.eventTitle}</h3>
            <MeetingActiveDot active={event.meetingActive} />
          </div>
          <div className="text-right">
            <p className="text-xs text-gray-400 mb-0.5">
              {event.date} &nbsp;·&nbsp; {event.startTime} – {event.endTime}
            </p>
            {event.totalExpected > 0 && event.present >= event.totalExpected ? (
              <span className="text-sm font-semibold text-green-600">
                All {event.totalExpected} present
              </span>
            ) : (
              <span className="text-sm font-medium text-gray-700">
                {event.present} / {event.totalExpected} present
              </span>
            )}
            <span className="ml-3 text-gray-400 text-sm">{expanded ? '▲' : '▼'}</span>
          </div>
        </div>
      </button>

      {expanded && (
        <div className="mt-3 overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 border-b">
                <th className="pb-1 pr-6 font-medium">Participant</th>
                <th className="pb-1 pr-6 font-medium">Status</th>
                <th className="pb-1 font-medium" />
              </tr>
            </thead>
            <tbody>
              {event.students.map((entry: AttendanceEntry, i: number) => (
                <tr key={entry.email ?? i} className="border-b last:border-0">
                  <td className="py-1 pr-6">
                    {entry.registered ? (
                      <span className="text-gray-800">{entry.name}</span>
                    ) : (
                      <span className="text-gray-400 italic">{entry.email}</span>
                    )}
                  </td>
                  <td className="py-1 pr-6">
                    {statusBadge(entry.status, entry.personType, entry.registered, entry.inMeetNow)}
                  </td>
                  <td className="py-1">
                    {!entry.registered && (
                      <div className="flex gap-1">
                        <button
                          onClick={() => setQuickAdd({ email: entry.email, mode: 'student' })}
                          className="text-xs bg-blue-50 text-blue-600 hover:bg-blue-100 px-2 py-0.5 rounded"
                        >
                          + Student
                        </button>
                        <button
                          onClick={() => setQuickAdd({ email: entry.email, mode: 'teacher' })}
                          className="text-xs bg-purple-50 text-purple-600 hover:bg-purple-100 px-2 py-0.5 rounded"
                        >
                          + Teacher
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {event.guests && event.guests.length > 0 && (
            <div className="mt-3 pt-3 border-t border-gray-100">
          <p className="text-xs font-medium text-gray-400 uppercase tracking-wide mb-2">Others in meeting</p>
          <table className="min-w-full text-sm">
            <tbody>
              {event.guests.map((guest: GuestEntry, i: number) => (
                <tr key={guest.googleUserId ?? i} className="border-b last:border-0">
                  <td className="py-1 pr-6">
                    {guest.registeredName ? (
                      <span className="text-gray-800">{guest.registeredName}</span>
                    ) : (
                      <span className="text-gray-500 italic">{guest.displayName ?? 'Unknown'}</span>
                    )}
                  </td>
                  <td className="py-1 pr-6">
                    {guest.personType === 'STUDENT' && <span className="text-xs text-gray-400">Student · not invited</span>}
                    {guest.personType === 'TEACHER' && <span className="text-xs text-gray-400">Teacher · not invited</span>}
                    {!guest.personType && <span className="text-xs text-gray-400">Not registered</span>}
                  </td>
                  <td className="py-1">
                    {!guest.personType && (
                      <div className="flex gap-1">
                        <button
                          onClick={() => setQuickAdd({ email: '', initialName: guest.displayName ?? '', mode: 'student' })}
                          className="text-xs bg-blue-50 text-blue-600 hover:bg-blue-100 px-2 py-0.5 rounded"
                        >
                          + Student
                        </button>
                        <button
                          onClick={() => setQuickAdd({ email: '', initialName: guest.displayName ?? '', mode: 'teacher' })}
                          className="text-xs bg-purple-50 text-purple-600 hover:bg-purple-100 px-2 py-0.5 rounded"
                        >
                          + Teacher
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
            </div>
          )}
        </div>
      )}

      {quickAdd && (
        <QuickAddModal
          email={quickAdd.email}
          initialName={quickAdd.initialName}
          mode={quickAdd.mode}
          onClose={() => setQuickAdd(null)}
        />
      )}
    </div>
  )
}

export default function Dashboard() {
  const { data, isLoading, isFetching, isLiveRefreshing, refreshLive, isError } = useAttendanceToday()
  const { data: checks = [] } = useScheduledChecks()

  return (
    <div>
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Dashboard</h1>

      {(isFetching && !isLoading || isLiveRefreshing) && (
        <div className="fixed bottom-4 right-4 bg-gray-800 text-white text-xs px-3 py-2 rounded shadow-lg z-50 animate-pulse">
          Refreshing…
        </div>
      )}

      <section className="mb-8">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-semibold text-gray-700">Today's Attendance</h2>
          <button
            onClick={refreshLive}
            disabled={isLiveRefreshing}
            className="text-xs text-blue-600 hover:text-blue-800 disabled:opacity-50 flex items-center gap-1"
          >
            ↻ Refresh live
          </button>
        </div>

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

      <section>
        <h2 className="text-lg font-semibold text-gray-700 mb-3">Upcoming Checks</h2>
        {checks.length === 0 ? (
          <p className="text-gray-500 text-sm">No checks scheduled for today.</p>
        ) : (
          <div className="bg-white rounded-lg shadow divide-y divide-gray-100">
            {checks.slice(0, 5).map((check, i) => (
              <div key={i} className="px-4 py-2.5 flex items-center justify-between text-sm">
                <div>
                  <span className="font-medium text-gray-800">{check.eventTitle}</span>
                  <span className="text-gray-400 mx-2">·</span>
                  <span className="text-gray-500">
                    {CHECK_TYPE_LABELS[check.checkType] ?? check.checkType}
                  </span>
                </div>
                <span className="text-gray-400 text-xs tabular-nums">
                  {formatRelativeTime(check.scheduledAt)}
                </span>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
