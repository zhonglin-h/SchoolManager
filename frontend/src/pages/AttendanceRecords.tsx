import { useState, useMemo, useCallback } from 'react'
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  type ColumnDef,
} from '@tanstack/react-table'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getAttendanceRecords, upsertAttendance } from '../services/api'
import type { AttendanceRecord } from '../services/api'

type PersonTypeFilter = 'ALL' | 'STUDENT' | 'TEACHER'
type AttendanceStatus = 'PRESENT' | 'LATE' | 'ABSENT'

const STATUS_STYLES: Record<string, string> = {
  PRESENT: 'text-green-600 font-medium',
  LATE: 'text-amber-500 font-medium',
  ABSENT: 'text-red-600 font-medium',
}

const TYPE_STYLES: Record<string, string> = {
  STUDENT: 'bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded text-xs',
  TEACHER: 'bg-purple-50 text-purple-700 px-1.5 py-0.5 rounded text-xs',
}

const STATUS_OPTIONS: AttendanceStatus[] = ['PRESENT', 'LATE', 'ABSENT']

function formatDate(dateStr: string): string {
  const d = new Date(dateStr + 'T00:00:00')
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

function formatDateTime(dtStr: string | null): string {
  if (!dtStr) return '—'
  return new Date(dtStr).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

interface EditModal {
  record: AttendanceRecord
  selected: AttendanceStatus
}

export default function AttendanceRecords() {
  const queryClient = useQueryClient()
  const [personTypeFilter, setPersonTypeFilter] = useState<PersonTypeFilter>('ALL')
  const [editModal, setEditModal] = useState<EditModal | null>(null)

  const { data = [], isLoading, isError } = useQuery({
    queryKey: ['attendance', 'records', personTypeFilter],
    queryFn: () => getAttendanceRecords(personTypeFilter),
  })

  const mutation = useMutation({
    mutationFn: (m: EditModal) =>
      upsertAttendance(
        m.record.personId!,
        m.record.personType as 'STUDENT' | 'TEACHER',
        m.record.calendarEventId,
        m.selected,
        m.record.date,
        m.record.eventTitle ?? undefined,
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['attendance', 'records'] })
      queryClient.invalidateQueries({ queryKey: ['attendance', 'today'] })
      setEditModal(null)
    },
  })

  const openEdit = useCallback((record: AttendanceRecord) => {
    mutation.reset()
    setEditModal({ record, selected: record.status as AttendanceStatus })
  }, [mutation])

  const columns = useMemo<ColumnDef<AttendanceRecord>[]>(
    () => [
      {
        accessorKey: 'date',
        header: 'Date',
        cell: ({ getValue }) => formatDate(getValue<string>()),
      },
      {
        accessorKey: 'personName',
        header: 'Person',
        cell: ({ getValue }) => (
          <span className="text-gray-800">{getValue<string | null>() ?? '—'}</span>
        ),
      },
      {
        accessorKey: 'personType',
        header: 'Type',
        cell: ({ getValue }) => {
          const type = getValue<string | null>()
          if (!type) return '—'
          return <span className={TYPE_STYLES[type] ?? 'text-xs text-gray-500'}>{type}</span>
        },
      },
      {
        accessorKey: 'eventTitle',
        header: 'Class',
        cell: ({ getValue }) => {
          const title = getValue<string | null>()
          return title
            ? <span className="text-gray-700">{title}</span>
            : <span className="text-gray-400 italic">—</span>
        },
      },
      {
        accessorKey: 'status',
        header: 'Status',
        cell: ({ getValue, row }) => {
          const s = getValue<string>()
          const record = row.original
          const canEdit = record.personId != null && record.personType != null
          return (
            <div className="flex items-center gap-1.5">
              <span className={STATUS_STYLES[s] ?? 'text-gray-600'}>{s}</span>
              {canEdit && (
                <button
                  onClick={() => openEdit(record)}
                  title="Edit status"
                  className="text-gray-400 hover:text-blue-600 transition-colors"
                >
                  ✎
                </button>
              )}
            </div>
          )
        },
      },
      {
        accessorKey: 'updatedAt',
        header: 'Last Updated',
        cell: ({ getValue }) => (
          <span className="text-gray-500 text-xs tabular-nums">{formatDateTime(getValue<string | null>())}</span>
        ),
      },
    ],
    [openEdit],
  )

  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data,
    columns,
    getCoreRowModel: getCoreRowModel(),
  })

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-bold text-gray-900">Attendance Records</h1>
        <div className="flex items-center gap-2 text-sm text-gray-600">
          <span>Show:</span>
          {(['ALL', 'STUDENT', 'TEACHER'] as PersonTypeFilter[]).map((opt) => (
            <button
              key={opt}
              onClick={() => setPersonTypeFilter(opt)}
              className={`px-3 py-1 rounded border text-sm transition-colors ${
                personTypeFilter === opt
                  ? 'bg-blue-600 text-white border-blue-600'
                  : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
              }`}
            >
              {opt === 'ALL' ? 'All' : opt === 'STUDENT' ? 'Students' : 'Teachers'}
            </button>
          ))}
        </div>
      </div>

      {isLoading && <p className="text-gray-500">Loading records…</p>}
      {isError && <p className="text-red-500">Failed to load attendance records.</p>}

      {!isLoading && !isError && (
        <div className="bg-white rounded-lg shadow overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="bg-gray-50 border-b">
              {table.getHeaderGroups().map((hg) => (
                <tr key={hg.id}>
                  {hg.headers.map((header) => (
                    <th
                      key={header.id}
                      className="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wide"
                    >
                      {flexRender(header.column.columnDef.header, header.getContext())}
                    </th>
                  ))}
                </tr>
              ))}
            </thead>
            <tbody className="divide-y divide-gray-100">
              {table.getRowModel().rows.length === 0 ? (
                <tr>
                  <td colSpan={columns.length} className="px-4 py-6 text-center text-gray-400">
                    No records found.
                  </td>
                </tr>
              ) : (
                table.getRowModel().rows.map((row) => (
                  <tr key={row.id} className="hover:bg-gray-50">
                    {row.getVisibleCells().map((cell) => (
                      <td key={cell.id} className="px-4 py-2 text-gray-800">
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    ))}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* Edit status modal */}
      {editModal && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-lg p-5 w-72">
            <h2 className="text-base font-semibold text-gray-800 mb-1">Edit Attendance Status</h2>
            <p className="text-xs text-gray-500 mb-4 truncate">
              {editModal.record.personName} · {formatDate(editModal.record.date)}
              {editModal.record.eventTitle ? ` · ${editModal.record.eventTitle}` : ''}
            </p>
            <div className="flex flex-col gap-2 mb-4">
              {STATUS_OPTIONS.map((s) => (
                <label key={s} className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="status"
                    value={s}
                    checked={editModal.selected === s}
                    onChange={() => setEditModal((prev) => prev ? { ...prev, selected: s } : prev)}
                    className="accent-blue-600"
                  />
                  <span className={STATUS_STYLES[s]}>{s}</span>
                </label>
              ))}
            </div>
            {mutation.isError && (
              <p className="text-xs text-red-500 mb-2">Failed to save. Please try again.</p>
            )}
            <div className="flex gap-2">
              <button
                onClick={() => mutation.mutate(editModal)}
                disabled={mutation.isPending}
                className="bg-blue-600 text-white text-sm px-4 py-1.5 rounded hover:bg-blue-700 disabled:opacity-50 flex-1"
              >
                {mutation.isPending ? 'Saving…' : 'Save'}
              </button>
              <button
                type="button"
                onClick={() => { setEditModal(null); mutation.reset() }}
                disabled={mutation.isPending}
                className="text-sm px-4 py-1.5 rounded border hover:bg-gray-50 disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
