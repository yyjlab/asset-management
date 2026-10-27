import { useEffect, useId, useRef, useState } from 'react'
import { ChevronIcon, CloseIcon, PlusIcon } from './Icons'

export interface ComboboxOption {
  id: number | string
  label: string
}

interface Props {
  value: string
  selectedId?: number | string | null
  options: ComboboxOption[]
  placeholder: string
  editable?: boolean
  required?: boolean
  allowCreate?: boolean
  suggestOnly?: boolean
  onChange: (value: string, selectedId: number | string | null) => void
  onCreate?: () => void
  onDelete?: (option: ComboboxOption) => Promise<boolean>
}

const normalized = (text: string) => text.normalize('NFKC').replace(/\s+/g, '').toLocaleLowerCase()
export function filterSuggestions(options: ComboboxOption[], input: string, limit = 8) {
  const query = normalized(input)
  if (!query) return []
  const rank = (label: string) => label === query ? 0 : label.startsWith(query) ? 1 : 2
  return options.map((option, index) => ({ option, index, label: normalized(option.label) }))
    .filter(item => item.label.includes(query))
    .sort((a, b) => rank(a.label) - rank(b.label) || a.index - b.index)
    .slice(0, limit).map(item => item.option)
}

export function EditableCombobox({ value, selectedId, options, placeholder, editable = false, required, allowCreate, suggestOnly = false, onChange, onCreate, onDelete }: Props) {
  const [open, setOpen] = useState(false)
  const [activeId, setActiveId] = useState<ComboboxOption['id'] | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const listId = useId()
  const visible = suggestOnly ? filterSuggestions(options, value) : options
  const activeIndex = visible.findIndex(option => option.id === activeId)
  const expanded = open && (!suggestOnly || !!normalized(value))

  useEffect(() => {
    const close = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [])

  const choose = (option: ComboboxOption) => {
    setOpen(false); setActiveId(null)
    onChange(option.label, option.id)
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
    window.setTimeout(() => setOpen(false), 0)
  }

  const remove = async (option: ComboboxOption) => {
    if (!onDelete) return
    const deleted = await onDelete(option)
    if (deleted && option.id === selectedId) onChange('', null)
  }

  return <div ref={rootRef} className={`editable-combobox ${expanded ? 'open' : ''} ${suggestOnly ? 'autocomplete' : ''}`} onKeyDown={(event) => {
    if (event.nativeEvent.isComposing || event.keyCode === 229) return
    if (event.key === 'Escape') { if (expanded) event.stopPropagation(); setOpen(false); setActiveId(null) }
    if (suggestOnly && ['ArrowDown', 'ArrowUp'].includes(event.key) && visible.length) {
      event.preventDefault(); setOpen(true)
      const next = activeIndex < 0 ? (event.key === 'ArrowDown' ? 0 : visible.length - 1)
        : (activeIndex + (event.key === 'ArrowDown' ? 1 : -1) + visible.length) % visible.length
      setActiveId(visible[next].id)
      rootRef.current?.querySelector(`#${CSS.escape(`${listId}-${next}`)}`)?.scrollIntoView({ block: 'nearest' })
    }
    if (suggestOnly && event.key === 'Enter' && expanded) {
      event.preventDefault()
      if (activeIndex >= 0) choose(visible[activeIndex])
    }
  }}>
    {editable
      ? <input role="combobox" aria-autocomplete="list" aria-expanded={expanded} aria-controls={listId} aria-activedescendant={expanded && activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined} autoComplete="off" required={required} value={value} placeholder={placeholder} onFocus={() => setOpen(true)} onBlur={event => { if (!event.currentTarget.parentElement?.contains(event.relatedTarget)) setOpen(false) }} onChange={(event) => {
        const next = event.target.value
        const exact = options.find((option) => option.label.localeCompare(next, undefined, { sensitivity: 'accent' }) === 0)
        onChange(next, exact?.id ?? null)
        setActiveId(null); setOpen(true)
      }} />
      : <button type="button" className={`combobox-value ${value ? '' : 'placeholder'}`} onClick={() => setOpen((current) => !current)}>{value || placeholder}</button>}
    {!suggestOnly && <button type="button" className="combobox-arrow" aria-label={expanded ? '收起选项' : '展开选项'} aria-expanded={expanded} onClick={() => setOpen((current) => !current)}><ChevronIcon /></button>}
    {expanded && <div className="combobox-menu">
      <div className="combobox-options" role="listbox" id={listId}>
        {visible.length === 0 ? <span className="combobox-empty" role="status">{suggestOnly ? '未找到匹配型号' : '暂无可选项'}</span> : visible.map((option, index) => <div
          className={`combobox-option ${onDelete ? 'deletable' : ''} ${option.id === selectedId ? 'selected' : ''} ${option.id === activeId ? 'keyboard-active' : ''}`}
          key={option.id}
          id={`${listId}-${index}`}
          role="option"
          aria-selected={option.id === selectedId}
          tabIndex={0}
          onMouseDown={event => event.preventDefault()}
          onClick={() => choose(option)}
          onKeyDown={(event) => {
            if (!event.nativeEvent.isComposing && event.keyCode !== 229 && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); event.stopPropagation(); choose(option) }
          }}
        >
          {onDelete && <button type="button" className="combobox-delete" title={`删除${option.label}`} aria-label={`删除${option.label}`} onClick={(event) => { event.stopPropagation(); void remove(option) }}><CloseIcon /></button>}
          <span className="combobox-option-label">{option.label}</span>
        </div>)}
      </div>
      {allowCreate && onCreate && <button type="button" className="combobox-create" onClick={() => { setOpen(false); onCreate() }}><PlusIcon />新建选项</button>}
    </div>}
  </div>
}
