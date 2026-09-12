package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.application.AuthService;
import com.example.campusmarket.identity.application.EmailVerificationService;
import com.example.campusmarket.identity.infrastructure.DeviceCookieSigner;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 严格 Jackson 与 Bean Validation 在 HTTP 边界拒绝非法身份请求。 */
class StrictAuthJsonIT {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false);
        mapper.coercionConfigFor(LogicalType.Textual)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = standaloneSetup(new AuthController(mock(EmailVerificationService.class), mock(AuthService.class),
                mock(DeviceCookieSigner.class)))
            .setControllerAdvice(new IdentityApiExceptionHandler())
            .setValidator(validator)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }

    @Test
    void rejectsUnknownJsonField() throws Exception {
        assertThat(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"student@stu.example.edu.cn\",\"password\":\"correct horse battery staple\",\"extra\":true}"))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentType())
            .contains("application/json");
    }

    @Test
    void rejectsWrongJsonTypeAndMissingEmail() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":123,\"password\":\"correct horse battery staple\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"correct horse battery staple\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingRegisterPasswordOrCodeAndIllegalPurpose() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@stu.example.edu.cn\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/email-verifications").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@stu.example.edu.cn\",\"purpose\":\"UNKNOWN\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOutOfBoundsPassword() throws Exception {
        String tooLong = "x".repeat(129);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@stu.example.edu.cn\",\"password\":\"" + tooLong + "\"}"))
            .andExpect(status().isBadRequest());
    }
}
