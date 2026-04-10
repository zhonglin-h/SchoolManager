import { useQuery } from '@tanstack/react-query'
import { getScheduledChecks } from '../services/api'

export function useScheduledChecks() {
  return useQuery({
    queryKey: ['scheduledChecks'],
    queryFn: getScheduledChecks,
    refetchInterval: 30_000,
  })
}
