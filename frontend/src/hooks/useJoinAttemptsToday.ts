import { useQuery } from '@tanstack/react-query'
import { getTodayJoinAttempts, type JoinAttemptLogResponse } from '../services/api'

export function useJoinAttemptsToday() {
  return useQuery<JoinAttemptLogResponse[]>({
    queryKey: ['joinAttemptsToday'],
    queryFn: getTodayJoinAttempts,
    refetchInterval: 60_000,
  })
}
