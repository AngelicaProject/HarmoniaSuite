export default {
  props: ['log'],
  template: `
  <section class="card" style="margin:0;height:100%;overflow:auto"><h2><svg class="icon" viewBox="0 0 24 24"><path d="M4 5h16M4 12h16M4 19h10"/></svg>Журнал</h2><div class="log" style="height:auto;min-height:120px">{{log}}</div></section>`
}
