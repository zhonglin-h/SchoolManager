import { useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { getAttendanceToday } from '../services/api'
import type { AttendanceSummaryResponse, GuestEntry } from '../services/api'
import { useMemo } from 'react'

interface LiveEventData {
  meetingActive: boolean | null
  inMeetNow: Record<string, boolean> // "STUDENT:1" -> true
  guests: GuestEntry[]
}

function extractLive(events: AttendanceSummaryResponse[]): Record<string, LiveEventData> {
  const map: Record<string, LiveEventData> = {}
  for (const event of events) {
    map[event.calendarEventId] = {
      meetingActive: event.meetingActive,
      guests: event.guests,
      inMeetNow: Object.fromEntries(
        event.students
          .filter((e) => e.personId != null)
          .map((e) => [`${e.personType}:${e.personId}`, e.inMeetNow])
      ),
    }
  }
  return map
}

function mergeWithLive(
  dbData: AttendanceSummaryResponse[],
  liveMap: Record<string, LiveEventData>
): AttendanceSummaryResponse[] {
  return dbData.map((event) => {
    const live = liveMap[event.calendarEventId]
    if (!live) return event
    const students = event.students.map((entry) => ({
      ...entry,
      inMeetNow: entry.personId != null
        ? (live.inMeetNow[`${entry.personType}:${entry.personId}`] ?? false)
        : false,
    }))
    const present = students.filter(
      (e) => e.status === 'PRESENT' || e.status === 'LATE'
    ).length
    return {
      ...event,
      meetingActive: live.meetingActive,
      guests: live.guests,
      students,
      present,
    }
  })
}

const LIVE_QUERY_KEY = ['attendance', 'today', 'live']

export function useAttendanceToday() {
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: ['attendance', 'today'],
    queryFn: () => {
      if (import.meta.env.DEV) console.log('[attendance] DB refresh')
      return getAttendanceToday(false)
    },
  })

  const liveQuery = useQuery({
    queryKey: LIVE_QUERY_KEY,
    queryFn: async () => {
      if (import.meta.env.DEV) console.log('[attendance] live refresh')
      const result = await getAttendanceToday(true)
      return extractLive(result)
    },
    refetchInterval: 60000,
    placeholderData: keepPreviousData,
    enabled: query.isSuccess,
  })

  const data = useMemo(
    () => (query.data ? mergeWithLive(query.data, liveQuery.data ?? {}) : query.data),
    [query.data, liveQuery.data]
  )

  function patchLiveGuest(
    calendarEventId: string,
    displayName: string,
    personId: number,
    personType: 'STUDENT' | 'TEACHER'
  ) {
    queryClient.setQueryData<Record<string, LiveEventData>>(LIVE_QUERY_KEY, (prev) => {
      if (!prev) return prev
      const event = prev[calendarEventId]
      if (!event) return prev
      return {
        ...prev,
        [calendarEventId]: {
          ...event,
          // Remove from guests — they'll appear in the students section after refetch
          guests: event.guests.filter((g: GuestEntry) => g.displayName !== displayName),
          // Mark as in-meeting so the student row shows "In meeting" after DB refetch
          inMeetNow: {
            ...event.inMeetNow,
            [`${personType}:${personId}`]: true,
          },
        },
      }
    })
    // Pull the newly registered person into the students list
    query.refetch()
  }

  return {
    ...query,
    data,
    isLiveRefreshing: liveQuery.isFetching,
    refreshLive: () => liveQuery.refetch(),
    patchLiveGuest,
  }
}
