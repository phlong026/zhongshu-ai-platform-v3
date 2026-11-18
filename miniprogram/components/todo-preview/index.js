Component({
  properties: {
    rows: { type: Array, value: [] },
    scope: { type: String, value: '' },
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
    appearance(){this.renderRows(this.data.rows);}
  },
  methods: {
    renderRows(rows) {
      const all = Array.isArray(rows) ? rows : [];
      const limit = 3;
      const expanded = all.length > limit && this.data.expanded;
      const visibleRows = (expanded ? all : all.slice(0, limit)).map((item, index) => ({
        key: item.key || item.id || String(index),
        title: item.title || [item.company, item.name].filter(Boolean).join(' · '),
        meta: item.typeLabel
          ? [item.typeLabel, item.clickable === false ? item.actionHint : ''].filter(Boolean).join(' · ')
          : item.detail || item.status || item.note || '',
        amount: item.amount || '',
        initial:(String(item.title||item.name||'事').match(/[\u4e00-\u9fffA-Za-z0-9]/)||['事'])[0],action:item.actionLabel||'查看',
        disabled: item.clickable === false || item.disabled === true,
        index
      }));
      this.setData({ visibleRows, expanded, total: all.length, remaining: Math.max(0, all.length - limit),limit });
    },
    toggle() {
      this.setData({ expanded: !this.data.expanded });
      this.renderRows(this.data.rows);
    },
    activate(e) {
      const item = this.data.rows[Number(e.currentTarget.dataset.index)];
      if (!item || item.clickable === false || item.disabled === true) return;
      this.triggerEvent('activate', { item });
    }
  }
});
