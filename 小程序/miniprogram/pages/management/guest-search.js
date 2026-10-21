// Search the public visitor examples only; never query a previous company session.
const preview=require('../../utils/management-guest');
module.exports=function(keyword=''){
 const source=preview(),query=keyword.trim().toLowerCase();
 const matches=item=>['name','status','note','label','amount'].some(key=>typeof item[key]==='string'&&item[key].toLowerCase().includes(query));
 const guestVisibleTodos=source.guestTodos.filter(matches),guestVisibleProjects=source.guestProjects.filter(matches),guestVisibleMenu=source.guestMenu.filter(matches);
 return {guestKeyword:keyword,guestHasSearch:!!query,guestVisibleTodos,guestVisibleProjects,guestVisibleMenu,guestResultCount:guestVisibleTodos.length+guestVisibleProjects.length+guestVisibleMenu.length};
};
