import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { AccessoryStock, AccessoryStockDetails, LookupValue } from '../types'
import { CloseIcon, PlusIcon } from './Icons'
import './Accessories.css'

export function AccessoryInventory({ lookups, onNotify }: { lookups: LookupValue[]; onNotify: (text: string, kind?: 'ok' | 'error') => void }) {
  const [rows,setRows]=useState<AccessoryStock[]>([])
  const [loading,setLoading]=useState(true),[error,setError]=useState(''),[revision,setRevision]=useState(0)
  const [type,setType]=useState(''),[search,setSearch]=useState(''),[busy,setBusy]=useState(false)
  const [mode,setMode]=useState<'create'|'restock'|'scrap'|null>(null),[selected,setSelected]=useState<AccessoryStock|null>(null)
  const [details,setDetails]=useState<AccessoryStockDetails|null>(null),[detailError,setDetailError]=useState('')
  const dialog=useRef<HTMLDialogElement>(null)
  useEffect(()=>{if((mode||selected)&&dialog.current&&!dialog.current.open)dialog.current.showModal()},[mode,selected?.id])
  const [draft,setDraft]=useState({type:'鼠标',brand:'',companyId:'',locationId:'',quantity:1,note:''})
  useEffect(()=>{let active=true;setLoading(true);setError('');api.accessoryStock().then(data=>{if(active)setRows(data)}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[revision])
  const visible=rows.filter(row=>(!type||type===row.type)&&`${row.brand} ${row.company} ${row.location}`.toLowerCase().includes(search.trim().toLowerCase()))
  const total=(key:'total'|'available'|'paired'|'used'|'scrapped')=>visible.reduce((sum,row)=>sum+row[key],0)
  const open=(value:'create'|'restock'|'scrap',row:AccessoryStock|null=null)=>{setMode(value);setSelected(row);setDetails(null);setDraft({type:row?.type||'鼠标',brand:row?.brand||'',companyId:String(row?.companyId||''),locationId:String(row?.locationId||''),quantity:1,note:''})}
  const readDetails=(row:AccessoryStock)=>{setMode(null);setSelected(row);setDetails(null);setDetailError('')}
  useEffect(()=>{
    if(mode||!selected)return
    let active=true
    api.accessoryStockDetails(selected.id).then(value=>{if(active)setDetails(value)}).catch(e=>{if(active)setDetailError(e instanceof Error?e.message:'读取明细失败')})
    return()=>{active=false}
  },[selected?.id,mode])
  const save=async(event:React.FormEvent)=>{
    event.preventDefault();if(busy||!mode)return;setBusy(true)
    try{
      if(mode==='create')await api.createAccessoryStock({type:draft.type.trim(),brand:draft.brand.trim(),companyId:Number(draft.companyId),locationId:Number(draft.locationId),quantity:draft.quantity})
      else if(selected)await api.adjustAccessoryStock(selected.id,{version:selected.version,quantity:draft.quantity,scrap:mode==='scrap',note:draft.note.trim()})
      setMode(null);setSelected(null);setRevision(v=>v+1);onNotify('配件库存已更新')
    }catch(e){onNotify(e instanceof Error?e.message:'操作失败','error');setRevision(v=>v+1)}finally{setBusy(false)}
  }
  const dictionaries=(kind:'COMPANY'|'LOCATION')=>lookups.filter(row=>row.type===kind)
  return <section className="accessory-inventory" aria-label="配件库存">
    <header className="accessory-inventory-heading"><h2>配件库存</h2><div><button className="button ghost" disabled={loading||busy} onClick={()=>setRevision(v=>v+1)}>刷新</button><button className="button primary" disabled={busy} onClick={()=>open('create')}><PlusIcon/>入库</button></div></header>
    <div className="accessory-stock-summary">{([['total','总数量'],['available','可用库存'],['paired','已配套'],['used','使用中'],['scrapped','报废']] as const).map(([key,label])=><div key={key}><span>{label}</span><strong>{total(key)}</strong></div>)}</div>
    <div className="accessory-stock-filters"><select aria-label="配件类型" value={type} onChange={e=>setType(e.target.value)}><option value="">全部类型</option>{[...new Set(rows.map(row=>row.type))].map(value=><option key={value}>{value}</option>)}</select><input type="search" aria-label="搜索配件库存" placeholder="搜索品牌、公司或位置" value={search} onChange={e=>setSearch(e.target.value)}/></div>
    {error?<p role="alert">{error}</p>:loading?<p role="status">正在读取库存…</p>:<div className="accessory-stock-table"><table><thead><tr>{['类型','品牌','公司 / 位置','可用','已配套','使用中','报废','操作'].map(value=><th key={value}>{value}</th>)}</tr></thead><tbody>{visible.map(row=><tr key={row.id}><td>{row.type}</td><td><strong>{row.brand}</strong></td><td>{row.company} / {row.location}</td><td>{row.available}</td><td>{row.paired}</td><td>{row.used}</td><td>{row.scrapped}</td><td><button className="button ghost" onClick={()=>void readDetails(row)}>明细</button><button className="button ghost" onClick={()=>open('restock',row)}>补货</button><button className="button ghost" disabled={!row.available} onClick={()=>open('scrap',row)}>报废</button></td></tr>)}{!visible.length&&<tr><td colSpan={8}>暂无配件库存</td></tr>}</tbody></table></div>}
    {(mode||selected)&&<dialog ref={dialog} className="accessory-stock-dialog" aria-label={mode?mode==='create'?'配件入库':mode==='scrap'?'配件报废':'配件补货':'配件明细'} onCancel={event=>{event.preventDefault();if(!busy){setMode(null);setSelected(null)}}}><header><h3>{mode?mode==='create'?'配件入库':mode==='scrap'?'配件报废':'配件补货':`${selected?.type} / ${selected?.brand}`}</h3><button type="button" className="button ghost" aria-label="关闭配件窗口" disabled={busy} onClick={()=>{setMode(null);setSelected(null)}}><CloseIcon/></button></header>
      {mode?<form onSubmit={e=>void save(e)}><div className="accessory-stock-fields">{mode==='create'?<><label>类型<input required maxLength={120} list="accessory-stock-types" value={draft.type} onChange={e=>setDraft({...draft,type:e.target.value})}/><datalist id="accessory-stock-types">{['鼠标','键盘','电源','转接头'].map(value=><option key={value}>{value}</option>)}</datalist></label><label>品牌<input required maxLength={160} value={draft.brand} onChange={e=>setDraft({...draft,brand:e.target.value})}/></label>{(['COMPANY','LOCATION'] as const).map(kind=><label key={kind}>{kind==='COMPANY'?'公司':'位置'}<select required value={kind==='COMPANY'?draft.companyId:draft.locationId} onChange={e=>setDraft({...draft,[kind==='COMPANY'?'companyId':'locationId']:e.target.value})}><option value="">请选择</option>{dictionaries(kind).map(value=><option key={value.id} value={value.id}>{value.name}</option>)}</select></label>)}</>:<p>{selected?.type} / {selected?.brand} · {selected?.company} / {selected?.location}</p>}
      <label>数量<input required type="number" min={1} max={mode==='scrap'?selected?.available:9999999} step={1} value={draft.quantity} onChange={e=>setDraft({...draft,quantity:Number(e.target.value)})}/></label>{mode!=='create'&&<label>备注<input maxLength={500} value={draft.note} onChange={e=>setDraft({...draft,note:e.target.value})}/></label>}</div>{mode==='scrap'&&<p>确认将选中数量记为报废？本次操作不增加可用库存，并保留记录。</p>}<footer><button type="button" className="button ghost" disabled={busy} onClick={()=>{setMode(null);setSelected(null)}}>取消</button><button className="button primary" disabled={busy}>{busy?'保存中…':mode==='scrap'?'确认报废':'确认入库'}</button></footer></form>:detailError?<p role="alert">{detailError}</p>:!details?<p role="status">正在读取明细…</p>:<><h4>当前配套 / 领用</h4><div className="accessory-stock-table"><table><thead><tr><th>资产编号</th><th>领用人</th><th>数量</th><th>状态</th></tr></thead><tbody>{details.allocations.map(item=><tr key={item.assetId}><td>{item.assetTag}<small>{item.name}</small></td><td>{item.assignedTo||'未领用'}</td><td>{item.quantity}</td><td>{item.checkedOut?'使用中':'已配套'}</td></tr>)}{!details.allocations.length&&<tr><td colSpan={4}>暂无分配</td></tr>}</tbody></table></div><h4>最近操作</h4><ul className="accessory-stock-events">{details.events.map((item,index)=><li key={index}><strong>{item.summary}</strong> · {item.operator} · {new Date(item.time).toLocaleString('zh-CN')}<small>{item.changes}</small></li>)}</ul></>}
    </dialog>}
  </section>
}
