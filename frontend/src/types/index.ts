export type LookupType = 'COMPANY' | 'ASSET_NAME' | 'DEPARTMENT' | 'MODEL' | 'CPU' | 'GRAPHICS_CARD' | 'CATEGORY' | 'STATUS' | 'LOCATION'
export type AssetProfile = 'COMPUTER' | 'DISPLAY' | 'GENERAL'

export interface LookupValue {
  id: number
  type: LookupType
  typeLabel: string
  name: string
  assetProfile: AssetProfile | null
  parameterTemplate?: string[]
}

export interface Asset {
  id: number
  version: number
  qrToken: string
  assetTag: string
  name: string
  ownershipDepartment: string | null
  cpu: string | null
  memory: string | null
  storage: string | null
  graphicsCard: string | null
  manufacturerSerialNumber: string | null
  screenSize: string | null
  displayResolution: string | null
  displayInterface: string | null
  orderNumber: string | null
  company: LookupValue
  model: LookupValue
  category: LookupValue
  status: LookupValue
  location: LookupValue
  purchasePrice: number | null
  currentValue: number | null
  checkedOut: boolean
  assignedTo: string | null
  assignedUserId: number | null
  imageUrl: string | null
  imageUrls: string[]
  notes: string | null
  boundDisplays: AssetLink[]
  boundComputer: AssetLink | null
  relatedDevices: RelatedDevice[]
  accessories: AssetAccessory[]
  customParameters: CustomParameter[]
  createdAt: string
  updatedAt: string
}

export interface AssetPayload {
  version?: number
  assetTag: string
  name: string
  ownershipDepartment: string
  cpu: string
  memory: string
  storage: string
  graphicsCard: string
  manufacturerSerialNumber: string
  screenSize: string
  displayResolution: string
  displayInterface: string
  orderNumber: string
  companyId: number | null
  modelId: number | null
  modelName: string
  categoryId: number | null
  statusId: number | null
  locationId: number | null
  purchasePrice: number | null
  currentValue: number | null
  checkedOut: boolean
  assignedTo: string
  assignedUserId?: number | null
  imageUrl: string
  imageUrls: string[]
  notes: string
  boundDisplayIds: number[]
  boundComputerId: number | null
  relatedDevices: RelatedDevice[]
  accessories: AssetAccessory[]
  customParameters: CustomParameter[]
}

export interface AssetLink {
  id: number
  assetTag: string
  name: string
  model: string | null
  category: string | null
}

export interface RelatedDevice {
  name: string
  model: string
  serialNumber: string
  orderNumber: string
  specification: string
  quantity: number
}

export interface AssetAccessory {
  name: string
  specification: string
  quantity: number
}

export interface CustomParameter {
  name: string
  value: string
}

export interface PublicAsset {
  assetTag: string
  name: string
  manufacturerSerialNumber: string | null
  computerModel: string | null
  cpu: string | null
  memory: string | null
  storage: string | null
  graphicsCard: string | null
  screenSize: string | null
  displayResolution: string | null
  displayInterface: string | null
  orderNumber: string | null
  company: string | null
  ownershipDepartment: string | null
  category: string | null
  assetProfile: AssetProfile
  status: string | null
  location: string | null
  checkedOut: boolean
  assignedTo: string | null
  imageUrl: string | null
  imageUrls: string[]
  boundDisplays: PublicAssetLink[]
  boundComputer: PublicAssetLink | null
  relatedDevices: PublicRelatedDevice[]
  updatedAt: string
}

export interface PublicAssetLink {
  assetTag: string
  name: string
  model: string | null
  category: string | null
}

export interface PublicRelatedDevice {
  name: string
  model: string | null
  serialNumber: string | null
  specification: string | null
  quantity: number
}

export interface Summary {
  total: number
  checkedOut: number
  available: number
  maintenance: number
  scrapped: number
}

export type AssetFilter = 'all' | 'available' | 'checkedOut' | 'maintenance' | 'scrapped'

export type Permission = 'ASSET_VIEW' | 'ASSET_CREATE' | 'ASSET_EDIT' | 'ASSET_DELETE' | 'ASSET_RETURN' | 'ASSET_CHECKOUT' | 'ASSET_IMPORT' | 'ASSET_EXPORT'
export type DataScope = 'SELF' | 'DEPARTMENT' | 'ALL'
export type HandoverKind = 'ONBOARDING' | 'OFFBOARDING'
export type HandoverState = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
export interface HandoverSummary {
  id: number; version: number; kind: HandoverKind; state: HandoverState
  employeeUserId: number | null; employeeName: string; department: string; plannedDate: string | null
  totalItems: number; checkedItems: number; confirmedItems: number; createdAt: string
}
export interface HandoverItem {
  index: number; assetId: number | null; assetTag: string | null; name: string; model: string | null
  serialNumber: string | null; accessories: string | null; checked: boolean; checkedBy: string | null
  checkedAt: string | null; confirmationMethod: 'ONLINE' | 'OFFLINE' | null; confirmedBy: string | null
  confirmedAt: string | null; confirmationNote: string | null
}
export interface HandoverDetail extends Omit<HandoverSummary, 'totalItems' | 'checkedItems' | 'confirmedItems'> {
  approvalReference: string; notes: string; createdByName: string; items: HandoverItem[]
  events: { occurredAt: string; actorName: string; description: string }[]
}
export interface HandoverPageData { items: HandoverSummary[]; page: number; totalElements: number; totalPages: number; canManage: boolean }
export interface HandoverEmployee extends Assignee { enabled: boolean }
export type RegistrationState = 'SUBMITTED' | 'NEEDS_INFO' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
export interface RegistrationSummary {
  id: number; version: number; ownerId: number; ownerName: string; name: string; model: string
  serialNumber: string; state: RegistrationState; updatedAt: string; approvedAssetId: number | null; approvedAssetTag: string | null
}
export interface RegistrationDetail {
  request: RegistrationSummary; requestKey: string; department: string; location: string; cpu: string; memory: string; storage: string
  notes: string; reviewNote: string; reviewerName: string; photos: string[]; serialAlreadyInAssets: boolean
  events: { occurredAt: string; actorName: string; description: string }[]
}
export interface RegistrationPageData { items: RegistrationSummary[]; page: number; totalElements: number; totalPages: number; canReview: boolean }
export interface RegistrationSubmit {
  requestKey: string; version?: number; name: string; model: string; serialNumber: string; department: string; location: string
  cpu: string; memory: string; storage: string; notes: string
}
export interface TaskNotificationStatus {
  enabled: boolean; configured: boolean; pending: number; failed: number
  recent: { id: number; category: string; title: string; state: string; attempts: number; lastError: string | null; createdAt: string; sentAt: string | null }[]
}
export interface HandoverCreate {
  requestKey: string; kind: HandoverKind; employeeUserId: number | null; employeeName: string; department: string
  plannedDate: string | null; approvalReference: string; notes: string; assetIds: number[]
  includeAssignedAssets: boolean; allowLegacyAssets: boolean; manualItems: string[]
}
export interface AccessPayload { role?: UserRole; permissions: Permission[]; dataScope: DataScope; departments: string[]; enabled: boolean }
export interface Assignee { id: number; username: string; displayName: string }
export interface DashboardOverview {
  total: number; computerCount: number; displayCount: number; available: number; checkedOut: number; maintenance: number; scrapped: number
  configurationRate: number; completeComputers: number; missingImages: number; missingDepartments: number; unboundDisplays: number
  cpu: RankedItem[]; memory: RankedItem[]; storage: RankedItem[]; departments: RankedItem[]; locations: RankedItem[]
}
export interface RankedItem { label: string; count: number; percent: number }
export type UserRole = 'SUPER_ADMIN' | 'USER'

export interface AuthUser {
  displayName: string
  dataScope: DataScope
  departments: string[]
  id: number
  username: string
  role: UserRole
  permissions: Permission[]
  canManageUsers: boolean
  hasAvatar: boolean
  dingtalkLogin: boolean
}

export interface ManagedUser {
  protectedAccount: boolean
  displayName: string
  dingtalkBound: boolean
  dataScope: DataScope
  departments: string[]
  id: number
  username: string
  role: UserRole
  permissions: Permission[]
  enabled: boolean
  createdAt: string
  updatedAt: string
  hasAvatar: boolean
}

export interface AvatarResponse { hasAvatar: boolean }

export interface CreateUserPayload {
  username: string
  password: string
}

export interface UpdateUserPayload {
  password: string
}

export interface QrSetting {
  qrBaseUrl: string
  configured: boolean
  source: 'DATABASE' | 'ENVIRONMENT' | 'NONE'
  updatedAt: string | null
}

export type AuditResult = 'SUCCESS' | 'FAILURE'

export interface AuditLogItem {
  id: number
  occurredAt: string
  actorUserId: number | null
  actorUsername: string
  actorRole: UserRole | null
  action: string
  module: string
  actionLabel: string
  result: AuditResult
  targetType: string
  targetId: number | null
  targetLabel: string | null
  summary: string
  changesJson: string
  ipAddress: string | null
  userAgent: string | null
}

export interface AuditLogPage {
  items: AuditLogItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AuditFilters {
  username: string
  module: string
  action: string
  result: string
  keyword: string
  from: string
  to: string
}

export interface CsvRowMessage { row: number; field: string; message: string }
export interface CsvPreviewRow { row: number; assetTag: string; name: string; status: string; valid: boolean }
export interface CsvImportPreview {
  totalRows: number
  validRows: number
  errors: CsvRowMessage[]
  warnings: CsvRowMessage[]
  sample: CsvPreviewRow[]
  canImport: boolean
}
export interface CsvImportResult { importedCount: number; createdLookupCount: number; createdLookups: string[] }

export const lookupLabels: Record<LookupType, string> = {
  COMPANY: '公司',
  ASSET_NAME: '资产名称',
  DEPARTMENT: '归属部门',
  MODEL: '设备型号',
  CPU: 'CPU',
  GRAPHICS_CARD: '显卡',
  CATEGORY: '分类',
  STATUS: '状态',
  LOCATION: '位置',
}
