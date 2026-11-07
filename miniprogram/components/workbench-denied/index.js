// 无权限/未登录拒绝态公共组件（B7-2：收敛三页近似实现）
// title 标题；lines 说明行数组；active 已登录则显示返回按钮，否则登录按钮
Component({
  properties: {
    title: { type: String, value: '' },
    lines: { type: Array, value: [] },
    homeTitle: { type: String, value: '' },
    active: { type: Boolean, value: false },
    loginLabel: { type: String, value: '登录后继续' },
  },
  methods: {
    home() { this.triggerEvent('home'); },
    identity() { this.triggerEvent('identity'); },
  },
});
