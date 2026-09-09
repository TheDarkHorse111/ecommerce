package com.thedarkhorse.config.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MonitorSignatureFilterTest {

    private static final String SECRET = "shhh";
    private static final String HEADER = "X-Hub-Signature-256";
    private static final String ALGORITHM = "HmacSHA256";
    private static final String BODY = "{\"commits\":[{\"modified\":[\"catalog-service-local.yaml\"]}]}";
    private static final String WRONG =
            "sha256=0000000000000000000000000000000000000000000000000000000000000000";

    private final MonitorSignatureFilter filter = new MonitorSignatureFilter(SECRET);

    @Test
    void rejectsARequestWithNoSignature() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsARequestWhoseSignatureDoesNotMatchTheBody() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(HEADER, WRONG);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesARequestWhoseSignatureMatchesTheBodyAndLeavesTheBodyReadable() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(HEADER, "sha256=" + hmac());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(BODY);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/monitor");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private String hmac() throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        return HexFormat.of().formatHex(mac.doFinal(BODY.getBytes(StandardCharsets.UTF_8)));
    }
}
