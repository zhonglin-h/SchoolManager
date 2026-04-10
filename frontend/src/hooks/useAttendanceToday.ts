import { useQuery } from '@tanstack/react-query'
import { getAttendanceToday } from '../services/api'

export function useAttendanceToday() {
  return useQuery({
    queryKey: ['attendance', 'today'],
    queryFn: getAttendanceToday,
    refetchInterval: 60000,
  })
}
