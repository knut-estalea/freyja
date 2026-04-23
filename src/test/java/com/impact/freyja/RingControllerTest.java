package com.impact.freyja;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void addListLocateAndRemoveNode() throws Exception {
        mockMvc.perform(post("/ring/nodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"n1","host":"127.0.0.1","port":9001}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("n1"));

        mockMvc.perform(get("/ring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(1));

        mockMvc.perform(get("/ring/locate").param("key", "order:7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary.id").value("n1"))
                .andExpect(jsonPath("$.preferenceList.length()").value(1));

        mockMvc.perform(delete("/ring/nodes/n1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("n1"));
    }
}

