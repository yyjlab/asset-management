import type { AccessPayload, Assignee, DashboardOverview, Asset, AssetPayload, AssetProfile, AuditFilters, AuditLogPage, AuthUser, AvatarResponse, CreateUserPayload, CsvImportPreview, CsvImportResult, LookupType, LookupValue, ManagedUser, PublicAsset, QrSetting, Summary, UpdateUserPayload } from './types'
import type { HandoverCreate, HandoverDetail, HandoverEmployee, HandoverPageData } from './types'
import type { RegistrationSubmit, RegistrationDetail, RegistrationPageData, TaskNotificationStatus } from './types'
import type { OperationCreate, OperationAction, OperationDetail, OperationPageData, InventoryDetail, InventoryPageData, InventoryAction, Consumable, ConsumablePageData, ConsumableMovementPage } from './types'

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message)
  }
}

let csrf: { headerName: string; token: string } | null = null

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...options,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...options?.headers },
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiError(body?.message || `请求失败（${response.status}）`, response.status)
  }
  if (response.status === 204) return undefined as T
  return response.json()
}

async function csrfToken() {
  if (!csrf) csrf = await request<{ headerName: string; token: string }>('/api/auth/csrf')
  return csrf
}

async function mutate<T>(path: string, options: RequestInit): Promise<T> {
  const token = await csrfToken()
  try {
    return await request<T>(path, { ...options, headers: { [token.headerName]: token.token, ...options.headers } })
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) csrf = null
    throw error
  }
}

async function mutateForm<T>(path: string, form: FormData, method: 'POST' | 'PUT' = 'POST'): Promise<T> {
  const token = await csrfToken()
  const response = await fetch(path, {
    method, body: form, credentials: 'include', headers: { [token.headerName]: token.token },
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiError(body?.message || `请求失败（${response.status}）`, response.status)
  }
  return response.json()
}

async function download(path: string, fallbackFilename: string) {
  const response = await fetch(path, { credentials: 'include' })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiError(body?.message || `下载失败（${response.status}）`, response.status)
  }
  const blob = await response.blob()
  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = fallbackFilename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(objectUrl)
}

function registrationForm(payload: RegistrationSubmit, photos: File[]) {
  const form = new FormData()
  form.append('data', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
  photos.forEach(photo => form.append('photos', photo))
  return form
}

export const api = {
  listOperations: (page = 0, kind = '', mine = true) => request<OperationPageData>(`/api/operations?${new URLSearchParams({ page: String(page), kind, mine: String(mine) })}`),
  getOperation: (id: number) => request<OperationDetail>(`/api/operations/${id}`),
  createOperation: (payload: OperationCreate) => mutate<OperationDetail>('/api/operations', { method: 'POST', body: JSON.stringify(payload) }),
  operationAction: (id: number, payload: OperationAction) => mutate<OperationDetail>(`/api/operations/${id}/actions`, { method: 'POST', body: JSON.stringify(payload) }),
  listInventories: (page = 0) => request<InventoryPageData>(`/api/inventories?page=${page}`),
  getInventory: (id: number) => request<InventoryDetail>(`/api/inventories/${id}`),
  createInventory: (payload: { requestKey: string; title: string; department: string; dueDate: string }) => mutate<InventoryDetail>('/api/inventories', { method: 'POST', body: JSON.stringify(payload) }),
  inventoryAction: (id: number, payload: InventoryAction) => mutate<InventoryDetail>(`/api/inventories/${id}/actions`, { method: 'POST', body: JSON.stringify(payload) }),
  listConsumables: (page = 0, query = '', lowStock = false) => request<ConsumablePageData>(`/api/consumables?${new URLSearchParams({ page: String(page), query, lowStock: String(lowStock) })}`),
  getConsumable: (id: number) => request<Consumable>(`/api/consumables/${id}`),
  createConsumable: (payload: { requestKey: string; name: string; unit: string; lowStockThreshold: number; notes: string }) => mutate<Consumable>('/api/consumables', { method: 'POST', body: JSON.stringify(payload) }),
  updateConsumable: (id: number, payload: { requestKey: string; version: number; name: string; unit: string; lowStockThreshold: number; notes: string }) => mutate<Consumable>(`/api/consumables/${id}/settings`, { method: 'POST', body: JSON.stringify(payload) }),
  moveConsumable: (id: number, payload: { requestKey: string; version: number; kind: 'IN' | 'OUT'; quantity: number; recipientUsername: string; note: string }) => mutate<{ item: Consumable }>(`/api/consumables/${id}/movements`, { method: 'POST', body: JSON.stringify(payload) }),
  listConsumableMovements: (page = 0, consumableId: number | null = null, mine = true) => request<ConsumableMovementPage>(`/api/consumables/movements?${new URLSearchParams({ page: String(page), ...(consumableId ? { consumableId: String(consumableId) } : {}), mine: String(mine) })}`),
  listRegistrations: (page = 0, state = '', review = false) => request<RegistrationPageData>(`/api/registrations?${new URLSearchParams({ page: String(page), state, review: String(review) })}`),
  getRegistration: (id: number) => request<RegistrationDetail>(`/api/registrations/${id}`),
  submitRegistration: (payload: RegistrationSubmit, photos: File[]) => mutateForm<RegistrationDetail>('/api/registrations', registrationForm(payload, photos)),
  supplementRegistration: (id: number, payload: RegistrationSubmit, photos: File[]) => mutateForm<RegistrationDetail>(`/api/registrations/${id}/supplement`, registrationForm(payload, photos)),
  withdrawRegistration: (id: number, version: number, note: string) => mutate<RegistrationDetail>(`/api/registrations/${id}/withdraw`, { method: 'POST', body: JSON.stringify({ version, note }) }),
  reviewRegistration: (id: number, version: number, decision: 'NEEDS_INFO' | 'REJECTED', note: string) => mutate<RegistrationDetail>(`/api/registrations/${id}/review`, { method: 'POST', body: JSON.stringify({ version, decision, note }) }),
  approveNewRegistration: (id: number, version: number, companyId: number, categoryId: number, locationId: number, note: string, confirmed: boolean) => mutate<RegistrationDetail>(`/api/registrations/${id}/approve-create`, { method: 'POST', body: JSON.stringify({ version, companyId, categoryId, locationId, note, confirmed }) }),
  approveExistingRegistration: (id: number, version: number, assetId: number, assetVersion: number, note: string, confirmed: boolean) => mutate<RegistrationDetail>(`/api/registrations/${id}/approve-existing`, { method: 'POST', body: JSON.stringify({ version, assetId, assetVersion, note, confirmed }) }),
  taskNotifications: () => request<TaskNotificationStatus>('/api/task-notifications'),
  retryTaskNotification: (id: number) => mutate<void>(`/api/task-notifications/${id}/retry`, { method: 'POST' }),
  listHandovers: (page = 0, kind = '', state = '', mine = false) => request<HandoverPageData>(`/api/handovers?${new URLSearchParams({ page: String(page), kind, state, ...(mine ? { mine: 'true' } : {}) })}`),
  getHandover: (id: number) => request<HandoverDetail>(`/api/handovers/${id}`),
  handoverEmployees: () => request<HandoverEmployee[]>('/api/handovers/employees'),
  createHandover: (payload: HandoverCreate) => mutate<HandoverDetail>('/api/handovers', { method: 'POST', body: JSON.stringify(payload) }),
  checkHandoverItem: (id: number, index: number, version: number, checked: boolean) => mutate<HandoverDetail>(`/api/handovers/${id}/items/${index}/check`, { method: 'POST', body: JSON.stringify({ version, checked }) }),
  confirmHandoverItem: (id: number, index: number, version: number, offline: boolean, note = '') => mutate<HandoverDetail>(`/api/handovers/${id}/items/${index}/confirm`, { method: 'POST', body: JSON.stringify({ version, offline, note }) }),
  completeHandover: (id: number, version: number) => mutate<HandoverDetail>(`/api/handovers/${id}/complete`, { method: 'POST', body: JSON.stringify({ version }) }),
  cancelHandover: (id: number, version: number, note: string) => mutate<HandoverDetail>(`/api/handovers/${id}/cancel`, { method: 'POST', body: JSON.stringify({ version, note }) }),
  linkHandoverEmployee: (id: number, version: number, employeeUserId: number) => mutate<HandoverDetail>(`/api/handovers/${id}/employee`, { method: 'POST', body: JSON.stringify({ version, employeeUserId }) }),
  dashboard: () => request<DashboardOverview>('/api/dashboard'),
  assignees: () => request<Assignee[]>('/api/assignees'),
  updateUserAccess: (id: number, payload: AccessPayload) => mutate<ManagedUser>(`/api/users/${id}/access`, { method: 'PUT', body: JSON.stringify(payload) }),
  getAsset: (id: number) => request<Asset>(`/api/assets/${id}`),
  getPublicAsset: (qrToken: string) => request<PublicAsset>(`/api/public/assets/${encodeURIComponent(qrToken)}`),
  me: () => request<AuthUser>('/api/auth/me'),
  dingtalkStatus: () => request<{ enabled: boolean; bound: boolean }>('/api/v1/auth/dingtalk/status'),
  bindDingtalk: () => mutate<{ authorizationUrl: string }>('/api/v1/auth/dingtalk/bind', { method: 'POST' }),
  login: (username: string, password: string) =>
    mutate<AuthUser>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: async () => {
    await mutate<void>('/api/auth/logout', { method: 'POST' })
    csrf = null
  },
  changeMyPassword: (currentPassword: string, newPassword: string) =>
    mutate<void>('/api/auth/password', { method: 'PUT', body: JSON.stringify({ currentPassword, newPassword }) }),
  uploadMyAvatar: (file: File) => {
    const form = new FormData(); form.append('file', file)
    return mutateForm<AvatarResponse>('/api/auth/avatar', form, 'PUT')
  },
  deleteMyAvatar: () => mutate<AvatarResponse>('/api/auth/avatar', { method: 'DELETE' }),
  deleteUserAvatar: (id: number) => mutate<AvatarResponse>(`/api/users/${id}/avatar`, { method: 'DELETE' }),
  listAssets: (search = '') => request<Asset[]>(`/api/assets?search=${encodeURIComponent(search)}`),
  summary: () => request<Summary>('/api/assets/summary'),
  createAsset: (payload: AssetPayload, cloneSourceId?: number) =>
    mutate<Asset>(`/api/assets${cloneSourceId ? `?cloneSourceId=${cloneSourceId}` : ''}`, { method: 'POST', body: JSON.stringify(payload) }),
  updateAsset: (id: number, payload: AssetPayload) =>
    mutate<Asset>(`/api/assets/${id}`, { method: 'PUT', headers: { 'If-Match': String(payload.version) }, body: JSON.stringify({ ...payload, version: undefined }) }),
  returnAsset: (id: number) => mutate<Asset>(`/api/assets/${id}/return`, { method: 'POST' }),
  returnAssetByQrToken: (qrToken: string) =>
    mutate<Asset>(`/api/assets/qr/${encodeURIComponent(qrToken)}/return`, { method: 'POST' }),
  deleteAsset: (id: number) => mutate<void>(`/api/assets/${id}`, { method: 'DELETE' }),
  downloadAssetCsv: () => download('/api/assets/export.csv', 'asset-data.csv'),
  previewAssetCsv: (file: File) => {
    const form = new FormData(); form.append('file', file)
    return mutateForm<CsvImportPreview>('/api/assets/import/preview', form)
  },
  importAssetCsv: (file: File) => {
    const form = new FormData(); form.append('file', file)
    return mutateForm<CsvImportResult>('/api/assets/import/commit', form)
  },
  listLookups: () => request<LookupValue[]>('/api/lookups'),
  createLookup: (type: LookupType, name: string, assetProfile?: AssetProfile) =>
    mutate<LookupValue>('/api/lookups', { method: 'POST', body: JSON.stringify({ type, name, assetProfile }) }),
  deleteLookup: (id: number) => mutate<void>(`/api/lookups/${id}`, { method: 'DELETE' }),
  listUsers: () => request<ManagedUser[]>('/api/users'),
  createUser: (payload: CreateUserPayload) =>
    mutate<ManagedUser>('/api/users', { method: 'POST', body: JSON.stringify(payload) }),
  updateUser: (id: number, payload: UpdateUserPayload) =>
    mutate<ManagedUser>(`/api/users/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteUser: (id: number) => mutate<void>(`/api/users/${id}`, { method: 'DELETE' }),
  getQrSetting: () => request<QrSetting>('/api/settings/qr'),
  updateQrSetting: (qrBaseUrl: string) =>
    mutate<QrSetting>('/api/settings/qr', { method: 'PUT', body: JSON.stringify({ qrBaseUrl }) }),
  listAuditLogs: (filters: AuditFilters, page = 0, size = 50) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    Object.entries(filters).forEach(([key, value]) => value && params.set(key, value))
    return request<AuditLogPage>(`/api/audit-logs?${params}`)
  },
  downloadAuditLogs: (filters: AuditFilters) => {
    const params = new URLSearchParams()
    Object.entries(filters).forEach(([key, value]) => value && params.set(key, value))
    return download(`/api/audit-logs/export.csv?${params}`, 'audit-logs.csv')
  },
}
