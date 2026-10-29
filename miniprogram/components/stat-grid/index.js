// 统计卡宫格公共组件（B7-1：收敛六套首页统计卡实现，体检复用项3）
// items: [{ label, value, unit?, filter? }]；点击携带 filter 触发 statTap
Component({
  properties: { items: { type: Array, value: [] } },
  methods: {
    tap(e) {
      const filter = e.currentTarget.dataset.filter;
      this.triggerEvent('stattap', { filter });
    },
  },
});
