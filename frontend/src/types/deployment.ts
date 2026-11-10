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
