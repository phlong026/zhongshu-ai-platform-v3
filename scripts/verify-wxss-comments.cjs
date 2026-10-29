const fs = require('node:fs');
const path = require('node:path');

function verifyComments(source, filename) {
  let quote = '', comment = false;
  for (let index = 0; index < source.length; index += 1) {
    const pair = source.slice(index, index + 2);
    if (comment) {
      if (pair === '*/') { comment = false; index += 1; }
    } else if (quote) {
      if (source[index] === '\\') index += 1;
      else if (source[index] === quote) quote = '';
    } else if (pair === '/*') {
      comment = true;
      index += 1;
    } else if (pair === '*/') {
      throw Error('WXSS 注释提前闭合或有多余结束符：' + filename);
    } else if (source[index] === '"' || source[index] === "'") {
      quote = source[index];
    }
  }
  if (comment) throw Error('WXSS 注释未闭合：' + filename);
}

function verifyDirectory(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const filename = path.join(directory, entry.name);
    if (entry.isDirectory()) verifyDirectory(filename);
    else if (filename.endsWith('.wxss')) verifyComments(fs.readFileSync(filename, 'utf8'), filename);
  }
}

module.exports = { verifyComments, verifyDirectory };
if (require.main === module) {
  verifyDirectory(path.resolve(__dirname, '../miniprogram'));
  console.log('PASS WXSS 注释边界检查');
}
