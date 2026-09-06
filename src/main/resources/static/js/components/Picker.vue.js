import {api} from '../api.js?v=29';
export default {
  props: ['modelValue', 'defaultRoot'], emits: ['update:modelValue', 'confirm'],
  data(){return{projects:[],pending:this.modelValue,newId:'',newRoot:'',error:'',q:'',loading:true,confirmId:'',deleteJob:null,deleteTimer:null}},
  computed:{
    filtered(){ const q=this.q.toLowerCase(); return this.projects.filter(p=>!q||(p.name||'').toLowerCase().includes(q)); },
    progress(){ return p=>{ const n=p.entries||0, t=p.translated||0; return n?Math.round(t/n*100):0; } },
    confirmName(){ const p=this.projects.find(p=>p.id===this.confirmId); return (p&&(p.name||p.id))||this.confirmId; },
    deleteProgress(){
      let removed=0, total=0;
      const out=(this.deleteJob&&this.deleteJob.output)||'';
      const lines=out.split('\n');
      for(let i=lines.length-1;i>=0;i--){
        const m=lines[i].match(/Удалено записей:\s*(\d+)\s*\/\s*(\d+)/);
        if(m){ removed=+m[1]; total=+m[2]; break; }
        const t=lines[i].match(/Записей к удалению:\s*(\d+)/);
        if(t){ total=+t[1]; break; }
      }
      return {removed,total,pct:total?Math.min(100,Math.round(removed/total*100)):0};
    },
    fmt(){ return n=>Number(n||0).toLocaleString('ru-RU'); }
  },
  async mounted(){ try{ const d=await api.projects(); this.projects=d.projects||[]; if(this.projects[0]&&!this.pending) this.pending=this.projects[0].id; }catch(e){this.error=e.message} this.loading=false; },
  methods:{
    pick(p){ this.pending=p; this.error=''; },
    async create(){
      if(!this.newId.trim()){ this.error='Введите id'; return; }
      try{
        const root=(this.newRoot||'').trim()||this.defaultRoot||'';
        const d=await api.createProject(this.newId.trim(), root);
        this.pending=d.id; const r=await api.projects(); this.projects=r.projects||[]; this.newId=''; this.error='';
      }catch(e){ this.error=e.message; }
    },
    async handleOpen(){ if(!this.pending){ this.error='Выберите проект'; return; } this.$emit('update:modelValue',this.pending); this.$emit('confirm',this.pending); },
    async handleDelete(e){
      if(!this.pending){ this.error='Выберите проект'; return; }
      if(!e.ctrlKey){ this.error='Зажмите Ctrl чтобы удалить выбранный проект'; return; }
      this.confirmId=this.pending;
    },
    async confirmDelete(){
      const id=this.confirmId;
      try{
        const d=await api.startJob({action:'delete',projectId:id});
        this.deleteJob={id:d.id,output:'',status:d.status||'running',done:false};
        this.pollDelete();
      }catch(err){ this.error=err.message; this.confirmId=''; }
    },
    pollDelete(){
      if(this.deleteTimer) clearInterval(this.deleteTimer);
      this.deleteTimer=setInterval(async()=>{
        const dj=this.deleteJob;
        if(!dj){ clearInterval(this.deleteTimer); this.deleteTimer=null; return; }
        try{
          const d=await api.jobGet(dj.id);
          dj.output=d.output||''; dj.status=d.status;
          if(d.status&&d.status!=='running'&&d.status!=='queued'){
            clearInterval(this.deleteTimer); this.deleteTimer=null; dj.done=true;
            if(d.status==='completed'){
              this.projects=this.projects.filter(p=>p.id!==this.confirmId);
              if(this.pending===this.confirmId) this.pending=this.projects[0]?.id||'';
              this.confirmId=''; this.deleteJob=null; this.error='';
            }else{
              this.refreshProjects();
            }
          }
        }catch(e){ dj.output+='\n'+e.message; }
      },700);
    },
    async cancelDelete(){
      if(!this.deleteJob) return;
      try{ await api.jobCancel(this.deleteJob.id); }catch(e){ this.error=e.message; }
    },
    closeDelete(){
      if(this.deleteTimer){ clearInterval(this.deleteTimer); this.deleteTimer=null; }
      this.deleteJob=null; this.confirmId='';
    },
    async refreshProjects(){
      try{ const r=await api.projects(); this.projects=r.projects||[]; }catch(e){ this.error=e.message; }
    },
  },
   template: `
  <div class="overlay">
    <div class="modal" style="width:1100px;max-width:96vw;max-height:92vh;display:flex;flex-direction:column">
      <div style="display:flex;justify-content:space-between;align-items:center;gap:14px">
        <div style="display:flex;align-items:center;gap:12px">
          <img src="/img/yuki-icon.png" alt="Yuki" style="width:38px;height:38px;border-radius:50%;object-fit:cover;box-shadow:0 0 0 1px var(--border);flex-shrink:0">
          <div>
            <h2 style="margin:0;font-size:16px;font-weight:750;letter-spacing:-.015em">Проекты Harmonia Suite</h2>
            <p class="muted" style="margin:3px 0 0;font-size:12px">Выберите проект или создайте новый. Прогресс считается по переведённым фразам.</p>
          </div>
        </div>
        <div style="position:relative;display:flex;align-items:center">
          <svg class="icon ic-search" viewBox="0 0 24 24" style="position:absolute;left:11px;width:15px;height:15px;pointer-events:none"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>
          <input v-model="q" placeholder="Поиск проекта…" style="width:200px;padding-left:34px">
        </div>
      </div>

      <div class="dash">
         <div v-for="p in filtered" :key="p.id" @click="pick(p.id)" class="pcard" :class="{sel:pending===p.id}">
            <div style="display:flex;align-items:center;gap:9px;padding-right:84px">
              <span class="logo" style="width:34px;height:34px;border-radius:10px;background:var(--surface-3);display:flex;align-items:center;justify-content:center;border:1px solid var(--border-soft);flex-shrink:0"><svg class="icon ic-folder" viewBox="0 0 24 24" style="width:18px;height:18px"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"/></svg></span>
              <div style="font-weight:700;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;letter-spacing:-.01em">{{p.name}}</div>
            </div>
            <div style="display:flex;gap:8px;font-size:12px" class="muted"><span style="display:inline-flex;align-items:center;gap:5px"><svg class="icon" viewBox="0 0 24 24" style="width:13px;height:13px"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5z"/><path d="M14 3v5h5"/></svg>{{p.files||0}} файлов</span><span style="display:inline-flex;align-items:center;gap:5px"><svg class="icon" viewBox="0 0 24 24" style="width:13px;height:13px"><path d="M4 6h16M4 12h16M4 18h10"/></svg>{{p.entries||0}} фраз</span></div>
            <div style="margin-top:auto">
              <div class="pc-prog-label"><span>{{progress(p)}}% переведено</span><span>{{p.translated||0}}/{{p.entries||0}}</span></div>
              <div class="pc-bar"><i :style="'width:'+progress(p)+'%'"></i></div>
            </div>
            <div v-if="p.updated_at" class="muted" style="font-size:11px;margin-top:8px">{{new Date(p.updated_at).toLocaleString('ru-RU')}}</div>
            <div v-if="p.error" style="color:var(--danger);font-size:12px;margin-top:4px">{{p.error}}</div>
         </div>
        <template v-if="loading">
          <div v-for="i in 6" :key="i" class="pcard" style="cursor:default">
            <div class="skel"></div>
            <div class="skel s"></div>
            <div class="skel bar"></div>
          </div>
        </template>
        <div v-else-if="!filtered.length" class="ft-empty" style="grid-column:1/-1"><svg class="icon" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"/></svg><span>{{projects.length?'Ничего не найдено':'Проектов нет — создайте новый'}}</span></div>
      </div>

      <div class="new-project">
        <label style="flex:1">Новый проект<input v-model="newId" @keydown.enter="create" class="grow" style="width:100%;margin-top:6px" placeholder="напр. patch-7.1"></label>
        <label style="width:280px">Корень исходников (пусто = активный)<input v-model="newRoot" class="grow" style="width:100%;margin-top:6px" :placeholder="defaultRoot||'активный корень'"></label>
        <button class="primary" style="align-self:flex-end" @click="create"><svg class="icon" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>Создать</button>
      </div>
       <div v-if="error" class="form-error"><svg class="icon" viewBox="0 0 24 24" style="width:14px;height:14px"><circle cx="12" cy="12" r="9"/><path d="M12 8v4M12 16h.01"/></svg>{{error}}</div>

      <div style="display:flex;justify-content:space-between;align-items:center;margin-top:16px">
        <button class="btn-danger" @click="handleDelete($event)" :disabled="!pending||deleteJob" title="Зажмите Ctrl для удаления выбранного проекта"><svg class="icon" viewBox="0 0 24 24"><path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>Удалить</button>
        <button class="primary" :disabled="!pending||deleteJob" @click="handleOpen()" style="padding:12px 24px;font-size:15px"><svg class="icon" viewBox="0 0 24 24"><path d="M5 12h14M13 6l6 6-6 6"/></svg>Открыть</button>
      </div>
      <div v-if="confirmId" class="overlay" style="z-index:2000;background:rgba(8,9,12,.6)" @click.self="deleteJob?'':(confirmId='')">
        <div class="modal" style="width:400px;padding:24px">
          <template v-if="!deleteJob">
          <h3 style="margin:0;font-size:16px;font-weight:700">Удалить проект «{{confirmName}}»?</h3>
          <p class="muted" style="margin:10px 0 0">Проект «{{confirmName}}» будет удалён безвозвратно.</p>
           <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:18px"><button @click="confirmId=''">Отмена</button><button class="btn-danger" @click="confirmDelete"><svg class="icon" viewBox="0 0 24 24"><path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1 2 2H8a2 2 0 0 1-2-2L5 6"/></svg>Удалить</button></div>
          </template>
          <template v-else>
          <h3 style="margin:0;font-size:16px;font-weight:700">Удаление проекта «{{confirmName}}»…</h3>
          <div v-if="deleteJob.status==='failed'" class="form-error" style="margin:10px 0 0;white-space:pre-wrap">{{deleteJob.output||'Ошибка'}}</div>
          <div v-else-if="deleteJob.status==='cancelled'" class="muted" style="margin:10px 0 0;font-size:13px;white-space:pre-wrap">{{deleteJob.output||'Удаление отменено.'}}</div>
          <div v-else-if="deleteJob.status==='queued'" class="muted" style="margin:10px 0 0;font-size:13px">В очереди — дождитесь завершения предыдущей задачи.</div>
          <div v-else style="margin-top:12px">
            <div class="pc-prog-label"><span>{{deleteProgress.pct}}%</span><span>{{fmt(deleteProgress.removed)}}/{{fmt(deleteProgress.total)}}</span></div>
            <div class="pc-bar"><i :style="'width:'+deleteProgress.pct+'%'"></i></div>
          </div>
           <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:18px"><button v-if="!deleteJob.done" @click="cancelDelete">Отмена</button><button v-else class="primary" @click="closeDelete">Закрыть</button></div>
          </template>
        </div>
      </div>
    </div>
  </div>`
}
