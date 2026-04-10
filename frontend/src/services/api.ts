import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL as string,
})

// ── Types ──────────────────────────────────────────────────────────────────

export interface StudentRequest {
  name: string
  meetEmail: string
  classroomEmail: string
  parentEmail: string
  parentPhone: string
}

export interface StudentResponse {
  id: number
  name: string
  meetEmail: string
  classroomEmail: string
  parentEmail: string
  parentPhone: string
  active: boolean
}

export interface StudentAttendanceEntry {
  studentId: number
  name: string
  status: string | null
}

export interface AttendanceSummaryResponse {
  calendarEventId: string
  eventTitle: string
  date: string
  startTime: string
  endTime: string
  totalExpected: number
  present: number
  late: number
  absent: number
  students: StudentAttendanceEntry[]
}

export interface AttendanceEntry {
  calendarEventId: string
  date: string
  status: string
}

export interface NotificationLogResponse {
  id: number
  studentId: number | null
  calendarEventId: string
  date: string
  type: string
  message: string
  sentAt: string
  channel: string
}

// ── Students ───────────────────────────────────────────────────────────────

export async function getStudents(): Promise<StudentResponse[]> {
  const { data } = await api.get<StudentResponse[]>('/students')
  return data
}

export async function getStudent(id: number): Promise<StudentResponse> {
  const { data } = await api.get<StudentResponse>(`/students/${id}`)
  return data
}

export async function createStudent(payload: StudentRequest): Promise<StudentResponse> {
  const { data } = await api.post<StudentResponse>('/students', payload)
  return data
}

export async function updateStudent(id: number, payload: StudentRequest): Promise<StudentResponse> {
  const { data } = await api.put<StudentResponse>(`/students/${id}`, payload)
  return data
}

export async function deleteStudent(id: number): Promise<void> {
  await api.delete(`/students/${id}`)
}

// ── Attendance ─────────────────────────────────────────────────────────────

export async function getAttendanceToday(): Promise<AttendanceSummaryResponse[]> {
  const { data } = await api.get<AttendanceSummaryResponse[]>('/attendance/today')
  return data
}

export async function getStudentAttendance(id: number): Promise<AttendanceEntry[]> {
  const { data } = await api.get<AttendanceEntry[]>(`/students/${id}/attendance`)
  return data
}

// ── Notifications ──────────────────────────────────────────────────────────

export async function getNotifications(): Promise<NotificationLogResponse[]> {
  const { data } = await api.get<NotificationLogResponse[]>('/notifications')
  return data
}

// ── Settings ───────────────────────────────────────────────────────────────

export interface AppSettings {
  notificationsEnabled: boolean
}

export async function getSettings(): Promise<AppSettings> {
  const { data } = await api.get<AppSettings>('/settings')
  return data
}

// ── Calendar ───────────────────────────────────────────────────────────────

export async function syncCalendar(): Promise<void> {
  await api.post('/calendar/sync')
}
