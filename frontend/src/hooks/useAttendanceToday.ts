import { useQuery } from '@tanstack/react-query'
import { getAttendanceToday } from '../services/api'
import type { AttendanceSummaryResponse, GuestEntry } from '../services/api'
import { useState, useEffect, useRef, useMemo } from 'react'

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
      (e) => e.status === 'PRESENT' || e.status === 'LATE' || e.inMeetNow
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

export function useAttendanceToday() {
  const [isLiveRefreshing, setIsLiveRefreshing] = useState(false)
  const [liveData, setLiveData] = useState<Record<string, LiveEventData>>({})
  const initialLiveDoneRef = useRef(false)

  const query = useQuery({
    queryKey: ['attendance', 'today'],
    queryFn: () => {
      if (import.meta.env.DEV) console.log('[attendance] DB refresh')
      return getAttendanceToday(false)
    },
  })

  const data = useMemo(
    () => (query.data ? mergeWithLive(query.data, liveData) : query.data),
    [query.data, liveData]
  )

  // Once DB data first arrives, start the live refresh cycle
  useEffect(() => {
    if (query.data && !initialLiveDoneRef.current) {
      initialLiveDoneRef.current = true
      refreshLive()
      const interval = setInterval(refreshLive, 60000)
      return () => clearInterval(interval)
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query.data])

  async function refreshLive() {
    if (import.meta.env.DEV) console.log('[attendance] live refresh')
    setIsLiveRefreshing(true)
    try {
      const result = await getAttendanceToday(true)
      setLiveData(extractLive(result))
    } finally {
      setIsLiveRefreshing(false)
    }
  }

  function patchLiveGuest(
    calendarEventId: string,
    displayName: string,
    personId: number,
    personType: 'STUDENT' | 'TEACHER'
  ) {
    setLiveData((prev) => {
      const event = prev[calendarEventId]
      if (!event) return prev
      return {
        ...prev,
        [calendarEventId]: {
          ...event,
          guests: event.guests.map((g) =>
            g.displayName === displayName
              ? { ...g, personId, personType, registeredName: displayName }
              : g
          ),
        },
      }
    })
  }

  return { ...query, data, isLiveRefreshing, refreshLive, patchLiveGuest }
}
