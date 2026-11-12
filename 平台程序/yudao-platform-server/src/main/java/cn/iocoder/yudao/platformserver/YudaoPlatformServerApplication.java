package cn.iocoder.yudao.platformserver;

import cn.iocoder.yudao.platformmigration.PlatformAdminBootstrapConfiguration;
import cn.iocoder.yudao.platformmigration.PlatformMigrationConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

@SpringBootApplication(scanBasePackages = {
        "cn.iocoder.yudao.platformserver", "cn.iocoder.yudao.module"
})
@EnableScheduling
public class YudaoPlatformServerApplication {

    public static void main(String[] args) {
        if (args.length > 0 && ("migrate".equals(args[0]) || "bootstrap-admin".equals(args[0]))) {
            // A separate source prevents accidental construction of business beans and scheduled workers.
            Class<?> source = "migrate".equals(args[0])
                    ? PlatformMigrationConfiguration.class : PlatformAdminBootstrapConfiguration.class;
            SpringApplication migration = new SpringApplication(source);
            migration.setWebApplicationType(WebApplicationType.NONE);
            migration.setAdditionalProfiles("migration");
            try (var context = migration.run(Arrays.copyOfRange(args, 1, args.length))) {
                // ApplicationRunner completed the migration before run() returned.
            }
            return;
        }
        SpringApplication.run(YudaoPlatformServerApplication.class, args);
    }
}
