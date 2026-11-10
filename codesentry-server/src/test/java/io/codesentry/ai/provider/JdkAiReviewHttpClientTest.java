package io.codesentry.ai.provider;

import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdkAiReviewHttpClientTest {
    @SuppressWarnings("unchecked")
    HttpResponse<String> response(int status,String retryAfter) {
        HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn("{}");
        when(response.headers()).thenReturn(HttpHeaders.of(retryAfter==null?Map.of():Map.of("Retry-After",List.of(retryAfter)),(a,b)->true));return response;
    }
    @Test void throttlingAndTransientFailuresBackOffBeforeRetrying() throws Exception {
        HttpClient http=mock(HttpClient.class);List<Long> delays=new ArrayList<>();
        HttpResponse<String> throttled=response(429,"3"), unavailable=response(503,null), ok=response(200,null);
        when(http.send(any(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(throttled,unavailable,ok);
        assertEquals("{}",new JdkAiReviewHttpClient(http,delays::add).postJson("http://mock/v1","test-key","{}",10));
        assertEquals(List.of(3000L,2000L),delays);verify(http,times(3)).send(any(),any());
    }
    @Test void authenticationFailureIsNotRetried() throws Exception {
        HttpClient http=mock(HttpClient.class); HttpResponse<String> unauthorized=response(401,null);
        when(http.send(any(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(unauthorized);
        assertThrows(IllegalStateException.class,()->new JdkAiReviewHttpClient(http,delay->fail("must not retry")).postJson("http://mock/v1","test-key","{}",10));
        verify(http,times(1)).send(any(),any());
    }
    @Test void cancellationDuringBackoffStopsRequestsAndPreservesInterrupt() throws Exception {
        HttpClient http=mock(HttpClient.class); HttpResponse<String> throttled=response(429,null);
        when(http.send(any(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(throttled);
        try {
            assertThrows(IllegalStateException.class,()->new JdkAiReviewHttpClient(http,delay->{throw new InterruptedException();}).postJson("http://mock/v1","test-key","{}",10));
            assertTrue(Thread.currentThread().isInterrupted());verify(http,times(1)).send(any(),any());
        }finally{Thread.interrupted();}
    }
}
