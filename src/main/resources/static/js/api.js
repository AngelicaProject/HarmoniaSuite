async function req(url,opt){
  const ctrl = (typeof AbortController !== 'undefined') ? new AbortController() : null;
  const timer = ctrl ? setTimeout(() => ctrl.abort(), 120000) : null;
  try {
    const r = await fetch(url, Object.assign({}, opt || {}, ctrl ? {signal: ctrl.signal} : {}));
    const d = await r.json().catch(()=>({error:r.statusText}));
    if(d.error) throw Error(d.error);
    return d;
  } catch(e) {
    if (e && e.name === 'AbortError') throw Error('Превышено время ожидания ответа сервера');
    throw e;
  } finally {
    if (timer) clearTimeout(timer);
  }
}
const enc=encodeURIComponent;
const mapBackupList=(d)=>({backups:d.backups,retention:d.retention,autoIntervalMinutes:d.auto_interval_minutes,usedBytes:d.used_bytes,estimatedBytes:d.estimated_bytes});
export const api={
  projects:()=>req('/api/projects'),
  createProject:(name,root)=>req('/api/projects',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:name,root})}),
  overview:(projectId)=>req('/api/projects/'+enc(projectId)+'/overview'),
  pendingByFile:(projectId)=>req('/api/projects/'+enc(projectId)+'/translate/pending-by-file'),
  files:(projectId,f,page)=>{
    const p=new URLSearchParams();
    if(f){
      if(f.q) p.set('q',f.q);
      if(f.hideReady) p.set('hideReady','true');
      if(f.readyOnly) p.set('readyOnly','true');
    }
    if(page){
      if(page.offset) p.set('offset',page.offset);
      if(page.limit) p.set('limit',page.limit);
    }
    return req('/api/projects/'+enc(projectId)+'/files?'+p.toString());
  },
  entries:(projectId,f,page)=>{
    const p=new URLSearchParams();
    if(f){
      if(f.file) p.set('file',f.file);
      if(f.q) p.set('q',f.q);
      if(f.status) p.set('status',f.status);
      if(f.rowKey) p.set('rowKey',f.rowKey);
      if(f.untranslated) p.set('untranslated','true');
    }
    if(page){
      if(page.offset) p.set('offset',page.offset);
      if(page.limit) p.set('limit',page.limit);
    }
    return req('/api/projects/'+enc(projectId)+'/entries?'+p.toString());
  },
  entry:(projectId,entryId)=>req('/api/projects/'+enc(projectId)+'/entries/'+enc(entryId)),
  entryByCell:(projectId,cellId)=>req('/api/projects/'+enc(projectId)+'/entries/by-cell/'+enc(cellId)),
  fileTree:(projectId)=>req('/api/projects/'+enc(projectId)+'/files/tree'),
  patchEntry:(projectId,key,translation,status)=>req('/api/projects/'+enc(projectId)+'/entries/'+enc(key),{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({translation,status})}),
  startJob:(body)=>req('/api/jobs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:body.action,project_id:body.projectId,root:body.root,output:body.output,files:body.files,force:body.force,model:body.model,reasoning:body.reasoning})}),
  jobGet:(jobId)=>req('/api/jobs/'+enc(jobId)),
  jobCancel:(jobId)=>req('/api/jobs/'+enc(jobId),{method:'DELETE'}),
  status:()=>req('/api/status'),
  version:()=>req('/api/version'),
  updateStatus:()=>req('/api/update/status'),
  runUpdate:()=>req('/api/update',{method:'POST'}),
  aiSettings:()=>req('/api/settings/ai'),
  saveAiSettings:(b)=>req('/api/settings/ai',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({provider:b.provider,gemini_key:b.geminiKey,openrouter_key:b.openrouterKey,openrouter_model:b.openrouterModel,openrouter_reasoning:b.openrouterReasoning})}),
  settings:()=>req('/api/settings'),
  saveSettings:(b)=>req('/api/settings',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({game_path:b.gamePath})}),
  checkAiKey:(b)=>req('/api/settings/ai/check',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({provider:b.provider,key:b.key})}),
  aiModels:()=>req('/api/settings/ai/models'),
  detectSettings:()=>req('/api/settings/detect'),
  backups:()=>req('/api/backup').then(mapBackupList),
  createBackup:()=>req('/api/backup',{method:'POST'}),
  saveBackupSettings:(retention,autoIntervalMinutes)=>req('/api/backup/settings',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({retention,auto_interval_minutes:autoIntervalMinutes})}).then(mapBackupList),
  deleteBackup:(name)=>req('/api/backup/'+encodeURIComponent(name),{method:'DELETE'}),
  openBackupFolder:()=>req('/api/backup/open-folder',{method:'POST'}),
  backupDownloadUrl:(name)=>'/api/backup/'+encodeURIComponent(name),
  _previewCache:new Map(),
  preview(root,file){
    const k=root+'|'+file;
    if(this._previewCache.has(k)) return Promise.resolve(this._previewCache.get(k));
    return req('/api/source/preview?root='+enc(root)+'&file='+enc(file)).then(d=>{this._previewCache.set(k,d);return d;});
  },
  previewFull:(root,file)=>req('/api/source/preview?root='+enc(root)+'&file='+enc(file)+'&full=true'),
  sourceFiles:(root)=>req('/api/source/files?root='+enc(root)),
  getPack:(projectId)=>req('/api/projects/'+enc(projectId)+'/pack'),
  savePack:(projectId,pack)=>req('/api/projects/'+enc(projectId)+'/pack',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(pack)}),
  exportCsvUrl:(projectId,file)=>'/api/projects/'+enc(projectId)+'/exports/csv?file='+enc(file),
  exportZipUrl:(projectId)=>'/api/projects/'+enc(projectId)+'/exports/zip',
  exportManifestUrl:(projectId)=>'/api/projects/'+enc(projectId)+'/exports/manifest',
  exportList:(projectId)=>req('/api/projects/'+enc(projectId)+'/exports'),
  deltaExport:(projectId,f)=>{
    const p=new URLSearchParams();
    if(f){
      if(f.sinceUpdatedAt) p.set('sinceUpdatedAt',f.sinceUpdatedAt);
      if(f.sinceCellId) p.set('sinceCellId',f.sinceCellId);
      if(f.files) p.set('files',f.files);
      if(f.limit) p.set('limit',f.limit);
      if(f.author) p.set('author',f.author);
    }
    return req('/api/projects/'+enc(projectId)+'/delta?'+p.toString());
  },
  deltaPreview:(projectId,body)=>req('/api/projects/'+enc(projectId)+'/delta/preview',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({author:body.author,files_allowlist:body.filesAllowlist,max_status:body.maxStatus,sources_fp:body.sourcesFp,game_version:body.gameVersion,rows:(body.rows||[]).map(r=>({cell_id:r.cellId,file_path:r.filePath,source:r.source,translation:r.translation,status:r.status}))})}),
  deltaImport:(projectId,body)=>req('/api/projects/'+enc(projectId)+'/delta/import',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({author:body.author,files_allowlist:body.filesAllowlist,max_status:body.maxStatus,sources_fp:body.sourcesFp,game_version:body.gameVersion,rows:(body.rows||[]).map(r=>({cell_id:r.cellId,file_path:r.filePath,source:r.source,translation:r.translation,status:r.status}))})}),
};
export const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[c]));
