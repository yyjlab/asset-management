export interface DeploymentCredential {
  id: string
  name: string
  createdAt: string
  expiresAt: string
  revokedAt: string | null
  active: boolean
}

export interface DeploymentStatus {
  enabled: boolean
  secureRequest: boolean
  allowHttp: boolean
  endpoint: string
  lastReceivedAt: string | null
}

export interface DeploymentEntry {
  externalRegistrationId: string
  assetTag: string
  assetId: number
  receivedAt: string
  credentialId: string
  declaredOperator: { id: string; displayName: string }
  result: string
  differences: { field: string; existing: string | null; incoming: string | null }[]
  warnings: string[]
}

export interface DeploymentHistory {
  items: DeploymentEntry[]
  page: number
  totalPages: number
  totalElements: number
}

export type DeploymentIntakeState = 'pending_review' | 'approved' | 'rejected'
export interface DeploymentIntake {
  source?: string
  assignedTo?: string | null
  ownershipDepartment?: string | null
  domainJoinStatus?: 'JOINED' | 'NOT_JOINED' | 'UNKNOWN' | null
  externalRegistrationId: string
  receivedAt: string
  capturedAt: string
  declaredOperator: { id: string; displayName: string }
  credentialId: string
  state: DeploymentIntakeState
  revision: number
  name?: string | null
  model: string | null
  cpu: string | null
  memory: string | null
  storage: string | null
  graphicsCard: string | null
  manufacturerSerialNumber: string | null
  categoryId: number | null
  asset: { id: number; assetTag: string; version: number } | null
  reviewedAt: string | null
  reviewedBy: string | null
  reviewNote: string | null
}
export interface DeploymentIntakePage {
  items: DeploymentIntake[]
  page: number
  totalPages: number
  totalElements: number
}
export interface DeploymentApproval {
  assignedTo?: string | null
  ownershipDepartment?: string | null
  domainJoinStatus?: 'JOINED' | 'NOT_JOINED' | 'UNKNOWN' | null
  revision: number
  categoryId: number
  name: string
  model: string
  cpu: string | null
  memory: string | null
  storage: string | null
  graphicsCard: string | null
  manufacturerSerialNumber: string | null
  note: string | null
}
