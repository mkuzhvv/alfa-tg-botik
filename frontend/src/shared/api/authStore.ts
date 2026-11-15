import { create } from 'zustand'
import {
  apiClient,
  apiError,
  ApiError,
  result,
  setAccessToken,
  setSessionRenewal,
} from './client'
import { useAppStore } from './appStore'
import { getTelegramInitData, initTelegramApp } from '../lib/telegram'

interface AuthState {
  status: 'loading' | 'ready' | 'error'
  error: ApiError | null
  initialize: () => Promise<void>
  logout: () => Promise<void>
}

let initialization: Promise<void> | undefined

async function login() {
  const initData = getTelegramInitData()
  if (!initData) throw new ApiError('OPEN_IN_TELEGRAM', 401)
  try {
    const session = result(
      await apiClient.POST('/auth/telegram', { body: { initData } }),
    )
    setAccessToken(session.accessToken)
    useAppStore.setState({ user: session.user })
  } catch (failure) {
    const error = apiError(failure)
    setAccessToken(null)
    useAppStore.getState().reset()
    useAuthStore.setState({ status: 'error', error })
    throw error
  }
}

export const useAuthStore = create<AuthState>((set) => ({
  status: 'loading',
  error: null,
  initialize: () => {
    if (initialization) return initialization
    initialization = (async () => {
      set({ status: 'loading', error: null })
      initTelegramApp()
      try {
        await login()
        await useAppStore.getState().fetchInitialData()
        set({ status: 'ready' })
      } catch (failure) {
        set({ status: 'error', error: apiError(failure) })
      }
    })().finally(() => {
      initialization = undefined
    })
    return initialization
  },
  logout: async () => {
    try {
      result(await apiClient.POST('/auth/logout'))
      setAccessToken(null)
      useAppStore.getState().reset()
      set({ status: 'error', error: new ApiError('OPEN_IN_TELEGRAM', 401) })
    } catch (failure) {
      useAppStore.setState({ error: apiError(failure) })
      throw failure
    }
  },
}))

setSessionRenewal(login)
