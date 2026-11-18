package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import java.util.*;
import java.util.stream.Collectors;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.SUBMISSION_INPUT_INVALID;

/** Versioned client requirements. Customer and budget facts are always server snapshots. */
public final class CommissionBrief {
    private static final Set<String> FIELDS = Set.of("projectName","orientation","entranceAligned","entranceDirection","householdMembers","householdTags","rooms","bedrooms","guestBedrooms","bathroomFirst","bathroomSecond","livingUses","diningRound","diningLong","kitchenTypes","specialRooms","specialNeeds","painPoints","painOther","expectations","styleRefs","styleNotes");
    private static final Set<String> LIST_FIELDS=Set.of("householdTags","rooms","livingUses","kitchenTypes","specialRooms","painPoints","expectations","styleRefs");
    public static Map<String,Object> validate(Map<String,Object> raw) {
        if(raw==null)return null; // Historical clients retain the existing free-text contract.
        Map<String,Object> value=new LinkedHashMap<>();value.put("schemaVersion",1);
        for(var entry:raw.entrySet())if(FIELDS.contains(entry.getKey())){
            Object item=entry.getValue();
            if(LIST_FIELDS.contains(entry.getKey())&&item instanceof List<?> list){
                if(list.size()>20||list.stream().anyMatch(x->!(x instanceof String)||((String)x).length()>40))throw exception(SUBMISSION_INPUT_INVALID);
                value.put(entry.getKey(),List.copyOf(list));
            }else if(!LIST_FIELDS.contains(entry.getKey())&&item instanceof String text&&text.length()<=400)value.put(entry.getKey(),text.trim());
            else if(item!=null)throw exception(SUBMISSION_INPUT_INVALID);
        }
        String project=String.valueOf(value.getOrDefault("projectName",""));
        if(project.isBlank()||project.length()>80||!Set.of("东","南","西","北","东南","西南","东北","西北").contains(Objects.toString(value.get("orientation"),"")))throw exception(SUBMISSION_INPUT_INVALID);
        String population=String.valueOf(value.getOrDefault("householdMembers",""));
        if(!population.matches("[1-9][0-9]?")||!(value.get("rooms") instanceof List<?> rooms)||rooms.isEmpty())throw exception(SUBMISSION_INPUT_INVALID);
        if(!Set.of("一致","不一致").contains(Objects.toString(value.get("entranceAligned"),"")))throw exception(SUBMISSION_INPUT_INVALID);
        if("不一致".equals(value.get("entranceAligned"))&&String.valueOf(value.getOrDefault("entranceDirection","")).isBlank())throw exception(SUBMISSION_INPUT_INVALID);
        for(String key:List.of("bedrooms","guestBedrooms","bathroomFirst","bathroomSecond","diningRound","diningLong"))if(value.containsKey(key)&&!String.valueOf(value.get(key)).isBlank()&&!String.valueOf(value.get(key)).matches("[0-9]{1,2}"))throw exception(SUBMISSION_INPUT_INVALID);
        return Collections.unmodifiableMap(value);
    }
    public static String forDesign(String snapshot){
        if(snapshot==null)return null;
        try{
            Map<String,Object> data=cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(snapshot,Map.class);
            return cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(redact(data));
        }catch(Exception e){return null;}
    }
    private static Object redact(Object node){
        if(node instanceof Map<?,?> map){
            var out=new LinkedHashMap<String,Object>();
            for(var entry:map.entrySet()){
                String key=String.valueOf(entry.getKey());
                if(Set.of("phone","wechat","recipient","rateCents","totalCents","totalLowCents","totalHighCents","firstCents","tailCents","amountCents","pricedSubtotalCents","categoryTotals","priceMicros").contains(key))continue;
                out.put(key,redact(entry.getValue()));
            }
            return out;
        }
        if(node instanceof List<?> list)return list.stream().map(CommissionBrief::redact).toList();
        return node;
    }
    public static Map<String,Object> customerInfo(CustomerDO c) {
        if(c==null)return Map.of();
        String address=java.util.stream.Stream.of(c.getProvince(),c.getCity(),c.getDistrict(),c.getTown(),c.getVillage(),c.getAddressDetail()).filter(s->s!=null&&!s.isBlank()).collect(Collectors.joining(" "));
        return Map.of("id",String.valueOf(c.getId()),"name",Objects.toString(c.getName(),""),"phone",Objects.toString(c.getPhone(),""),"address",address);
    }
    private CommissionBrief() { }
}
