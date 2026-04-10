import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createStudent, createTeacher } from '../services/api'

interface Props {
  email: string
  mode: 'student' | 'teacher'
  onClose: () => void
}

export default function QuickAddModal({ email, mode, onClose }: Props) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [meetDisplayName, setMeetDisplayName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      if (mode === 'student') {
        await createStudent({
          name,
          meetEmail: email,
          meetDisplayName,
          classroomEmail: '',
          parentEmail: '',
          parentPhone: '',
        })
        queryClient.invalidateQueries({ queryKey: ['students'] })
      } else {
        await createTeacher({ name, meetEmail: email, meetDisplayName, phone: '', hourlyRate: null })
        queryClient.invalidateQueries({ queryKey: ['teachers'] })
      }
      queryClient.invalidateQueries({ queryKey: ['attendance', 'today'] })
      onClose()
    } catch {
      setError('Failed to save. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  const label = mode === 'student' ? 'Student' : 'Teacher'

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-lg p-5 w-80">
        <h2 className="text-base font-semibold text-gray-800 mb-1">Add as {label}</h2>
        <p className="text-xs text-gray-500 mb-4 truncate">{email}</p>
        <form onSubmit={handleSubmit} className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <label className="text-xs text-gray-500">Name</label>
            <input
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              className="border rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-400"
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs text-gray-500">Meet Display Name <span className="text-gray-400">(optional)</span></label>
            <input
              value={meetDisplayName}
              onChange={(e) => setMeetDisplayName(e.target.value)}
              className="border rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-400"
            />
          </div>
          {error && <p className="text-xs text-red-500">{error}</p>}
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={submitting}
              className="bg-blue-600 text-white text-sm px-4 py-1.5 rounded hover:bg-blue-700 disabled:opacity-50 flex-1"
            >
              {submitting ? 'Saving…' : `Add ${label}`}
            </button>
            <button
              type="button"
              onClick={onClose}
              className="text-sm px-4 py-1.5 rounded border hover:bg-gray-50"
            >
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
