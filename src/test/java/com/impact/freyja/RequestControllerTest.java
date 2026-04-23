package com.impact.freyja;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end happy-path test for the public /request endpoint. With only the
 * self-registered node in the ring, every request is owned locally so no
 * inter-node call is exercised here — that's covered indirectly by
 * RemoteClassifier and the routing logic.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void firstRequestIsNewSecondIsDuplicate() throws Exception {
        mockMvc.perform(get("/request").param("session", "abc").param("program", "614"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("614|abc"))
                .andExpect(jsonPath("$.classification").value("NEW"));

        mockMvc.perform(get("/request").param("session", "abc").param("program", "614"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").value("DUPLICATE"));
    }

    @Test
    void differentSessionsAreIndependent() throws Exception {
        mockMvc.perform(get("/request").param("session", "alice").param("program", "1"))
                .andExpect(jsonPath("$.classification").value("NEW"));
        mockMvc.perform(get("/request").param("session", "bob").param("program", "1"))
                .andExpect(jsonPath("$.classification").value("NEW"));
    }

    @Test
    void failNodeReturnsAliveFalse() throws Exception {
        String body = mockMvc.perform(post("/ring/nodes")
                        .contentType("application/json")
                        .content("{\"id\":\"victim\",\"host\":\"127.0.0.1\",\"port\":19999}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assert body.contains("\"alive\":true");

        mockMvc.perform(post("/ring/nodes/victim/fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alive").value(false));

        mockMvc.perform(post("/ring/nodes/victim/recover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alive").value(true));
    }
}
