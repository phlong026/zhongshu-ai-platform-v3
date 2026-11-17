// FIPS 180-4，分块更新；不复制整份媒体文件。
const K=[
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
];
const rotr=(x,n)=>(x>>>n)|(x<<(32-n));
function bytesOf(value){if(ArrayBuffer.isView(value))return new Uint8Array(value.buffer,value.byteOffset,value.byteLength);try{ArrayBuffer.prototype.slice.call(value,0,0);return new Uint8Array(value);}catch(e){throw Error('无法读取素材二进制数据，请重新选择');}}
class Sha256{
constructor(){this.H=[
0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19
];this.pending=new Uint8Array(64);this.used=0;this.length=0;this.result='';}
block(bytes,offset){const H=this.H;let i;
var w=[];
for(i=0;i<16;i++){
w[i]=(bytes[offset+i * 4]<<24)|(bytes[offset+i * 4+1]<<16)
|(bytes[offset+i * 4+2]<<8)|bytes[offset+i * 4+3];
}
for(i=16;i<64;i++){
var s0=rotr(w[i - 15],7)^rotr(w[i - 15],18)^(w[i - 15]>>>3);
var s1=rotr(w[i - 2],17)^rotr(w[i - 2],19)^(w[i - 2]>>>10);
w[i]=(w[i - 16]+s0+w[i - 7]+s1)|0;
}
var a=H[0],b=H[1],c=H[2],d=H[3],e=H[4],f=H[5],g=H[6],h=H[7];
for(i=0;i<64;i++){
var S1=rotr(e,6)^rotr(e,11)^rotr(e,25);
var ch=(e&f)^(~e&g);
var temp1=(h+S1+ch+K[i]+w[i])|0;
var S0=rotr(a,2)^rotr(a,13)^rotr(a,22);
var maj=(a&b)^(a&c)^(b&c);
var temp2=(S0+maj)|0;
h=g;g=f;f=e;
e=(d+temp1)|0;
d=c;c=b;b=a;
a=(temp1+temp2)|0;
}
H[0]=(H[0]+a)|0;H[1]=(H[1]+b)|0;H[2]=(H[2]+c)|0;H[3]=(H[3]+d)|0;
H[4]=(H[4]+e)|0;H[5]=(H[5]+f)|0;H[6]=(H[6]+g)|0;H[7]=(H[7]+h)|0;

}
update(value){if(this.result)throw Error('摘要已完成');const bytes=bytesOf(value);this.length+=bytes.length;let offset=0;
if(this.used){const n=Math.min(64-this.used,bytes.length);this.pending.set(bytes.subarray(0,n),this.used);this.used+=n;offset=n;if(this.used===64){this.block(this.pending,0);this.used=0;}}
for(;offset+64<=bytes.length;offset+=64)this.block(bytes,offset);
if(offset<bytes.length){this.pending.set(bytes.subarray(offset));this.used=bytes.length-offset;}return this;
}
hex(){if(this.result)return this.result;const bits=this.length*8,pad=new Uint8Array(this.used<56?64-this.used:128-this.used);pad[0]=128;for(let i=0;i<8;i++)pad[pad.length-1-i]=Math.floor(bits/Math.pow(2,8*i))&255;this.update(pad);return this.result=this.H.map(v=>('00000000'+(v>>>0).toString(16)).slice(-8)).join('');}
}
async function hashFile(fs,path,size,alive){const hash=new Sha256();for(let offset=0;offset<size;offset+=1024*1024){if(!alive())throw{cancelled:true};const length=Math.min(1024*1024,size-offset);const bytes=await new Promise((resolve,reject)=>fs.readFile({filePath:path,position:offset,length,success:r=>resolve(r.data),fail:()=>reject(Error('无法读取素材，请重新选择或压缩文件'))}));if(bytes.byteLength!==length)throw Error('素材读取不完整，请重新选择');hash.update(bytes);}if(!alive())throw{cancelled:true};return hash.hex();}
module.exports={Sha256,hashFile,bytesOf};
