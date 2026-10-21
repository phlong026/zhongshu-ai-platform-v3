// One A4 page with the same high-resolution canvas used by the PNG export.
// All offsets and stream lengths are byte based, including binary JPEG data.
function pdf(jpeg,width,height){
 const ascii=s=>Uint8Array.from(Array.from(s,c=>c.charCodeAt(0))),parts=[],offsets=[0];let length=0;
 const add=b=>{parts.push(typeof b==='string'?ascii(b):b);length+=parts[parts.length-1].length;};
 const object=(n,body)=>{offsets[n]=length;add(n+' 0 obj\n'+body+'\nendobj\n');};
 add('%PDF-1.4\n');
 object(1,'<< /Type /Catalog /Pages 2 0 R >>');
 object(2,'<< /Type /Pages /Kids [3 0 R] /Count 1 >>');
 object(3,'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595.28 841.89] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>');
 offsets[4]=length;add('4 0 obj\n<< /Type /XObject /Subtype /Image /Width '+width+' /Height '+height+' /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length '+jpeg.byteLength+' >>\nstream\n');add(new Uint8Array(jpeg));add('\nendstream\nendobj\n');
 const content='q\n595.28 0 0 841.89 0 0 cm\n/Im0 Do\nQ\n';object(5,'<< /Length '+content.length+' >>\nstream\n'+content+'endstream');
 const xref=length;add('xref\n0 6\n0000000000 65535 f \n');for(let i=1;i<=5;i++)add(String(offsets[i]).padStart(10,'0')+' 00000 n \n');add('trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n'+xref+'\n%%EOF\n');
 const bytes=new Uint8Array(length);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}return bytes.buffer;
}
module.exports={pdf};
