Component({
properties: { workbench:{type:Boolean,value:false}, panel: { type: Object, value: null, observer() { this.setData({ values: {} }); } } },
data: { values: {} },
methods: {
noop() {},
close() { this.triggerEvent('close'); },
input(e) { this.setData({ ['values.' + e.currentTarget.dataset.key]: e.detail.value }); },
submit() { this.triggerEvent('submit', { values: this.data.values }); }
}
});
