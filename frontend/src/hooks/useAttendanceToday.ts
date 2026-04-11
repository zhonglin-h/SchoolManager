import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getAttendanceToday } from '../services/api'
import { useState, useEffect, useRef } from 'react'

export function useAttendanceToday() {
  const queryClient = useQueryClient()
  const [isLiveRefreshing, setIsLiveRefreshing] = useState(false)
  const liveRefreshedRef = useRef(false)

  const query = useQuery({
    queryKey: ['attendance', 'today'],
    queryFn: () => getAttendanceToday(false),
    refetchInterval: 60000,
  })

  // Auto-fetch live data in the background once DB data is available
  useEffect(() => {
    if (query.data && !liveRefreshedRef.current && !isLiveRefreshing) {
      liveRefreshedRef.current = true
      refreshLive()
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query.data])

  async function refreshLive() {
    setIsLiveRefreshing(true)
    try {
      const data = await getAttendanceToday(true)
      queryClient.setQueryData(['attendance', 'today'], data)
    } finally {
      setIsLiveRefreshing(false)
    }
  }

  return { ...query, isLiveRefreshing, refreshLive }
}
