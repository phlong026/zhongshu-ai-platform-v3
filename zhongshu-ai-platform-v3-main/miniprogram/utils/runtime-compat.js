// Mini-program JavaScript engines do not all provide Array.prototype.at.
if (!Array.prototype.at) Object.defineProperty(Array.prototype, 'at', {
  configurable: true, writable: true,
  value: function (index) {
    const length = this.length >>> 0, number = Number(index) || 0;
    const offset = number < 0 ? Math.ceil(number) : Math.floor(number);
    return this[offset < 0 ? length + offset : offset];
  }
});
module.exports = {};
