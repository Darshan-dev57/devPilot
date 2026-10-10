package devPilot.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import devPilot.backend.exceptions.GlobalExceptionHandler;
import devPilot.backend.services.ChatService;
import devPilot.backend.services.Visitors;

class ChatControllerValidationTest {

    private final ChatService chatService = mock(ChatService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void expectBlankQuestionRejected(String body) throws Exception {
        mvc.perform(post("/api/chat/sessions/{id}/messages", UUID.randomUUID())
                        .header(Visitors.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.ALL)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Type a question before sending."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void aBlankQuestionIsACleanBadRequest() throws Exception {
        expectBlankQuestionRejected("{\"content\":\"   \"}");
        expectBlankQuestionRejected("{\"content\":\"\"}");
        expectBlankQuestionRejected("{}");
        verify(chatService, never()).streamReply(any(), any(), any(), any());
    }

    @Test
    void aSessionWithoutARepositoryNamesWhatIsMissing() throws Exception {
        mvc.perform(post("/api/chat/sessions")
                        .header(Visitors.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pick a repository to chat with."));
        verifyNoInteractions(chatService);
    }

    @Test
    void aMalformedVisitorIdIsABadRequestThatNamesTheVisitorId() throws Exception {
        mvc.perform(post("/api/chat/sessions/{id}/messages", UUID.randomUUID())
                        .header(Visitors.HEADER, "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"How does indexing work?\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Visitors.INVALID_MESSAGE));
        verifyNoInteractions(chatService);
    }
}
