import { useState, useMemo } from 'react'
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  type ColumnDef,
} from '@tanstack/react-table'
import { useQuery } from '@tanstack/react-query'
import { getAttendanceRecords } from '../services/api'
import type { AttendanceRecord } from '../services/api'

type PersonTypeFilter = 'ALL' | 'STUDENT' | 'TEACHER'

const STATUS_STYLES: Record<string, string> = {
  PRESENT: 'text-green-600 font-medium',
  LATE: 'text-amber-500 font-medium',
  ABSENT: 'text-red-600 font-medium',
}

const TYPE_STYLES: Record<string, string> = {
  STUDENT: 'bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded text-xs',
  TEACHER: 'bg-purple-50 text-purple-700 px-1.5 py-0.5 rounded text-xs',
}

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

export default function AttendanceRecords() {
  const [personTypeFilter, setPersonTypeFilter] = useState<PersonTypeFilter>('ALL')

  const { data = [], isLoading, isError } = useQuery({
    queryKey: ['attendance', 'records', personTypeFilter],
    queryFn: () => getAttendanceRecords(personTypeFilter),
  })

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
        cell: ({ getValue }) => (
          <span className="text-gray-700">{getValue<string | null>() ?? <span className="text-gray-400 italic">—</span>}</span>
        ),
      },
      {
        accessorKey: 'status',
        header: 'Status',
        cell: ({ getValue }) => {
          const s = getValue<string>()
          return <span className={STATUS_STYLES[s] ?? 'text-gray-600'}>{s}</span>
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
    [],
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
    </div>
  )
}
