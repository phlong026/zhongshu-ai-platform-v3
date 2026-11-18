package cn.iocoder.yudao.module.franchise.engineering.content;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
class EngProgressTest {
    @Test void signedHlsQueryRemainsExactUnderHttpXssDeserialization() throws Exception {
        var module=new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addDeserializer(String.class,new cn.iocoder.yudao.framework.xss.core.json.XssStringJsonDeserializer(
            new cn.iocoder.yudao.framework.xss.config.XssProperties(),new org.springframework.util.AntPathMatcher(),new cn.iocoder.yudao.framework.xss.core.clean.JsoupXssCleaner()));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(module);
        String url="https://camera.example.com/live.m3u8?token=abc&expires=123&sign=xyz";
        var body=mapper.readValue("{\"cameraId\":1,\"name\":\"东侧施工区\",\"location\":\"入口立杆\",\"url\":\""+url+"\"}",cn.iocoder.yudao.module.franchise.engineering.controller.admin.EngSiteControlController.CameraBody.class);
        assertThat(body.url()).isEqualTo(url);EngCameraService.validateUrl(body.url());
    }
    @Test void dayBoundaryEarlyAndLateCompletion(){
        var day=LocalDate.of(2026,11,18);var n=new FrEngPlanNodeDO().setStatus("PENDING").setEndDate(day);
        assertThat(EngProgress.deviationDays(n,day)).isZero();assertThat(EngProgress.deviationDays(n,day.plusDays(1))).isEqualTo(1);
        n.setStatus("DONE").setCompletedAt(day.minusDays(2));assertThat(EngProgress.deviationDays(n,day.plusYears(1))).isEqualTo(-2);
        n.setCompletedAt(day.plusDays(3));assertThat(EngProgress.deviationDays(n,day)).isEqualTo(3);assertThat(EngProgress.overdue(n,day)).isFalse();
    }
    @Test void baselineWinsAndMissingPlanIsUnknown(){
        var day=LocalDate.of(2026,11,18);var n=new FrEngPlanNodeDO().setStatus("PENDING");assertThat(EngProgress.deviationDays(n,day)).isNull();
        n.setEndDate(day.plusDays(5)).setBaselineEndDate(day.minusDays(4));assertThat(EngProgress.deviationDays(n,day)).isEqualTo(4);
    }
    @Test void shanghaiMidnightOnUtcServer() {
        var midnight=java.time.Instant.parse("2026-10-31T16:00:00Z");
        assertThat(EngProgress.businessDate(java.time.Clock.fixed(midnight,java.time.ZoneOffset.UTC))).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(EngProgress.businessDate(java.time.Clock.fixed(midnight.minusSeconds(1),java.time.ZoneOffset.UTC))).isEqualTo(LocalDate.of(2026,10,31));
    }
    @Test void genericCameraUrlValidation(){
        EngCameraService.validateUrl("");EngCameraService.validateUrl("https://camera.example.com/live.m3u8?token=123");
        for(String url:java.util.List.of("rtsp://camera/live","http://camera/live.m3u8","https://user:pass@camera/live.m3u8","https://camera/live.mp4","javascript:alert(1)"))assertThatThrownBy(()->EngCameraService.validateUrl(url)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
}
