package cn.iocoder.yudao.module.franchise.api;
import cn.iocoder.yudao.module.franchise.api.capability.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DesignFlowCallContractTest {
    private DesignFlowCall call(String method,String path){return new DesignFlowCall(method,path,Map.of(),Map.of("count",2),"stable");}
    @Test void paidRoutesCannotBeDisguisedAsUnpaidReadsOrWrites(){
        assertEquals(DesignCapabilityContract.Operation.FLOW_FLAT_JOB,call("POST","/design-projects/12/flat-jobs").operation());
        assertEquals(DesignCapabilityContract.Operation.FLOW_ELEVATION_JOB,call("POST","/design-projects/12/elevation-jobs").operation());
        assertEquals(DesignCapabilityContract.Operation.FLOW_REVISION,call("POST","/design-projects/12/revision-requests").operation());
        assertEquals(DesignCapabilityContract.Operation.FLOW_BUDGET,call("POST","/budget-drafts/12/estimates").operation());
        assertEquals(DesignCapabilityContract.Operation.FLOW_BUDGET,call("POST","/design-projects/12/budget-estimates/itemized").operation());
        assertThrows(IllegalArgumentException.class,()->call("GET","/design-projects/12/flat-jobs").operation());
    }
    @Test void onlyOriginalResourcePathsAndMethodsAreAllowed(){
        for(String value:List.of("https://example.org","//host/secret","/../budget","/budget?secret=x","/admin/budget","/point-ledger","/cases/1/generation-reference"))assertThrows(IllegalArgumentException.class,()->call("POST",value).operation());
        assertEquals(DesignCapabilityContract.Operation.FLOW_WRITE,call("POST","/budget/my-regions/MYHOME/prices:copy").operation());
        assertEquals(DesignCapabilityContract.Operation.FLOW_READ,call("GET","/budget-estimates/12/quotes/3").operation());
        assertEquals(DesignCapabilityContract.Operation.FLOW_WRITE,new DesignFlowCall("GET","/design-projects/12",Map.of("jobId","11"),Map.of(),"k").operation());
    }
    @Test void copiesInputsAndAllowsOnlyTheOriginalImageCounts(){
        var data=new LinkedHashMap<String,Object>();data.put("count",2);var call=new DesignFlowCall("POST","/design-projects/12/flat-jobs",Map.of(),data,"k");data.put("count",4);assertEquals(2,call.count());
        for(Object value:List.of(0,3,8,-1,2.5,"2"))assertThrows(IllegalArgumentException.class,()->new DesignFlowCall("POST","/design-projects/12/flat-jobs",Map.of(),Map.of("count",value),"k").count());
    }
}
