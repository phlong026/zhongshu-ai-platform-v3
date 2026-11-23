Component({
  properties: {
    rows: { type: Array, value: [] },
    scope: { type: String, value: '' },
    ownerMode:{type:Boolean,value:false},
    appearance:{type:String,value:''},title:{type:String,value:'今日待办'}
  },
  data: { visibleRows: [], expanded: false, total: 0, remaining: 0 },
  observers: {
    'rows, scope': function(rows, scope) {
      if (this.__scope !== scope) {
        this.__scope = scope;
        this.setData({ expanded: false });
      }
      this.renderRows(rows);
    },
    appearance(){this.renderRows(this.data.rows);},
    ownerMode(){this.renderRows(this.data.rows);}
  },
  methods: {
    renderRows(rows) {
      const owner = this.data.ownerMode ? require('../../utils/owner-todo-view').board(rows) : null;
      const all = owner ? owner.rows : Array.isArray(rows) ? rows : [];
      this.__renderRows = all;
      const limit = 3;
      const expanded = !owner && all.length > limit && this.data.expanded;
      const visibleRows = (expanded ? all : all.slice(0, limit)).map((item, index) => ({
        key: item.key || item.id || String(index),
        title: item.title || [item.company, item.name].filter(Boolean).join(' · '),
        meta: owner ? item.ownerMeta : item.typeLabel
          ? [item.typeLabel, item.clickable === false ? item.actionHint : ''].filter(Boolean).join(' · ')
          : item.detail || item.status || item.note || '',
        amount: item.amount || '',
        initial:(String(item.title||item.name||'事').match(/[\u4e00-\u9fffA-Za-z0-9]/)||['事'])[0],action:item.actionLabel||'查看',
        disabled: item.clickable === false || item.disabled === true,
        index
      }));
      this.setData({ visibleRows, expanded, total: all.length, remaining: Math.max(0, all.length - limit),limit, decisionTotal: owner ? owner.decisionTotal : 0 });
    },
    toggle() {
      this.setData({ expanded: !this.data.expanded });
      this.renderRows(this.data.rows);
    },
    openOwnerList(e) {
      this.triggerEvent('openlist', { decisions: e.currentTarget.dataset.mode === 'decisions' });
    },
    activate(e) {
      const item = (this.__renderRows || this.data.rows)[Number(e.currentTarget.dataset.index)];
      if (!item || item.clickable === false || item.disabled === true) return;
      this.triggerEvent('activate', { item });
    }
  }
});
