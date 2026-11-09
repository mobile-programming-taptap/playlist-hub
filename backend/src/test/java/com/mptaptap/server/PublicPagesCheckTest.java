package com.mptaptap.server;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class PublicPagesCheckTest {
    @Test void accountDeletionIsPublicDiscoverableAndDoesNotReceiveOrEchoCredentials() throws Exception {
        try(var context=new SpringApplicationBuilder(TapTapApplication.class).run(
                "--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:public-pages-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa","--spring.datasource.password=","--taptap.openai.key=",
                "--taptap.support-email=deletion-check@example.test","--logging.level.root=WARN","--spring.main.banner-mode=off")) {
            String base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
            var client=HttpClient.newHttpClient();
            for(String path:List.of("/","/privacy","/account-deletion")) {
                var request=HttpRequest.newBuilder(URI.create(base+path+"?token=private-query-check"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer private-header-check").GET().build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode(),path);
                assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
                assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
                assertEquals("no-referrer",response.headers().firstValue("Referrer-Policy").orElse(""));
                assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElse(""));
                assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
                assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
                assertTrue(response.body().contains("href=\"/account-deletion\""));
                assertFalse(response.body().contains("private-query-check"));
                assertFalse(response.body().contains("private-header-check"));
                assertFalse(Pattern.compile("<(?:script|iframe|form)\\b",Pattern.CASE_INSENSITIVE).matcher(response.body()).find());
            }
            var deletion=client.send(HttpRequest.newBuilder(URI.create(base+"/account-deletion")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,deletion.statusCode(),"Deletion instructions must not require an account or session.");
            assertTrue(deletion.body().contains("mailto:deletion-check@example.test?subject="));
            assertTrue(deletion.body().contains("직접 보내야 요청이 전달"));
            assertTrue(deletion.body().contains("계정 소유권을 확인"));
            assertTrue(deletion.body().contains("7일 이내"));
            assertTrue(deletion.body().contains("보관 기간"));
            assertTrue(deletion.body().contains("독립 목록"));
            assertTrue(deletion.body().contains("즉시 적용"));
        }
    }

    @Test void missingOrInjectedOperatorAddressNeverInventsAnAcceptedDeletionRequest() {
        for(String email:Arrays.asList(null,"","  ","not-an-address","bad@example.test?bcc=other@example.test",
                "bad@example.test\r\nBcc:other@example.test","<script>alert(1)</script>@example.test","a".repeat(255)+"@example.test")) {
            var api=new PublicPagesApi(email);
            for(String page:List.of(api.home().getBody(),api.privacy().getBody(),api.accountDeletion().getBody())) {
                assertFalse(page.contains("mailto:"));
                assertFalse(page.contains("<script"));
                assertFalse(page.contains("other@example.test"));
                assertTrue(page.contains("href=\"/account-deletion\""));
            }
            String deletion=api.accountDeletion().getBody();
            assertTrue(deletion.contains("삭제 요청을 접수할 수 없습니다"));
            assertFalse(deletion.contains("요청이 접수되었습니다"));
        }
    }

    @Test void validEmailPunctuationCannotBecomeMarkupOrAdditionalMailHeaders() {
        String email="help&demo+delete'?bcc=not-a-recipient@example.test";
        String page=new PublicPagesApi("  "+email+"  ").accountDeletion().getBody();
        assertTrue(page.contains("help&amp;demo+delete&#39;?bcc=not-a-recipient@example.test"));
        var matcher=Pattern.compile("href=\"(mailto:[^\"]+)\"").matcher(page);
        assertTrue(matcher.find());
        String link=matcher.group(1).replace("&amp;","&");
        var uri=URI.create(link);
        assertNull(uri.getRawFragment());
        String addressAndQuery=uri.getRawSchemeSpecificPart();
        int queryStart=addressAndQuery.indexOf('?');
        assertEquals(email,URLDecoder.decode(addressAndQuery.substring(0,queryStart),StandardCharsets.UTF_8));
        String[] parameters=addressAndQuery.substring(queryStart+1).split("&");
        assertEquals(2,parameters.length,"Only the app's subject and body may become mail headers.");
        assertEquals("subject=TapTap 계정 및 데이터 삭제 요청",URLDecoder.decode(parameters[0],StandardCharsets.UTF_8));
        String body=URLDecoder.decode(parameters[1],StandardCharsets.UTF_8);
        assertTrue(body.startsWith("body=TapTap 계정 및 관련 데이터 삭제를 요청합니다.\n"));
        assertFalse(body.contains(email));
        assertTrue(body.contains("비밀번호·인증 코드·인증 토큰은 적지 마세요"));
        assertFalse(page.contains("?bcc=not-a-recipient@example.test\""));
    }
}
