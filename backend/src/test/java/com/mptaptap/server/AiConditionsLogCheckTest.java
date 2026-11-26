package com.mptaptap.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** AI가 직접 낸 조건을 사전·검증이 바꾸기 전 값으로 남겨, 어느 쪽이 조건을 만들었는지 로그로 가린다. */
class AiConditionsLogCheckTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void firstPlanLogsTheModelsOwnConditionsWithoutArtistNamesOrPrompt() throws Exception {
        AtomicReference<Object> payload=new AtomicReference<>();
        HttpServer ai=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ai.createContext("/responses",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] body=json.writeValueAsBytes(Map.of("status","completed","output",List.of(Map.of("type","message",
                "content",List.of(Map.of("type","output_text","text",json.writeValueAsString(payload.get())))))));
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        ai.start();
        Logger logger=(Logger)LoggerFactory.getLogger(OpenAi.class);
        // 다른 검사의 Spring 설정이 같은 JVM에서 로그 수준을 WARN으로 남길 수 있어 이 검사 동안만 INFO로 둔다.
        ch.qos.logback.classic.Level level=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.INFO);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            OpenAi api=new OpenAi(json,"check-only","check-model","http://127.0.0.1:"+ai.getAddress().getPort()+"/responses");
            // calm 은 tags 와 excludedTags 에 함께 있어 검증에서 tags 쪽이 지워진다. 원본 로그에는 남아야 한다.
            payload.set(Map.of("tags",List.of("ballad","calm","genre:korean_1990s"),"excludedTags",List.of("calm"),"inferredTags",List.of("calm"),
                "language","ko","languageSpecified",true,"excludedArtists",List.of("Secret Singer"),"candidates",List.of()));
            OpenAi.Plan plan=api.plan("비밀 문장 1990년대 발라드",List.of(),null,Map.of(),Duration.ofSeconds(5));
            assertFalse(plan.conditions().tags.contains("calm"),"Validation still removes a tag the request excludes");
            assertEquals(List.of("calm"),plan.keptExclusions(),"The tag the model wanted but the exclusion kept out is reported for the notice");
            List<String> lines=logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(line->line.startsWith("AI conditions:")).toList();
            assertEquals(List.of("AI conditions: tags=[ballad, calm, genre:korean_1990s], excludedTags=[calm], inferredTags=[calm], language=ko, languageSpecified=true, excludedArtists=1"),lines);
            assertFalse(lines.get(0).contains("Secret Singer"));assertFalse(lines.get(0).contains("비밀 문장"));

            logs.list.clear();
            api.supplement("비밀 문장",List.of(),plan.conditions(),Map.of(),List.of(new OpenAi.Suggestion("Song","Artist",List.of(),"ko")),Duration.ofSeconds(5));
            assertTrue(logs.list.stream().noneMatch(event->event.getFormattedMessage().startsWith("AI conditions:")),"Supplementary conditions are not used, so they are not logged");

            logs.list.clear();
            payload.set(Map.of("tags",List.of("not a tag"),"excludedTags",List.of(),"language","ko","languageSpecified",true,"excludedArtists",List.of(),"candidates",List.of()));
            assertThrows(Exception.class,()->api.plan("발라드",List.of(),null,Map.of(),Duration.ofSeconds(5)));
            assertTrue(logs.list.stream().noneMatch(event->event.getFormattedMessage().startsWith("AI conditions:")),"Conditions that fail validation are not logged");
        }finally{logger.detachAppender(logs);logger.setLevel(level);ai.stop(0);}
    }

    @Test void valuesOutsideTheVocabularyAreMaskedSoTheyCannotForgeLogLines() {
        assertEquals("AI conditions: tags=[jazz, <invalid>], excludedTags=[], inferredTags=[], language=<invalid>, languageSpecified=false, excludedArtists=0",
            OpenAi.rawConditionsLog(List.of("jazz","x\nAI conditions: forged"),List.of(),List.of(),"ko\nforged",false,0));
    }
}
