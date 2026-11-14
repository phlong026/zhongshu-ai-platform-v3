package cn.iocoder.yudao.module.franchise.design.remote;

import cn.iocoder.yudao.module.franchise.design.remote.transport.DesignCapabilityTransport;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "zhongshu.design.remote.enabled", havingValue = "true")
public class RemoteDesignConfiguration {
    @Bean
    RemoteDesignCommandStore remoteDesignCommandStore(DataSource source, PlatformTransactionManager transactions,
                                                     ObjectMapper mapper, FranchiseCommands commands, RemoteDesignChargePort charges) {
        charges.requireReady();
        return new RemoteDesignCommandStore(source, transactions, mapper, commands, charges);
    }

    @Bean
    RemoteDesignGateway remoteDesignGateway(@Value("${zhongshu.design.remote.caller-app-id}") String caller,
                                           DesignCapabilityTransport transport, RemoteDesignCommandStore commands,
                                           ObjectMapper mapper,
                                           @Value("${zhongshu.design.remote.max-in-flight:16}") int globalLimit,
                                           @Value("${zhongshu.design.remote.max-in-flight-per-org:4}") int organizationLimit) {
        return new RemoteDesignGateway(caller, transport, commands, mapper,
                new RemoteDesignAdmission(globalLimit, organizationLimit));
    }
    @Bean
    ReferenceMirrorStore referenceMirrorStore(DataSource source, PlatformTransactionManager transactions) {
        return new ReferenceMirrorStore(source, transactions);
    }

    @Bean
    ReferenceByteUploader referenceByteUploader(@Value("${zhongshu.design.remote.reference-upload-hosts:}") String hosts) {
        var allowed = java.util.Arrays.stream(hosts.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        return new ReferenceByteUploader(allowed);
    }

    @Bean
    RemoteReferenceReader remoteReferenceReader(@Value("${zhongshu.design.remote.caller-app-id}") String caller,
            RemoteDesignGateway gateway, ReferenceMirrorStore mirrors, ReferenceByteUploader uploader) {
        return new RemoteReferenceReader(caller, gateway, mirrors, uploader);
    }
}
