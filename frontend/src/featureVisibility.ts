import type { AppSection } from './components/Sidebar'

// Release visibility only; this does not grant permissions or disable backend jobs/APIs.
// Restore this group after acceptance, without deleting its code or historical records.
export const featureVisibility = { businessWorkflows: false }

const coreSections = new Set<AppSection>(['dashboard', 'assets', 'users', 'logs', 'foundation', 'settings', 'transfer'])
const workflowSections = new Set<AppSection>([
  'workbench', 'selfService', 'handover', 'operations', 'inventory', 'repair', 'consumables',
  'procurement', 'kits', 'support', 'reports', 'registrations',
])

export const isSectionVisible = (section: AppSection) => coreSections.has(section)
  || featureVisibility.businessWorkflows && workflowSections.has(section)

export const visibleSection = (section: AppSection): AppSection => isSectionVisible(section) ? section : 'dashboard'
