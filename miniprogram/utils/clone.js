// 深拷贝单源（原 25+ 处独立定义收敛于此）
const clone = value => value === undefined ? value : JSON.parse(JSON.stringify(value));
module.exports = clone;
