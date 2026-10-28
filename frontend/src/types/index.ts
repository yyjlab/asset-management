export type LookupType = 'COMPANY' | 'ASSET_NAME' | 'DEPARTMENT' | 'MODEL' | 'CPU' | 'GRAPHICS_CARD' | 'CATEGORY' | 'STATUS' | 'LOCATION'
export type AssetProfile = 'COMPUTER' | 'DISPLAY' | 'GENERAL'
export type DomainJoinStatus = 'JOINED' | 'NOT_JOINED' | 'UNKNOWN'
export interface DomainStatusOption {
  code: DomainJoinStatus
  label: string
  description: string
  sortOrder: number
  version: number | null
}

export interface LookupValue {
  id: number
  type: LookupType
  typeLabel: string
  name: string
  assetProfile: AssetProfile | null
  parameterTemplate?: string[]
}

export interface ModelOption { id: number; name: string; disabled: boolean; version: number; assetCount: number }
export type ManagedLookupType = 'MODEL' | 'CPU' | 'ASSET_NAME'
export interface AccessoryOption { id: number; name: string; model: string; specification: string; disabled: boolean; version: number }
export type AccessoryOptionWrite = Pick<AccessoryOption, 'name' | 'model' | 'specification'>

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
  domainJoinStatus?: DomainJoinStatus | null
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
  domainJoinStatus?: DomainJoinStatus | null
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

export interface AssetBundlePayload {
  requestKey: string
  computerId?: number
  computerVersion?: number
  cloneSourceId?: number
  computer: AssetPayload
  displays: AssetPayload[]
  existingDisplays: { id: number; version: number }[]
}
export interface AssetBundleResult { computer: Asset; displays: Asset[]; replayed: boolean }
export type AssetEntryDefaults = Pick<AssetPayload, 'companyId' | 'ownershipDepartment' | 'locationId' | 'categoryId'>

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
  version: number
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
  ledgerEnabled?: boolean; ledgerAppliedAt?: string | null
}
export interface HandoverItem {
  index: number; assetId: number | null; assetTag: string | null; name: string; model: string | null
  serialNumber: string | null; accessories: string | null; checked: boolean; checkedBy: string | null
  checkedAt: string | null; confirmationMethod: 'ONLINE' | 'OFFLINE' | null; confirmedBy: string | null
  confirmedAt: string | null; confirmationNote: string | null
  assetVersion?: number | null; operationId?: number | null
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
export type OperationKind = 'ISSUE' | 'RETURN' | 'TRANSFER' | 'CORRECTION' | 'REPAIR'
export type OperationState = 'PENDING' | 'WAITING_CONFIRM' | 'PROCESSING' | 'CONFIRMED' | 'COMPLETED' | 'REJECTED' | 'CANCELLED' | 'OA_PENDING' | 'OA_FAILED' | 'HANDOVER_PENDING'
export interface OperationSummary { id: number; version: number; kind: OperationKind; state: OperationState; ownerId: number; ownerName: string; targetId: number | null; targetName: string; assetId: number | null; assetTag: string | null; title: string; updatedAt: string; handoverId?: number | null }
export interface WorkflowEvent { occurredAt: string; actor: string; description: string }
export interface OperationDetail { task: OperationSummary; assetVersion: number | null; description: string; proposed: Record<string, string>; outcome: 'FIXED' | 'SCRAP' | null; repairCost: number | null; events: WorkflowEvent[] }
export interface OperationPageData { items: OperationSummary[]; page: number; totalElements: number; totalPages: number; canManage: boolean }
export interface OperationCreate { requestKey: string; kind: OperationKind; title: string; description: string; assetId: number | null; targetUsername: string; proposed: Record<string, string> }
export interface OperationAction { version: number; action: 'APPROVE' | 'PROGRESS' | 'RESOLVE' | 'CONFIRM' | 'COMPLETE' | 'REJECT' | 'CANCEL'; note?: string; assetId?: number; assetVersion?: number; outcome?: 'FIXED' | 'SCRAP'; repairCost?: number; confirmed?: boolean }
export interface InventorySummary { id: number; version: number; title: string; department: string; dueDate: string; state: 'OPEN' | 'COMPLETED' | 'CANCELLED'; total: number; confirmed: number; resolved: number }
export interface InventoryItem { index: number; assetId: number; assetVersion: number; assetTag: string; name: string; ownerId: number | null; ownerName: string | null; result: 'PRESENT' | 'MISSING' | 'MISMATCH' | null; employeeNote: string | null; resolved: boolean; resolution: string | null; ledgerChanged: boolean }
export interface InventoryDetail { batch: InventorySummary; items: InventoryItem[]; events: WorkflowEvent[]; canManage: boolean }
export interface InventoryPageData { items: InventorySummary[]; page: number; totalElements: number; totalPages: number; canManage: boolean }
export interface InventoryAction { version: number; action: 'CONFIRM' | 'RESOLVE' | 'CLOSE' | 'CANCEL'; index?: number; result?: 'PRESENT' | 'MISSING' | 'MISMATCH'; note: string }
export interface Consumable { id: number; version: number; name: string; unit: string; quantity: number; lowStockThreshold: number; lowStock: boolean; notes: string; updatedAt: string }
export interface ConsumablePageData { items: Consumable[]; page: number; totalElements: number; totalPages: number; canManage: boolean }
export interface ConsumableMovement { id: number; consumableId: number; consumableName: string; unit: string; kind: 'IN' | 'OUT'; quantity: number; balanceAfter: number; recipientId: number | null; recipientName: string | null; actorName: string; note: string; createdAt: string }
export interface ConsumableMovementPage { items: ConsumableMovement[]; page: number; totalElements: number; totalPages: number; canManage: boolean }
export interface HandoverCreate {
  requestKey: string; kind: HandoverKind; employeeUserId: number | null; employeeName: string; department: string
  plannedDate: string | null; approvalReference: string; notes: string; assetIds: number[]
  includeAssignedAssets: boolean; allowLegacyAssets: boolean; manualItems: string[]
  ledgerEnabled?: boolean; assetVersions?: Record<string, number>
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

export interface DingTalkQrAuthorization {
  authorizationUrl: string
  redirectUri: string
  state: string
  expiresAt: number
}

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
