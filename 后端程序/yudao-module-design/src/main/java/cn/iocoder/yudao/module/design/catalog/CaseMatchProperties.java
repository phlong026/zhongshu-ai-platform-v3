package cn.iocoder.yudao.module.design.catalog;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** T26-07: precise dimensions remain disabled until business owners confirm the tolerance. */
@Data
@Component
@ConfigurationProperties(prefix = "zhongshu.design.case-match")
public class CaseMatchProperties {
    private boolean exactDimensionsEnabled = false;
    private BigDecimal dimensionToleranceMeters;
    private boolean strictFloorCount = false;
    private String policyVersion = "T26-07-v1";

    public boolean hasConfirmedTolerance() {
        return exactDimensionsEnabled && dimensionToleranceMeters != null
                && dimensionToleranceMeters.scale() >= -3 && dimensionToleranceMeters.scale() <= 6
                && dimensionToleranceMeters.signum() >= 0;
    }
}
