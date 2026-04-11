import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getAttendanceToday } from '../services/api'
import { useState } from 'react'

export function useAttendanceToday() {
  const queryClient = useQueryClient()
  const [isLiveRefreshing, setIsLiveRefreshing] = useState(false)

  const query = useQuery({
    queryKey: ['attendance', 'today'],
    queryFn: () => getAttendanceToday(false),
  })

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
