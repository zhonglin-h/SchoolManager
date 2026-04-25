import { useQuery } from '@tanstack/react-query'
import { getScheduledChecks, type ScheduledChecksResponse } from '../services/api'

export function useScheduledChecks() {
  return useQuery<ScheduledChecksResponse>({
    queryKey: ['scheduledChecks'],
    queryFn: getScheduledChecks,
    refetchInterval: 60_000,
  })
}
