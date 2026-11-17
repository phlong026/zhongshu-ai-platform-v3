const pad = value => String(value).padStart(2, '0');
/** Business dates use Asia/Shanghai and never fall back to entry creation time. */
function day(value) {
 if (value == null || value === '') return '';
 if (Array.isArray(value)) {
  const [y,m,d]=value.map(Number), date=new Date(Date.UTC(y,m-1,d));
  return Number.isInteger(y)&&date.getUTCFullYear()===y&&date.getUTCMonth()===m-1&&date.getUTCDate()===d ? y+'-'+pad(m)+'-'+pad(d) : '';
 }
 if (typeof value==='string' && /^\d{4}-\d{2}-\d{2}(?:$|T| )/.test(value)) return day(value.slice(0,10).split('-').map(Number));
 if (typeof value==='number' || /^\d{10,13}$/.test(String(value))) {
  const number=Number(value),ms=number<1e12?number*1000:number,date=new Date(ms+8*3600000);
  return Number.isFinite(date.getTime())?date.toISOString().slice(0,10):'';
 }
 return '';
}
module.exports={day};
