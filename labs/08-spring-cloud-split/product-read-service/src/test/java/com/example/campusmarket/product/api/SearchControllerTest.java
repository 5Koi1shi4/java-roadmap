package com.example.campusmarket.product.api;

import com.example.campusmarket.product.search.ProductSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchControllerTest {

    private ProductSearchPort search;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        search = mock(ProductSearchPort.class);
        mvc = MockMvcBuilders.standaloneSetup(new SearchController(search)).build();
    }

    @Test
    void legacySearchPathReturnsSearchPageWithUtf8Json() throws Exception {
        when(search.search(any())).thenReturn(page());

        var result = mvc.perform(get("/api/search")
                        .param("keyword", "数学书")
                        .param("category", "教材")
                        .param("minPriceFen", "100")
                        .param("maxPriceFen", "200")
                        .param("size", "2")
                        .param("searchAfter", "cursor"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.items[0].listingId").value("listing-1"))
                .andExpect(jsonPath("$.items[0].title").value("数学书"))
                .andExpect(jsonPath("$.nextSearchAfter").value("next"))
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("charset=UTF-8");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("数学书");
    }

    @Test
    void listingsSearchAliasRemainsAvailable() throws Exception {
        when(search.search(any())).thenReturn(page());

        mvc.perform(get("/api/listings/search").param("keyword", "教材"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void invalidSearchParametersReturnUtf8ChineseError() throws Exception {
        var result = mvc.perform(get("/api/search").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("搜索参数无效"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("charset=UTF-8");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("搜索参数无效");
        verifyNoInteractions(search);
    }

    @Test
    void malformedScalarParameterAlsoReturnsChineseBadRequest() throws Exception {
        mvc.perform(get("/api/search").param("minPriceFen", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("搜索参数无效"));
        verifyNoInteractions(search);
    }

    @Test
    void searchDependencyFailureReturnsOwnUnavailableContract() throws Exception {
        doThrow(new ProductSearchPort.SearchUnavailableException("backend unavailable"))
                .when(search).search(any());

        var result = mvc.perform(get("/api/search").param("keyword", "教材"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("搜索服务暂时不可用"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("backend unavailable");
        verify(search).search(any());
    }

    private static ProductSearchPort.SearchPage page() {
        return new ProductSearchPort.SearchPage(
                List.of(new ProductSearchPort.SearchItem(
                        "listing-1", "数学书", "九成新", "教材", 1200, 2, "ON_SALE", 3)),
                1,
                "next");
    }
}
