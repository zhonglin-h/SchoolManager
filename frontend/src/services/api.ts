import axios from 'axios'
import { showError } from '../context/toastStore'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL as string,
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const message: string =
      error?.response?.data?.message ?? error?.message ?? 'An unexpected error occurred'
    showError(message)
    return Promise.reject(error)
  },
)

// ── Types ──────────────────────────────────────────────────────────────────

export interface StudentRequest {
  name: string
  meetEmail: string
  meetDisplayName: string
  classroomEmail: string
  parentEmail: string
  parentPhone: string
}

export interface StudentResponse {
  id: number
  name: string
  meetEmail: string
  meetDisplayName: string | null
  classroomEmail: string
  parentEmail: string
  parentPhone: string
  active: boolean
}

export interface TeacherRequest {
  name: string
  meetEmail: string
  meetDisplayName: string
  phone: string
  hourlyRate: number | null
}

export interface TeacherResponse {
  id: number
  name: string
  meetEmail: string
  meetDisplayName: string | null
  phone: string
  hourlyRate: number | null
  active: boolean
}

export interface AttendanceEntry {
  personId: number | null
  personType: 'STUDENT' | 'TEACHER' | null
  name: string
  email: string
  status: string | null
  registered: boolean
  inMeetNow: boolean
}

export interface GuestEntry {
  googleUserId: string | null
  displayName: string | null
  personId: number | null
  personType: 'STUDENT' | 'TEACHER' | null
  registeredName: string | null
}

export interface AttendanceSummaryResponse {
  calendarEventId: string
  spaceCode: string
  eventTitle: string
  date: string
  startTime: string
  endTime: string
  meetingActive: boolean | null
  totalExpected: number
  present: number
  late: number
  absent: number
  students: AttendanceEntry[]
  guests: GuestEntry[]
}

export interface AttendanceEntry2 {
  calendarEventId: string
  date: string
  status: string
}

export interface AttendanceRecord {
  id: number
  personId: number | null
  personType: 'STUDENT' | 'TEACHER' | null
  personName: string | null
  calendarEventId: string
  eventTitle: string | null
  date: string
  status: string
  updatedAt: string | null
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
  success: boolean
  failureReason: string | null
  recipient: string | null
}

export interface AppSettings {
  notificationsEnabled: boolean
}

export interface ScheduledCheck {
  eventId: string
  eventTitle: string
  checkType: string
  scheduledAt: string
}

export interface ScheduledChecksResponse {
  checks: ScheduledCheck[]
  total: number
  limit: number
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

// ── Teachers ───────────────────────────────────────────────────────────────

export async function getTeachers(): Promise<TeacherResponse[]> {
  const { data } = await api.get<TeacherResponse[]>('/teachers')
  return data
}

export async function createTeacher(payload: TeacherRequest): Promise<TeacherResponse> {
  const { data } = await api.post<TeacherResponse>('/teachers', payload)
  return data
}

export async function updateTeacher(id: number, payload: TeacherRequest): Promise<TeacherResponse> {
  const { data } = await api.put<TeacherResponse>(`/teachers/${id}`, payload)
  return data
}

export async function deleteTeacher(id: number): Promise<void> {
  await api.delete(`/teachers/${id}`)
}

// ── Attendance ─────────────────────────────────────────────────────────────

export async function getAttendanceToday(live = false): Promise<AttendanceSummaryResponse[]> {
  const { data } = await api.get<AttendanceSummaryResponse[]>('/attendance/today', {
    params: live ? { live: true } : undefined,
  })
  return data
}

export async function getStudentAttendance(id: number): Promise<AttendanceEntry2[]> {
  const { data } = await api.get<AttendanceEntry2[]>(`/attendance/student/${id}`)
  return data
}

export async function upsertAttendance(
  personId: number,
  personType: 'STUDENT' | 'TEACHER',
  calendarEventId: string,
  status: 'PRESENT' | 'LATE' | 'ABSENT',
  date?: string,
  eventTitle?: string
): Promise<void> {
  await api.post('/attendance/upsert', { personId, personType, calendarEventId, status, date, eventTitle })
}

export async function getAttendanceRecords(
  personType: 'ALL' | 'STUDENT' | 'TEACHER' = 'ALL',
  personId?: number,
  dateFrom?: string,
  dateTo?: string,
  status?: string[]
): Promise<AttendanceRecord[]> {
  const { data } = await api.get<AttendanceRecord[]>('/attendance/records', {
    params: {
      personType,
      ...(personId != null ? { personId } : {}),
      ...(dateFrom ? { dateFrom } : {}),
      ...(dateTo ? { dateTo } : {}),
      ...(status && status.length > 0 ? { status } : {}),
    },
  })
  return data
}

// ── Notifications ──────────────────────────────────────────────────────────

export async function getNotifications(): Promise<NotificationLogResponse[]> {
  const { data } = await api.get<NotificationLogResponse[]>('/notifications')
  return data
}

// ── Settings ───────────────────────────────────────────────────────────────

export async function getSettings(): Promise<AppSettings> {
  const { data } = await api.get<AppSettings>('/settings')
  return data
}

// ── Calendar ───────────────────────────────────────────────────────────────

export async function syncCalendar(): Promise<void> {
  await api.post('/calendar/sync')
}

export async function getScheduledChecks(): Promise<ScheduledChecksResponse> {
  const { data } = await api.get<ScheduledChecksResponse>('/calendar/scheduled-checks')
  return data
}
