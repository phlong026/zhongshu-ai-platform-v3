const validMonth = value => /^\d{4}-(0[1-9]|1[0-2])$/.test(value || '');
const currentMonth = () => {
  const now = new Date();
  return now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
};
Component({
  properties: {
    value: { type: String, value: '' },
    minYear: { type: Number, value: 2000 },
    maxYear: { type: Number, value: 2100 }
  },
  data: { visible: false, year: 2026, draft: '', months: [], displayValue: '' },
  observers: {
    value(value) { this.setData({ displayValue: validMonth(value) ? value : currentMonth() }); }
  },
  lifetimes: {
    attached() { this.setData({ displayValue: validMonth(this.data.value) ? this.data.value : currentMonth() }); }
  },
  pageLifetimes: { hide() { this.setData({ visible: false }); } },
  methods: {
    open() {
      const draft = validMonth(this.data.value) ? this.data.value : currentMonth();
      this.setData({ visible: true, draft, year: Number(draft.slice(0, 4)) });
      this.updateMonths();
    },
    updateMonths() {
      const year = this.data.year;
      this.setData({ months: Array.from({ length: 12 }, (_, index) => {
        const value = year + '-' + String(index + 1).padStart(2, '0');
        return { value, label: (index + 1) + '月', selected: value === this.data.draft };
      }) });
    },
    changeYear(e) {
      const step = Number(e.currentTarget.dataset.step);
      if (step !== -1 && step !== 1) return;
      const year = this.data.year + step;
      if (year < this.data.minYear || year > this.data.maxYear) return;
      this.setData({ year }); this.updateMonths();
    },
    choose(e) {
      const value = e.currentTarget.dataset.value;
      if (!validMonth(value) || Number(value.slice(0, 4)) !== this.data.year) return;
      this.setData({ draft: value }); this.updateMonths();
    },
    cancel() { this.setData({ visible: false }); },
    confirm() {
      const value = this.data.draft;
      if (!validMonth(value)) return;
      this.setData({ visible: false });
      if (value !== this.data.value) this.triggerEvent('change', { value });
    },
    preventMove() {}
  }
});
