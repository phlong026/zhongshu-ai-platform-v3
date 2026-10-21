// One catalog for the sales entrance, dimension search and case details.
module.exports=[
 {id:'ZS001',name:'方正雅居',image:'/pages/workflow/assets/biz-case-1.jpg',area:400,width:12.5,depth:16,floors:2,style:'新中式'},
 {id:'ZS002',name:'云庭雅筑',image:'/pages/workflow/assets/biz-case-2.jpg',area:380,width:12.4,depth:16,floors:2,style:'新中式'},
 {id:'ZS003',name:'清和别院',image:'/pages/workflow/assets/biz-case-3.jpg',area:390,width:12.5,depth:15.8,floors:2,style:'新中式'},
 {id:'ZS004',name:'山居新作',image:'/pages/workflow/assets/biz-case-4.jpg',area:360,width:12.8,depth:16,floors:2,style:'新中式'},
 {id:'ZS005',name:'田园小院',image:'/pages/workflow/assets/biz-case-3.jpg',area:150,width:12,depth:12.5,floors:1,style:'新中式'},
 {id:'ZS006',name:'现代庭居',image:'/pages/workflow/assets/biz-case-2.jpg',area:300,width:10,depth:15,floors:2,style:'现代简约'},
 {id:'ZS007',name:'三代雅宅',image:'/pages/workflow/assets/biz-case-4.jpg',area:450,width:10,depth:15,floors:3,style:'新中式'},
 {id:'ZS008',name:'光影小墅',image:'/pages/workflow/assets/biz-case-1.jpg',area:135,width:9,depth:15,floors:1,style:'现代简约'}
].map((c,index)=>({...c,versionId:'demo-'+c.id+'-v1',sample:true,floorPlans:Array.from({length:c.floors},(_,floor)=>({floor:floor+1,image:'/pages/workflow/assets/biz-plan-'+((index+floor)%4+1)+'.jpg'}))}));
