Component({
  properties: {
    title: { type: String, value: '' },
    note: { type: String, value: '' },
    icon: { type: String, value: '' },
    noteIcon: { type: String, value: '/pages/engineering/assets/clock.png' },
    action: { type: String, value: '' },
    actionLabel: { type: String, value: '去办理' },
    outline: { type: Boolean, value: false },
    last: { type: Boolean, value: false },
  },
  methods: {
    activate() { this.triggerEvent('activate', { action: this.data.action }); },
  },
});
