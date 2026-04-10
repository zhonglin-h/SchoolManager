import { useState, useMemo } from 'react'
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  type ColumnDef,
} from '@tanstack/react-table'
import {
  useTeachers,
  useCreateTeacher,
  useUpdateTeacher,
  useDeleteTeacher,
} from '../hooks/useTeachers'
import type { TeacherResponse, TeacherRequest } from '../services/api'

const EMPTY_FORM: TeacherRequest = {
  name: '',
  meetEmail: '',
  meetDisplayName: '',
  phone: '',
  hourlyRate: null,
}

export default function Teachers() {
  const { data: allTeachers = [], isLoading, isError } = useTeachers()
  const createTeacher = useCreateTeacher()
  const updateTeacher = useUpdateTeacher()
  const deleteTeacher = useDeleteTeacher()

  const [showInactive, setShowInactive] = useState(false)
  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [form, setForm] = useState<TeacherRequest>(EMPTY_FORM)

  const teachers = useMemo(
    () => (showInactive ? allTeachers : allTeachers.filter((t) => t.active)),
    [allTeachers, showInactive],
  )

  function openCreate() {
    setEditingId(null)
    setForm(EMPTY_FORM)
    setShowForm(true)
  }

  function openEdit(teacher: TeacherResponse) {
    setEditingId(teacher.id)
    setForm({
      name: teacher.name,
      meetEmail: teacher.meetEmail,
      meetDisplayName: teacher.meetDisplayName ?? '',
      phone: teacher.phone,
      hourlyRate: teacher.hourlyRate,
    })
    setShowForm(true)
  }

  function closeForm() {
    setShowForm(false)
    setEditingId(null)
    setForm(EMPTY_FORM)
  }

  function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    const { name, value } = e.target
    setForm((prev) => ({
      ...prev,
      [name]: name === 'hourlyRate' ? (value === '' ? null : Number(value)) : value,
    }))
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (editingId !== null) {
      updateTeacher.mutate({ id: editingId, data: form }, { onSuccess: closeForm })
    } else {
      createTeacher.mutate(form, { onSuccess: closeForm })
    }
  }

  function handleDelete(id: number) {
    if (window.confirm('Deactivate this teacher?')) {
      deleteTeacher.mutate(id)
    }
  }

  const columns = useMemo<ColumnDef<TeacherResponse>[]>(
    () => [
      { accessorKey: 'name', header: 'Name' },
      { accessorKey: 'meetEmail', header: 'Meet Email' },
      { accessorKey: 'phone', header: 'Phone' },
      {
        accessorKey: 'hourlyRate',
        header: 'Hourly Rate',
        cell: ({ getValue }) => {
          const v = getValue<number | null>()
          return v != null ? `$${v.toFixed(2)}` : '—'
        },
      },
      {
        id: 'actions',
        header: 'Actions',
        cell: ({ row }) => (
          <div className="flex gap-2">
            <button
              onClick={() => openEdit(row.original)}
              className="text-sm text-blue-600 hover:underline"
            >
              Edit
            </button>
            {row.original.active && (
              <button
                onClick={() => handleDelete(row.original.id)}
                className="text-sm text-red-500 hover:underline"
              >
                Delete
              </button>
            )}
          </div>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  const table = useReactTable({
    data: teachers,
    columns,
    getCoreRowModel: getCoreRowModel(),
  })

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-bold text-gray-900">Teachers</h1>
        <div className="flex items-center gap-4">
          <label className="flex items-center gap-2 text-sm text-gray-600 cursor-pointer">
            <input
              type="checkbox"
              checked={showInactive}
              onChange={(e) => setShowInactive(e.target.checked)}
              className="rounded"
            />
            Show inactive
          </label>
          <button
            onClick={openCreate}
            className="bg-blue-600 text-white text-sm px-3 py-1.5 rounded hover:bg-blue-700"
          >
            + Add Teacher
          </button>
        </div>
      </div>

      {showForm && (
        <div className="bg-white rounded-lg shadow p-4 mb-6">
          <h2 className="text-base font-semibold text-gray-800 mb-3">
            {editingId !== null ? 'Edit Teacher' : 'New Teacher'}
          </h2>
          <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1">
              <label className="text-xs text-gray-500">Name</label>
              <input
                name="name"
                value={form.name}
                onChange={handleChange}
                required
                className="border rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-400"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs text-gray-500">Meet Email</label>
              <input
                name="meetEmail"
                value={form.meetEmail}
                onChange={handleChange}
                required
                className="border rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-400"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs text-gray-500">Meet Display Name <span className="text-gray-400">(optional)</span></label>
              <input
                name="meetDisplayName"
                value={form.meetDisplayName}
                onChange={handleChange}
                className="border rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-400"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs text-gray-500">Phone</label>
              <input
                name="phone"
                value={form.phone}
                onChange={handleChange}
                className="border rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-400"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs text-gray-500">Hourly Rate ($)</label>
              <input
                name="hourlyRate"
                type="number"
                min="0"
                step="0.01"
                value={form.hourlyRate ?? ''}
                onChange={handleChange}
                className="border rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-400"
              />
            </div>
            <div className="col-span-2 flex gap-2 mt-1">
              <button
                type="submit"
                disabled={createTeacher.isPending || updateTeacher.isPending}
                className="bg-blue-600 text-white text-sm px-4 py-1.5 rounded hover:bg-blue-700 disabled:opacity-50"
              >
                {editingId !== null ? 'Save Changes' : 'Create'}
              </button>
              <button
                type="button"
                onClick={closeForm}
                className="text-sm px-4 py-1.5 rounded border hover:bg-gray-50"
              >
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      {isLoading && <p className="text-gray-500">Loading teachers…</p>}
      {isError && <p className="text-red-500">Failed to load teachers.</p>}

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
                    No teachers found.
                  </td>
                </tr>
              ) : (
                table.getRowModel().rows.map((row) => (
                  <tr
                    key={row.id}
                    className={`hover:bg-gray-50 ${!row.original.active ? 'opacity-50' : ''}`}
                  >
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
