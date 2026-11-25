import { preparationLabels } from '../preparation'
import type { PreparationOverview } from '../preparation'

export function PreparationBadge({ value }: { value: PreparationOverview }) {
  return <span className={`preparation-badge preparation-${value.state.toLowerCase()}`} title={value.reason}>{preparationLabels[value.state]}</span>
}
