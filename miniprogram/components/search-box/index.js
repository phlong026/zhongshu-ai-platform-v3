Component({
  properties: {
    value: { type: String, value: '' },
    placeholder: { type: String, value: '' },
    ariaLabel: { type: String, value: '搜索' },
    variant: { type: String, value: 'plain' },
    icon: { type: String, value: '/assets/search.png' },
    clearIcon: { type: String, value: '/assets/studio-clear.png' },
    clearable: { type: Boolean, value: true },
    maxlength: { type: Number, value: 60 },
  },
  methods: {
    input(e) { this.triggerEvent('input', e.detail); },
    clear() { this.triggerEvent('clear'); },
  },
});
