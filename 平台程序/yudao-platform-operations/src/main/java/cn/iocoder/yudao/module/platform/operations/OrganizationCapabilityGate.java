package cn.iocoder.yudao.module.platform.operations;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 统一拦截计费端口，含免费/缓存准入；不包围settle/release，不改写现有计费实现。 */
@Aspect
@Component
@ConditionalOnProperty(name="platform.legacy-design-bridge.enabled",havingValue="false")
public class OrganizationCapabilityGate {
    private final OrganizationCapabilities capabilities;
    private final TransactionTemplate tx;
    public OrganizationCapabilityGate(OrganizationCapabilities capabilities,PlatformTransactionManager manager) {
        this.capabilities=capabilities;this.tx=new TransactionTemplate(manager);
    }
    @Around("execution(* cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointService.reserve(..)) || execution(* cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointService.reserveConfirmed(..)) || execution(* cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointService.assertSubjectAllowed(..))")
    public Object gate(ProceedingJoinPoint call) throws Throwable {
        Object[] args=call.getArgs();
        try {
            return tx.execute(status->{
                if(args[0] instanceof OrgPointPort.ReserveCommand command)
                    capabilities.assertEnabled(command.orgId(),command.subject(),command.bizKey());
                else capabilities.assertEnabled(((Number)args[0]).longValue(),(String)args[2],null);
                try{return call.proceed();}
                catch(RuntimeException|Error e){throw e;}
                catch(Throwable e){throw new InvocationFailure(e);}
            });
        }catch(InvocationFailure e){throw e.getCause();}
    }
    private static final class InvocationFailure extends RuntimeException {
        InvocationFailure(Throwable cause){super(cause);}
    }
}
