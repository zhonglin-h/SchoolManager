import { useQuery } from '@tanstack/react-query'
import { getNotifications } from '../services/api'

export function useNotifications() {
  return useQuery({
    queryKey: ['notifications'],
    queryFn: getNotifications,
    refetchInterval: 3 * 60 * 1000,
  })
}
