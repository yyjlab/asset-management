import type { Asset, AssetBundlePayload, AssetPayload } from './types'

export interface DisplayDraft { id: string; inheritCommon: boolean; data: AssetPayload }

export function newBundleKey() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function buildAssetBundle(computer: AssetPayload, drafts: DisplayDraft[], assets: Asset[], key: string, existing?: Asset | null, clone = false): AssetBundlePayload {
  if (drafts.length > 8 || computer.boundDisplayIds.length + drafts.length > 20) throw new Error('每套最多20台显示器，其中新建最多8台')
  const clean = (payload: AssetPayload) => {
    const { version: _version, ...data } = payload
    // imageUrls is authoritative. Do not send the first Base64 photo twice.
    return { ...data, imageUrl: data.imageUrls.length ? '' : data.imageUrl }
  }
  const payload: AssetBundlePayload = {
    requestKey: key,
    ...(existing && !clone ? { computerId: existing.id, computerVersion: computer.version } : {}),
    ...(existing && clone ? { cloneSourceId: existing.id } : {}),
    computer: clean(computer),
    displays: drafts.map(draft => clean({ ...draft.data,
      ...(draft.inheritCommon ? { companyId: computer.companyId, ownershipDepartment: computer.ownershipDepartment,
        locationId: computer.locationId, statusId: computer.statusId, checkedOut: computer.checkedOut,
        assignedTo: computer.assignedTo, assignedUserId: computer.assignedUserId } : {}),
      assetTag: '', domainJoinStatus: null, boundDisplayIds: [], boundComputerId: null,
    })),
    existingDisplays: computer.boundDisplayIds.map(id => {
      const display = assets.find(asset => asset.id === id)
      if (!display || !Number.isSafeInteger(display.version)) throw new Error('已有显示器信息不完整，请刷新后重新选择')
      return { id, version: display.version }
    }),
  }
  if (new TextEncoder().encode(JSON.stringify(payload)).byteLength > 10 * 1024 * 1024)
    throw new Error('整套照片总量过大，请减少照片后再保存（单次提交最多10MB）')
  return payload
}
