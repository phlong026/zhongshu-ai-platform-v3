const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'..'),out=path.join(root,'qa/engineering-native-20261021'),project=path.join(out,'project');
fs.mkdirSync(project,{recursive:true});
fs.cpSync(path.join(root,'miniprogram'),path.join(project,'miniprogram'),{recursive:true});
const config=JSON.parse(fs.readFileSync(path.join(root,'project.config.json'),'utf8'));
config.appid='touristappid';config.projectname='engineering-native-delivery-20261021';
fs.writeFileSync(path.join(project,'project.config.json'),JSON.stringify(config,null,2));
const files=[];
function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const file=path.join(dir,e.name);if(e.isDirectory())walk(file);else files.push({path:path.relative(root,file).replace(/\\/g,'/'),sha256:crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')});}}
walk(path.join(root,'miniprogram'));
fs.writeFileSync(path.join(out,'prepared-source.json'),JSON.stringify({project,files},null,2));
console.log('Prepared isolated native project: '+project);
console.log('Open this project with the official WeChat CLI auto command on port 9894, then run npm run check:engineering:record-flow:native.');
