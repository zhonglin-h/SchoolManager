import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getTeachers,
  createTeacher,
  updateTeacher,
  deleteTeacher,
  enableTeacher,
  type TeacherRequest,
} from '../services/api'

export function useTeachers() {
  return useQuery({
    queryKey: ['teachers', 'list', false],
    queryFn: () => getTeachers(false),
  })
}

export function useTeachersList(includeInactive: boolean) {
  return useQuery({
    queryKey: ['teachers', 'list', includeInactive],
    queryFn: () => getTeachers(includeInactive),
  })
}

export function useCreateTeacher() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: TeacherRequest) => createTeacher(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teachers'] })
    },
  })
}

export function useUpdateTeacher() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: TeacherRequest }) =>
      updateTeacher(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teachers'] })
    },
  })
}

export function useDeleteTeacher() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteTeacher(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teachers'] })
    },
  })
}

export function useEnableTeacher() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => enableTeacher(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teachers'] })
    },
  })
}
