import { useState, useMemo, useCallback } from 'react'
import {
  useReactTable,
  getCoreRowModel,
  getPaginationRowModel,
  flexRender,
  type ColumnDef,
} from '@tanstack/react-table'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getAttendanceRecords, upsertAttendance } from '../services/api'
import type { AttendanceRecord } from '../services/api'

type PersonTypeFilter = 'ALL' | 'STUDENT' | 'TEACHER'
type AttendanceStatus = 'PRESENT' | 'LATE' | 'ABSENT'
type DatePreset = 'today' | 'week' | 'custom' | 'all'

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

function toIsoDate(d: Date): string {
  return d.toISOString().split('T')[0]
}

function today(): string {
  return toIsoDate(new Date())
}

function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return toIsoDate(d)
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

interface EditModal {
  record: AttendanceRecord
  selected: AttendanceStatus
}

const PAGE_SIZE_OPTIONS = [20, 50, 100]

export default function AttendanceRecords() {
  const queryClient = useQueryClient()

  // ── Filter state ──────────────────────────────────────────────────────────
  const [personTypeFilter, setPersonTypeFilter] = useState<PersonTypeFilter>('ALL')
  const [datePreset, setDatePreset] = useState<DatePreset>('week')
  const [customFrom, setCustomFrom] = useState<string>(daysAgo(30))
  const [customTo, setCustomTo] = useState<string>(today())
  const [statusFilters, setStatusFilters] = useState<Set<AttendanceStatus>>(new Set(['ABSENT', 'LATE']))
  const [nameSearch, setNameSearch] = useState('')
  const [editModal, setEditModal] = useState<EditModal | null>(null)

  // Compute dateFrom / dateTo from preset
  const { dateFrom, dateTo } = useMemo(() => {
    if (datePreset === 'today') return { dateFrom: today(), dateTo: today() }
    if (datePreset === 'week') return { dateFrom: daysAgo(7), dateTo: today() }
    if (datePreset === 'custom') return { dateFrom: customFrom, dateTo: customTo }
    return { dateFrom: undefined, dateTo: undefined }
  }, [datePreset, customFrom, customTo])

  // statusArray: explicitly pass the selected statuses to the backend.
  // Empty set (no selection) is treated as "show all" on the backend (no filter param sent).
  const statusArray = useMemo(
    () => Array.from(statusFilters),
    [statusFilters],
  )

  const { data = [], isLoading, isError } = useQuery({
    queryKey: ['attendance', 'records', personTypeFilter, dateFrom, dateTo, statusArray],
    queryFn: () => getAttendanceRecords(
      personTypeFilter === 'ALL' ? undefined : personTypeFilter,
      undefined,
      dateFrom,
      dateTo,
      statusArray.length > 0 ? statusArray : undefined,
    ),
  })

  // Client-side name filter
  const filteredData = useMemo(() => {
    const q = nameSearch.trim().toLowerCase()
    if (!q) return data
    return data.filter((r) => r.personName?.toLowerCase().includes(q))
  }, [data, nameSearch])

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

  const toggleStatus = useCallback((s: AttendanceStatus) => {
    setStatusFilters((prev) => {
      const next = new Set(prev)
      if (next.has(s)) next.delete(s)
      else next.add(s)
      return next
    })
  }, [])

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
    data: filteredData,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    initialState: { pagination: { pageSize: 20 } },
  })

  const { pageIndex, pageSize } = table.getState().pagination

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-bold text-gray-900">Attendance Records</h1>
      </div>

      {/* ── Filters ── */}
      <div className="bg-white rounded-lg shadow p-4 mb-4 flex flex-wrap gap-4 items-end">

        {/* Person type */}
        <div>
          <p className="text-xs font-medium text-gray-500 mb-1">Show</p>
          <div className="flex items-center gap-1">
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
                {opt === 'ALL' ? 'All people' : opt === 'STUDENT' ? 'Students' : 'Teachers'}
              </button>
            ))}
          </div>
        </div>

        {/* Date preset */}
        <div>
          <p className="text-xs font-medium text-gray-500 mb-1">Date range</p>
          <div className="flex items-center gap-1">
            {(['today', 'week', 'custom', 'all'] as DatePreset[]).map((p) => (
              <button
                key={p}
                onClick={() => setDatePreset(p)}
                className={`px-3 py-1 rounded border text-sm transition-colors ${
                  datePreset === p
                    ? 'bg-blue-600 text-white border-blue-600'
                    : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
                }`}
              >
                {p === 'today' ? 'Today' : p === 'week' ? 'Past 7 days' : p === 'custom' ? 'Custom' : 'All time'}
              </button>
            ))}
          </div>
        </div>

        {/* Custom date inputs */}
        {datePreset === 'custom' && (
          <div className="flex items-center gap-2">
            <div>
              <p className="text-xs font-medium text-gray-500 mb-1">From</p>
              <input
                type="date"
                value={customFrom}
                max={customTo}
                onChange={(e) => setCustomFrom(e.target.value)}
                className="border border-gray-300 rounded px-2 py-1 text-sm"
              />
            </div>
            <div>
              <p className="text-xs font-medium text-gray-500 mb-1">To</p>
              <input
                type="date"
                value={customTo}
                min={customFrom}
                max={today()}
                onChange={(e) => setCustomTo(e.target.value)}
                className="border border-gray-300 rounded px-2 py-1 text-sm"
              />
            </div>
          </div>
        )}

        {/* Status filter */}
        <div>
          <p className="text-xs font-medium text-gray-500 mb-1">Status</p>
          <div className="flex items-center gap-1">
            {STATUS_OPTIONS.map((s) => {
              const active = statusFilters.has(s)
              const colorClass = s === 'PRESENT'
                ? active ? 'bg-green-600 text-white border-green-600' : 'bg-white text-green-700 border-green-300 hover:bg-green-50'
                : s === 'LATE'
                ? active ? 'bg-amber-500 text-white border-amber-500' : 'bg-white text-amber-600 border-amber-300 hover:bg-amber-50'
                : active ? 'bg-red-600 text-white border-red-600' : 'bg-white text-red-600 border-red-300 hover:bg-red-50'
              return (
                <button
                  key={s}
                  onClick={() => toggleStatus(s)}
                  className={`px-3 py-1 rounded border text-sm transition-colors ${colorClass}`}
                >
                  {s.charAt(0) + s.slice(1).toLowerCase()}
                </button>
              )
            })}
          </div>
        </div>

        {/* Name search */}
        <div>
          <p className="text-xs font-medium text-gray-500 mb-1">Name</p>
          <input
            type="text"
            placeholder="Search name…"
            value={nameSearch}
            onChange={(e) => setNameSearch(e.target.value)}
            className="border border-gray-300 rounded px-2 py-1 text-sm w-40"
          />
        </div>
      </div>

      {isLoading && <p className="text-gray-500">Loading records…</p>}
      {isError && <p className="text-red-500">Failed to load attendance records.</p>}

      {!isLoading && !isError && (
        <>
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

          {/* ── Pagination ── */}
          {table.getPageCount() > 1 && (
            <div className="flex items-center justify-between mt-3 text-sm text-gray-600">
              <div className="flex items-center gap-2">
                <span>Rows per page:</span>
                <select
                  value={pageSize}
                  onChange={(e) => table.setPageSize(Number(e.target.value))}
                  className="border border-gray-300 rounded px-2 py-0.5 text-sm"
                >
                  {PAGE_SIZE_OPTIONS.map((s) => (
                    <option key={s} value={s}>{s}</option>
                  ))}
                </select>
              </div>
              <div className="flex items-center gap-1">
                <span className="mr-2">
                  {pageIndex * pageSize + 1}–{Math.min((pageIndex + 1) * pageSize, filteredData.length)} of {filteredData.length}
                </span>
                <button
                  onClick={() => table.setPageIndex(0)}
                  disabled={!table.getCanPreviousPage()}
                  className="px-2 py-0.5 border rounded disabled:opacity-40 hover:bg-gray-50"
                >«</button>
                <button
                  onClick={() => table.previousPage()}
                  disabled={!table.getCanPreviousPage()}
                  className="px-2 py-0.5 border rounded disabled:opacity-40 hover:bg-gray-50"
                >‹</button>
                <button
                  onClick={() => table.nextPage()}
                  disabled={!table.getCanNextPage()}
                  className="px-2 py-0.5 border rounded disabled:opacity-40 hover:bg-gray-50"
                >›</button>
                <button
                  onClick={() => table.setPageIndex(table.getPageCount() - 1)}
                  disabled={!table.getCanNextPage()}
                  className="px-2 py-0.5 border rounded disabled:opacity-40 hover:bg-gray-50"
                >»</button>
              </div>
            </div>
          )}
        </>
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
