import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from '../api'
import type { AccessoryOption, AccessoryOptionWrite } from '../types'
import { PlusIcon, SaveIcon } from './Icons'
import { ConfirmDialog } from './ConfirmDialog'
import './Accessories.css'

const blank = (): AccessoryOptionWrite => ({ name: '', model: '', specification: '' })
const message = (reason: unknown) => reason instanceof Error ? reason.message : '配件目录操作失败'
const ordered = (rows: AccessoryOption[]) => [...rows].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN') || a.model.localeCompare(b.model, 'zh-CN') || a.id - b.id)

export function AccessoryOptions({ revision, onNotify }: { revision: number; onNotify: (message: string, kind?: 'ok' | 'error') => void }) {
  const [rows, setRows] = useState<AccessoryOption[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('')
  const [draft, setDraft] = useState(blank)
  const [editing, setEditing] = useState<AccessoryOption | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState<AccessoryOption | null>(null)
  const request = useRef(0)
  const mutation = useRef(false)

  const refresh = async (force = false) => {
    if (mutation.current && !force) return
    const token = ++request.current; setLoading(true); setError('')
    try { const result = await api.accessoryOptions(); if (request.current === token) setRows(ordered(result)) }
    catch (reason) { if (request.current === token) { setRows([]); setError(message(reason)) } }
    finally { if (request.current === token) setLoading(false) }
  }
  useEffect(() => { void refresh(); return () => { request.current++ } }, [revision])
  const reset = () => { setDraft(blank()); setEditing(null) }
  const save = async (operation: () => Promise<AccessoryOption>, success: string, resetForm = false) => {
    if (mutation.current) return
    mutation.current = true; setBusy(true); request.current++; setLoading(false)
    try {
      const saved = await operation()
      setRows(current => ordered([...current.filter(row => row.id !== saved.id), saved]))
      if (resetForm) reset()
      else if (editing?.id === saved.id) setEditing(saved)
      setConfirm(null); onNotify(success)
    } catch (reason) {
      setConfirm(null); onNotify(message(reason), 'error')
      if (reason instanceof ApiError && [401, 403].includes(reason.status)) { setRows([]); setError(message(reason)) }
      else if (reason instanceof ApiError && reason.status === 409) {
        // Keep both draft and reviewed version; never replay against an unseen newer version.
        await refresh(true)
      }
    } finally { mutation.current = false; setBusy(false) }
  }
  const term = query.trim().toLocaleLowerCase()
  const visible = rows.filter(row => [row.name, row.model, row.specification].some(value => value.toLocaleLowerCase().includes(term))
    && (!filter || row.disabled === (filter === 'disabled')))

  return <article className="foundation-card accessory-catalogue" aria-label="随附配件管理">
    <form className="accessory-catalogue-form" aria-label={editing ? '编辑配件目录' : '新增配件目录'} onSubmit={event => {
      event.preventDefault()
      if (loading || error || !draft.name.trim()) return
      const payload = { name: draft.name.trim(), model: draft.model.trim(), specification: draft.specification.trim() }
      void save(() => editing ? api.updateAccessoryOption(editing.id, editing.version, payload) : api.createAccessoryOption(payload), editing ? '配件目录已保存' : '配件已新增', true)
    }}>
      <label><span>配件名称 *</span><input aria-label="配件名称" required maxLength={120} placeholder="例如：鼠标" value={draft.name} disabled={busy} onChange={event => setDraft(current => ({ ...current, name: event.target.value }))} /></label>
      <label><span>型号</span><input aria-label="配件型号" maxLength={160} placeholder="例如：罗技 M185" value={draft.model} disabled={busy} onChange={event => setDraft(current => ({ ...current, model: event.target.value }))} /></label>
      <label><span>规格</span><input aria-label="配件规格" maxLength={300} placeholder="例如：无线 / USB" value={draft.specification} disabled={busy} onChange={event => setDraft(current => ({ ...current, specification: event.target.value }))} /></label>
      <div className="accessory-catalogue-actions"><button type="submit" className="button primary" disabled={busy || loading || !!error || !draft.name.trim()}>{editing ? <SaveIcon /> : <PlusIcon />}{busy ? '保存中…' : editing ? '保存修改' : '新增配件'}</button>{editing && <button type="button" className="button ghost" disabled={busy} onClick={reset}>取消编辑</button>}</div>
    </form>
    <div className="accessory-catalogue-filters"><input type="search" aria-label="搜索配件目录" placeholder="搜索名称、型号或规格" value={query} onChange={event => setQuery(event.target.value)} /><select aria-label="配件目录状态" value={filter} onChange={event => setFilter(event.target.value)}><option value="">全部状态</option><option value="enabled">启用</option><option value="disabled">停用</option></select></div>
    {error ? <p role="alert">{error}，请刷新重试。</p> : loading ? <p role="status" className="foundation-model-empty">正在读取配件…</p> : <>
      <ul className="accessory-catalogue-list">{visible.map(row => <li key={row.id}>
        <div className="accessory-identity"><strong>{row.name}</strong>{row.model && <span>{row.model}</span>}{row.specification && <small>{row.specification}</small>}</div>
        <span className={`foundation-model-state ${row.disabled ? 'disabled' : 'enabled'}`}>{row.disabled ? '停用' : '启用'}</span>
        <div className="accessory-catalogue-actions"><button type="button" className="button ghost" disabled={busy} onClick={() => { setEditing(row); setDraft({ name: row.name, model: row.model, specification: row.specification }) }}>编辑</button><button type="button" className="button ghost" disabled={busy} onClick={() => row.disabled ? void save(() => api.setAccessoryAvailability(row.id, row.version, false), '配件已启用') : setConfirm(row)}>{row.disabled ? '启用' : '停用'}</button></div>
      </li>)}</ul>{!visible.length && <p className="foundation-model-empty">暂无匹配配件</p>}
      <div className="foundation-model-total">共 {visible.length} 项配件</div>
    </>}
    {confirm && <ConfirmDialog title="停用随附配件" description={`停用“${confirm.name}${confirm.model ? ` · ${confirm.model}` : ''}”后不再供新选择，资产中已保存的配件记录保持不变。`} confirmLabel="确认停用" busy={busy} onCancel={() => !busy && setConfirm(null)} onConfirm={() => void save(() => api.setAccessoryAvailability(confirm.id, confirm.version, true), '配件已停用')} />}
  </article>
}
