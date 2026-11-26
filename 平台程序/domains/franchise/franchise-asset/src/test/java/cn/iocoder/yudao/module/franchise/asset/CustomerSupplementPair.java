package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.test.FixedCurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Serves the paired page's customer endpoints, signed-in profile and protected file bytes. */
final class CustomerSupplementPair {
    private CustomerSupplementPair() {}

    static JsonNode run(String client,Map<String,Object> fixture,Map<String,PostSnapshot> actors,
            FixedCurrentPost posts,LocalAssetService assets,MockMvc mvc,ObjectMapper json) throws Exception {
        // Passing JSON on the Windows command line strips quotes before Node reads argv.
        var process = new ProcessBuilder("node", "-e",
                "process.argv=[process.execPath,process.env.PAIRED_CUSTOMER_CLIENT,process.env.PAIRED_CUSTOMER_FIXTURE];"
                        + "require(process.env.PAIRED_CUSTOMER_CLIENT)");
        process.environment().put("PAIRED_CUSTOMER_CLIENT", java.nio.file.Path.of(client).toAbsolutePath().toString());
        process.environment().put("PAIRED_CUSTOMER_FIXTURE", json.writeValueAsString(fixture));
        var child = process.redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var io=Executors.newSingleThreadExecutor();
        try(var incoming=child.inputReader(StandardCharsets.UTF_8);var outgoing=child.outputWriter(StandardCharsets.UTF_8)) {
            try {
                for(int i=0;i<120;i++) {
                    String line=io.submit((Callable<String>)incoming::readLine).get(25,TimeUnit.SECONDS);
                    JsonNode request=json.readTree(Objects.requireNonNull(line,"八步配对页面提前退出"));
                    if(request.path("done").asBoolean()) {
                        assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();
                        return request;
                    }
                    var actor=Objects.requireNonNull(actors.get(request.path("actor").asText()));
                    posts.use(actor);
                    String response;
                    try {
                        String method=request.path("method").asText();
                        assertThat(method).isIn("GET","POST");
                        URI uri=URI.create(request.path("path").asText().replaceFirst("^/admin-api",""));
                        if(uri.getPath().matches("/franchise/assets/[0-9]+/content")) {
                            assertThat(method).isEqualTo("GET");assertThat(uri.getQuery()).startsWith("token=");
                            long id=Long.parseLong(uri.getPath().split("/")[3]);
                            var content=assets.readDownload(new AssetAccessPort.Actor(actor.orgId(),actor.orgId()+1_000_000L,actor.userId(),actor.postId()),
                                    id,uri.getQuery().substring(6));
                            response=json.writeValueAsString(Map.of("code",0,"data",Map.of("bytesBase64",Base64.getEncoder().encodeToString(content.bytes()),"contentType",content.mimeType())));
                        } else {
                            boolean ownProfile = method.equals("GET") && uri.getPath().equals("/platform/profile/mine");
                            assertThat(ownProfile || uri.getPath().startsWith("/franchise/customers/"))
                                    .as("allowed fixture route %s %s", method, uri).isTrue();
                            var http=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),uri);
                            if (ownProfile) {
                                http.with(servletRequest -> {
                                    var login = new LoginUser().setId(actor.userId()).setUserType(UserTypeEnum.ADMIN.getValue());
                                    SecurityFrameworkUtils.setLoginUser(login, servletRequest);
                                    return servletRequest;
                                });
                            }
                            if(request.hasNonNull("body")) http.contentType("application/json").content(request.path("body").toString());
                            if(request.hasNonNull("key")) http.header("Idempotency-Key",request.path("key").asText());
                            var result=mvc.perform(http).andReturn().getResponse();
                            assertThat(result.getStatus()).as(method+" "+uri).isEqualTo(200);
                            response=result.getContentAsString(StandardCharsets.UTF_8);
                        }
                    } catch(jakarta.servlet.ServletException error) {
                        if(!(error.getCause() instanceof ServiceException denied)) throw error;
                        response=json.writeValueAsString(Map.of("code",denied.getCode(),"msg",denied.getMessage()));
                    } catch(ServiceException denied) {
                        response=json.writeValueAsString(Map.of("code",denied.getCode(),"msg",denied.getMessage()));
                    } finally {
                        posts.clear();
                        SecurityContextHolder.clearContext();
                    }
                    outgoing.write(response);outgoing.newLine();outgoing.flush();
                }
                throw new AssertionError("八步配对未返回完成结果");
            } finally {child.destroyForcibly();io.shutdownNow();}
        }
    }
}
