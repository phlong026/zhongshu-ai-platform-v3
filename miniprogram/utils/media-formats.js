// Input formats are normalized by B before an asset can be used by a generation service.
const extensions = {
  jpg:'image/jpeg', jpeg:'image/jpeg', jpe:'image/jpeg', jfif:'image/jpeg', png:'image/png',
  webp:'image/webp', gif:'image/gif', bmp:'image/bmp', tif:'image/tiff', tiff:'image/tiff',
  heic:'image/heic', heif:'image/heif', avif:'image/avif',
  mp4:'video/mp4', mov:'video/quicktime', m4v:'video/x-m4v', webm:'video/webm',
  mkv:'video/x-matroska', avi:'video/x-msvideo', '3gp':'video/3gpp', '3g2':'video/3gpp2', mpg:'video/mpeg', mpeg:'video/mpeg',
  mp3:'audio/mpeg', m4a:'audio/mp4', wav:'audio/wav', aac:'audio/aac',
  ogg:'audio/ogg', oga:'audio/ogg', opus:'audio/ogg', flac:'audio/flac', amr:'audio/amr'
};
const aliases = {'video/mov':'video/quicktime', 'audio/mp3':'audio/mpeg', 'audio/m4a':'audio/mp4',
  'audio/x-m4a':'audio/mp4', 'audio/x-wav':'audio/wav', 'audio/wave':'audio/wav',
  'audio/x-aac':'audio/aac', 'audio/x-flac':'audio/flac'};
const supported = new Set([...Object.values(extensions), 'audio/3gpp', 'audio/webm']);

function fromName(name) {
  const match = String(name || '').split(/[?#]/)[0].match(/\.([a-z0-9]+)$/i);
  return match ? extensions[match[1].toLowerCase()] || '' : '';
}

function sniff(value, type) {
  const bytes = ArrayBuffer.isView(value) ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength) : new Uint8Array(value);
  const text = (start, length) => String.fromCharCode(...bytes.subarray(start, start + length));
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg';
  if (bytes[0] === 0x89 && text(1, 3) === 'PNG') return 'image/png';
  if (/^GIF8[79]a$/.test(text(0, 6))) return 'image/gif';
  if (text(0, 2) === 'BM') return 'image/bmp';
  if (text(0, 4) === 'II*\0' || text(0, 4) === 'MM\0*') return 'image/tiff';
  if (text(0, 4) === 'RIFF') {
    return {'WEBP':'image/webp', 'WAVE':'audio/wav', 'AVI ':'video/x-msvideo'}[text(8, 4)] || '';
  }
  if (text(4, 4) === 'ftyp') {
    const brand = text(8, 4);
    if (['heic','heix','hevc','hevx'].includes(brand)) return 'image/heic';
    if (['mif1','msf1'].includes(brand)) return 'image/heif';
    if (['avif','avis'].includes(brand)) return 'image/avif';
    if (brand === 'qt  ') return 'video/quicktime';
    if (brand.startsWith('3g')) return type === 'audio' ? 'audio/3gpp' : (brand.startsWith('3g2') ? 'video/3gpp2' : 'video/3gpp');
    return type === 'audio' || ['M4A ','M4B '].includes(brand) ? 'audio/mp4' : 'video/mp4';
  }
  if (text(0, 4) === 'fLaC') return 'audio/flac';
  if (text(0, 4) === 'OggS') return 'audio/ogg';
  if (text(0, 5) === '#!AMR') return 'audio/amr';
  if (text(0, 3) === 'ID3') return 'audio/mpeg';
  if (bytes[0] === 0xff && (bytes[1] & 0xf6) === 0xf0) return 'audio/aac';
  if (bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0) return 'audio/mpeg';
  if (bytes[0] === 0x1a && bytes[1] === 0x45 && bytes[2] === 0xdf && bytes[3] === 0xa3) {
    return text(0, Math.min(bytes.length, 256)).includes('webm') ? (type === 'audio' ? 'audio/webm' : 'video/webm') : 'video/x-matroska';
  }
  return '';
}

function mimeType(filePath, bytes, options = {}) {
  const declared = String(options.mimeType || '').toLowerCase().split(';')[0].trim();
  const named = fromName(options.name) || fromName(filePath);
  const hint = options.type || (named || aliases[declared] || declared).split('/')[0];
  const mime = sniff(bytes, hint) || named || aliases[declared] || declared;
  if (!supported.has(mime)) throw Error('无法识别素材格式，请选择常见图片、音频或视频文件');
  if (options.type && mime.split('/')[0] !== options.type) throw Error('所选文件类型不符，请重新选择' + ({image:'图片',video:'视频',audio:'音频'}[options.type] || '素材'));
  return mime;
}

module.exports = { mimeType, audioExtensions:Object.keys(extensions).filter(ext => extensions[ext].startsWith('audio/')) };
