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
    return {
      ...event,
      meetingActive: live.meetingActive,
      guests: live.guests,
      students: event.students.map((entry) => ({
        ...entry,
        inMeetNow: entry.personId != null
          ? (live.inMeetNow[`${entry.personType}:${entry.personId}`] ?? false)
          : false,
      })),
    }
  })
}

export function useAttendanceToday() {
  const [isLiveRefreshing, setIsLiveRefreshing] = useState(false)
  const [liveData, setLiveData] = useState<Record<string, LiveEventData>>({})
  const lastLiveFetchRef = useRef(0)

  const query = useQuery({
    queryKey: ['attendance', 'today'],
    queryFn: () => getAttendanceToday(false),
    refetchInterval: 60000,
  })

  const data = useMemo(
    () => (query.data ? mergeWithLive(query.data, liveData) : query.data),
    [query.data, liveData]
  )

  // After each DB fetch completes, kick off a background live refresh
  useEffect(() => {
    if (query.dataUpdatedAt > 0 && query.dataUpdatedAt !== lastLiveFetchRef.current) {
      lastLiveFetchRef.current = query.dataUpdatedAt
      refreshLive()
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query.dataUpdatedAt])

  async function refreshLive() {
    setIsLiveRefreshing(true)
    try {
      const result = await getAttendanceToday(true)
      setLiveData(extractLive(result))
    } finally {
      setIsLiveRefreshing(false)
    }
  }

  return { ...query, data, isLiveRefreshing, refreshLive }
}
