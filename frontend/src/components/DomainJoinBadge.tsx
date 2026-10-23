import type { Asset } from '../types'
import { domainStatusOf, domainStatusOptions } from '../domainJoin'

export function DomainJoinBadge({ asset }: { asset: Asset }) {
  const status = domainStatusOf(asset)
  const option = domainStatusOptions.find(value => value.code === status)
  return option ? <span className={`domain-status-badge domain-${option.code.toLowerCase()}`} title={option.description}>{option.label}</span>
    : <span className="domain-status-na" aria-label="入域状态不适用">—</span>
}
