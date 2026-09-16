package com.example.campusmarket.identity.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** SMTP 依赖故障必须转为不泄密的 503 协议。 */
class SmtpFailureHttpIT {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new FailingMailEndpoint())
            .setControllerAdvice(new IdentityApiExceptionHandler())
            .build();
    }

    @Test
    void mapsMailExceptionToDependencyUnavailableWithoutLeakingDetails() throws Exception {
        mvc.perform(post("/mail").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            .andExpect(content().string(not(containsString("smtp-password"))))
            .andExpect(content().string(not(containsString("recipient@example.edu.cn"))));
    }

    @RestController
    static final class FailingMailEndpoint {
        @PostMapping("/mail")
        void send() {
            throw new MailSendException("smtp-password recipient@example.edu.cn");
        }
    }
}
