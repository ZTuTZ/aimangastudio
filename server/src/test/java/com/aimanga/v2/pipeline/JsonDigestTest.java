package com.aimanga.v2.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonDigestTest {

    @Test
    void objectKeyNormalizationDoesNotChangeSnapshotDigest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var original = mapper.readTree("{\"z\":1,\"nested\":{\"b\":2,\"a\":1},\"a\":[2,1]}");
        var normalized = mapper.readTree("{\"a\":[2,1],\"nested\":{\"a\":1,\"b\":2},\"z\":1}");

        assertThat(JsonDigest.sha256(original)).isEqualTo(JsonDigest.sha256(normalized));
    }
}
