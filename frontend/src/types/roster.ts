export interface RosterPerson { name: string; department: string; seat: string }
export interface RosterEntry extends RosterPerson {
  id: string
  state: 'registered' | 'missing' | 'pending' | 'confirm'
  reason: string
  computers: { id: number; assetTag: string; name: string }[]
  pendingCount: number
}
export interface RosterImportResult { added: number; skipped: number; duplicates: string[] }
