package com.ticketing.common;

import com.ticketing.dto.SystemStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void serializesChineseMessageAndTypedData() throws Exception {
        var response = ApiResponse.success(new SystemStatusResponse("票务交易平台", "RUNNING"));
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("code").asText()).isEqualTo("OK");
        assertThat(json.get("message").asText()).isEqualTo("操作成功");
        assertThat(json.at("/data/application").asText()).isEqualTo("票务交易平台");
        assertThat(json.at("/data/status").asText()).isEqualTo("RUNNING");
    }
}
