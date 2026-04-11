import { useState, useMemo } from 'react'
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  type ColumnDef,
} from '@tanstack/react-table'
import {
  useStudents,
  useCreateStudent,
  useUpdateStudent,
  useDeleteStudent,
} from '../hooks/useStudents'
import type { StudentResponse, StudentRequest } from '../services/api'

const EMPTY_FORM: StudentRequest = {
  name: '',
  meetEmail: '',
  meetDisplayName: '',
  classroomEmail: '',
  parentEmail: '',
  parentPhone: '',
}

export default function Students() {
  const { data: allStudents = [], isLoading, isError } = useStudents()
  const createStudent = useCreateStudent()
  const updateStudent = useUpdateStudent()
  const deleteStudent = useDeleteStudent()

  const [showInactive, setShowInactive] = useState(false)
  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [form, setForm] = useState<StudentRequest>(EMPTY_FORM)

  const students = useMemo(
    () => (showInactive ? allStudents : allStudents.filter((s) => s.active)),
    [allStudents, showInactive],
  )

  function openCreate() {
    setEditingId(null)
    setForm(EMPTY_FORM)
    setShowForm(true)
  }

  function openEdit(student: StudentResponse) {
    setEditingId(student.id)
    setForm({
      name: student.name,
      meetEmail: student.meetEmail,
      meetDisplayName: student.meetDisplayName ?? '',
      classroomEmail: student.classroomEmail,
      parentEmail: student.parentEmail,
      parentPhone: student.parentPhone,
    })
    setShowForm(true)
  }

  function closeForm() {
    setShowForm(false)
    setEditingId(null)
    setForm(EMPTY_FORM)
  }

  function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }))
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (editingId !== null) {
      updateStudent.mutate({ id: editingId, data: form }, { onSuccess: closeForm })
    } else {
      createStudent.mutate(form, { onSuccess: closeForm })
    }
  }

  function handleDelete(id: number) {
    if (window.confirm('Deactivate this student?')) {
      deleteStudent.mutate(id)
    }
  }

  const columns = useMemo<ColumnDef<StudentResponse>[]>(
    () => [
      { accessorKey: 'name', header: 'Name' },
      { accessorKey: 'meetEmail', header: 'Meet Email' },
      { accessorKey: 'parentEmail', header: 'Parent Email' },
      { accessorKey: 'parentPhone', header: 'Parent Phone' },
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
    data: students,
    columns,
    getCoreRowModel: getCoreRowModel(),
  })

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-bold text-gray-900">Students</h1>
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
            + Add Student
          </button>
        </div>
      </div>

      {showForm && (
        <div className="bg-white rounded-lg shadow p-4 mb-6">
          <h2 className="text-base font-semibold text-gray-800 mb-3">
            {editingId !== null ? 'Edit Student' : 'New Student'}
          </h2>
          <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-3">
            {(
              [
                { name: 'name', label: 'Name', required: true },
                { name: 'meetEmail', label: 'Meet Email', required: true },
                { name: 'meetDisplayName', label: 'Meet Display Name', required: false },
                { name: 'classroomEmail', label: 'Classroom Email', required: true },
                { name: 'parentEmail', label: 'Parent Email', required: true },
                { name: 'parentPhone', label: 'Parent Phone', required: true },
              ] as { name: keyof StudentRequest; label: string; required: boolean }[]
            ).map(({ name, label, required }) => (
              <div key={name} className="flex flex-col gap-1">
                <label className="text-xs text-gray-500">
                  {label}{!required && <span className="text-gray-400"> (optional)</span>}
                </label>
                <input
                  name={name}
                  value={form[name]}
                  onChange={handleChange}
                  required={required}
                  className="border rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-400"
                />
              </div>
            ))}
            <div className="col-span-2 flex gap-2 mt-1">
              <button
                type="submit"
                disabled={createStudent.isPending || updateStudent.isPending}
                className="bg-blue-600 text-white text-sm px-4 py-1.5 rounded hover:bg-blue-700 disabled:opacity-50"
              >
                {createStudent.isPending || updateStudent.isPending ? 'Saving…' : editingId !== null ? 'Save Changes' : 'Create'}
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

      {isLoading && <p className="text-gray-500">Loading students…</p>}
      {isError && <p className="text-red-500">Failed to load students.</p>}

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
                    No students found.
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
