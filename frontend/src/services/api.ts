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

export interface TeacherRequest {
  name: string
  meetEmail: string
  phone: string
  hourlyRate: number | null
}

export interface TeacherResponse {
  id: number
  name: string
  meetEmail: string
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
}

export interface AttendanceSummaryResponse {
  calendarEventId: string
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
}

export interface AttendanceEntry2 {
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

export interface AppSettings {
  notificationsEnabled: boolean
}

export interface ScheduledCheck {
  eventId: string
  eventTitle: string
  checkType: string
  scheduledAt: string
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

export async function getAttendanceToday(): Promise<AttendanceSummaryResponse[]> {
  const { data } = await api.get<AttendanceSummaryResponse[]>('/attendance/today')
  return data
}

export async function getStudentAttendance(id: number): Promise<AttendanceEntry2[]> {
  const { data } = await api.get<AttendanceEntry2[]>(`/attendance/student/${id}`)
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

export async function getScheduledChecks(): Promise<ScheduledCheck[]> {
  const { data } = await api.get<ScheduledCheck[]>('/calendar/scheduled-checks')
  return data
}
