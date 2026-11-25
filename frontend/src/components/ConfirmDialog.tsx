import { useEffect } from 'react'
import { TrashIcon } from './Icons'

interface Props {
  title: string
  description: string
  confirmLabel?: string
  busy?: boolean
  destructive?: boolean
  onCancel: () => void
  onConfirm: () => void
}

export function ConfirmDialog({ title, description, confirmLabel = '确认删除', busy = false, destructive = true, onCancel, onConfirm }: Props) {
  useEffect(() => {
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onCancel()
    }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [busy, onCancel])

  return <div className="modal-backdrop confirm-backdrop" onMouseDown={(event) => event.target === event.currentTarget && !busy && onCancel()}>
    <section className="confirm-card" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" aria-describedby="confirm-description">
      {destructive && <div className="confirm-icon"><TrashIcon /></div>}
      <h3 id="confirm-title">{title}</h3>
      <p id="confirm-description">{description}</p>
      <div className="confirm-actions">
        <button type="button" className="button confirm-cancel" disabled={busy} onClick={onCancel}>取消</button>
        <button type="button" className={`button ${destructive ? 'confirm-delete' : 'primary'}`} disabled={busy} onClick={onConfirm}>{destructive && <TrashIcon />}{busy ? destructive ? '删除中…' : '保存中…' : confirmLabel}</button>
      </div>
    </section>
  </div>
}
