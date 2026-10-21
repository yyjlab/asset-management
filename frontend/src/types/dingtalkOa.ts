export interface OaStatus { enabled: boolean; identityReady: boolean; streamEnabled: boolean }
export interface OaCandidate { id: number; version: number; assetTag: string; name: string }
export interface OaCreate { requestKey: string; kind: 'ISSUE' | 'RETURN'; assetId: number; assetVersion: number; description: string; confirmed: boolean }
export interface OaItem { id: number; version: number; requestKey: string; kind: 'ISSUE' | 'RETURN'; state: string; approvalState: string; ownerId: number; ownerName: string; assetTag: string; instanceId: string | null; error: string | null; description: string; checkedAt: string | null; updatedAt: string }
export interface OaPage { items: OaItem[]; page: number; totalElements: number; totalPages: number; canManage: boolean }
