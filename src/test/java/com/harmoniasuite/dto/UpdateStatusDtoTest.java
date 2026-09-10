package com.harmoniasuite.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.config.JacksonConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateStatusDtoTest {

    @Test
    @DisplayName("update status uses the existing snake case wire contract")
    void serializesExistingWireContract() throws Exception {
        ObjectMapper mapper = new JacksonConfig().objectMapper();
        UpdateStatusDto status = new UpdateStatusDto(
                "1.0.0", true, "jar", null, "abc1234", "def5678", 2,
                List.of("Fix one"), true, UpdateState.UP_TO_DATE, null);

        String json = mapper.writeValueAsString(status);

        assertTrue(json.contains("\"current_sha\""));
        assertTrue(json.contains("\"latest_sha\""));
        assertTrue(json.contains("\"behind_by\""));
        assertTrue(json.contains("\"update_available\""));
        assertTrue(json.contains("\"state\" : \"up_to_date\""));
        assertFalse(json.contains("\"needsToolchain\""));
        assertFalse(json.contains("\"reason\""));
    }
}
