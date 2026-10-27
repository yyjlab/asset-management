import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from '../api'
import type { ModelOption } from '../types'
import { PlusIcon } from './Icons'
import { ConfirmDialog } from './ConfirmDialog'

interface Props { revision: number; onNotify: (message: string, kind?: 'ok' | 'error') => void; onChanged?: () => void }
const message = (reason: unknown) => reason instanceof Error ? reason.message : '型号操作失败'
const ordered = (rows: ModelOption[]) => [...rows].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN') || a.id - b.id)

export function ModelOptions({ revision, onNotify, onChanged }: Props) {
  const [rows, setRows] = useState<ModelOption[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('')
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState<ModelOption | null>(null)
  const request = useRef(0)
  const mutation = useRef(false)

  const refresh = async (force = false) => {
    if (mutation.current && !force) return
    const token = ++request.current; setLoading(true); setError('')
    try { const result = await api.modelOptions(); if (request.current === token) setRows(ordered(result)) }
    catch (reason) { if (request.current === token) { setRows([]); setError(message(reason)) } }
    finally { if (request.current === token) setLoading(false) }
  }
  useEffect(() => { void refresh(); return () => { request.current++ } }, [revision])

  const save = async (operation: () => Promise<ModelOption>, success: string, creating = false) => {
    if (mutation.current) return
    mutation.current = true; setBusy(true); request.current++; setLoading(false)
    try {
      const result = await operation()
      setRows(current => ordered([...current.filter(row => row.id !== result.id), result]))
      if (creating) setName('')
      setConfirm(null); onNotify(success); onChanged?.()
    } catch (reason) {
      setConfirm(null); onNotify(message(reason), 'error')
      if (reason instanceof ApiError && [401, 403].includes(reason.status)) { setRows([]); setError(message(reason)) }
      else if (reason instanceof ApiError && reason.status === 409) await refresh(true)
    } finally { mutation.current = false; setBusy(false) }
  }
  const visible = rows.filter(row => row.name.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())
    && (!filter || row.disabled === (filter === 'disabled')))

  return <article className="foundation-card" aria-label="设备型号管理">
    <div className="foundation-model-tools">
      <div className="foundation-model-filters"><input type="search" aria-label="搜索设备型号" placeholder="搜索设备型号" value={query} onChange={event => setQuery(event.target.value)} /><select aria-label="型号状态" value={filter} onChange={event => setFilter(event.target.value)}><option value="">全部状态</option><option value="enabled">启用</option><option value="disabled">停用</option></select></div>
      <form className="foundation-model-create" aria-label="新建设备型号" onSubmit={event => { event.preventDefault(); if (name.trim()) void save(() => api.createModelOption(name.trim()), '型号已新增', true) }}><input aria-label="新型号名称" placeholder="新型号名称" required maxLength={120} value={name} disabled={busy || loading || !!error} onChange={event => setName(event.target.value)} /><button className="button primary" disabled={busy || loading || !!error || !name.trim()}><PlusIcon />新增型号</button></form>
    </div>
    {error ? <p role="alert">{error}，请刷新重试。</p> : loading ? <p role="status" className="foundation-model-empty">正在读取型号…</p> : <>
      <div className="foundation-model-table-wrap"><table className="foundation-model-table"><thead><tr><th>设备型号</th><th>状态</th><th>关联资产</th><th>操作</th></tr></thead><tbody>{visible.map(row => <tr key={row.id}><td>{row.name}</td><td><span className={`foundation-model-state ${row.disabled ? 'disabled' : 'enabled'}`}>{row.disabled ? '停用' : '启用'}</span></td><td>{row.assetCount} 项</td><td><button className="button ghost" disabled={busy} onClick={() => row.disabled ? void save(() => api.setModelAvailability(row.id, row.version, false), '型号已启用') : setConfirm(row)}>{row.disabled ? '启用' : '停用'}</button></td></tr>)}</tbody></table>{!visible.length && <p className="foundation-model-empty">暂无匹配型号</p>}</div>
      <div className="foundation-model-total">共 {visible.length} 项型号</div>
    </>}
    {confirm && <ConfirmDialog title="停用设备型号" description={`停用“${confirm.name}”后不再供新选择；已关联的 ${confirm.assetCount} 项资产保留原型号，不修改资产档案。`} confirmLabel="确认停用" busy={busy} onCancel={() => !busy && setConfirm(null)} onConfirm={() => void save(() => api.setModelAvailability(confirm.id, confirm.version, true), '型号已停用，已有资产保留原型号')} />}
  </article>
}
