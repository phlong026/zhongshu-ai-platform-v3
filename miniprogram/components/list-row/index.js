Component({
  options: { multipleSlots: true },
  externalClasses: ['custom-class'],
  properties: {
    interactive: { type: Boolean, value: true },
  },
  methods: {
    activate() { this.triggerEvent('activate'); },
  },
});
