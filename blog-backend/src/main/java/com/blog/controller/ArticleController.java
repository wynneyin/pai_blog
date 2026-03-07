package com.blog.controller;

import com.blog.dto.ApiResponse;
import com.blog.dto.ArticleListResponse;
import com.blog.dto.Pagination;
import com.blog.model.Article;
import com.blog.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    @Value("${blog.admin-token:}")
    private String adminToken;

    @GetMapping("/articles")
    public ApiResponse<ArticleListResponse> getArticles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword
    ) {
        ArticleListResponse data;
        if (keyword != null && !keyword.isBlank()) {
            data = paginateSearchResult(articleService.searchArticles(keyword), page, pageSize);
        } else if (category != null && !category.isBlank()) {
            data = articleService.getArticlesByCategory(category, page, pageSize);
        } else if (tag != null && !tag.isBlank()) {
            data = articleService.getArticlesByTag(tag, page, pageSize);
        } else {
            data = articleService.getArticles(page, pageSize);
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/articles/{slug}")
    public ApiResponse<Article> getArticleBySlug(@PathVariable String slug) {
        return ApiResponse.success(articleService.getArticleBySlug(slug));
    }

    @GetMapping("/categories")
    public ApiResponse<?> getCategories() {
        return ApiResponse.success(articleService.getCategories());
    }

    @GetMapping("/tags")
    public ApiResponse<?> getTags() {
        return ApiResponse.success(articleService.getTags());
    }

    @PostMapping("/admin/cache/refresh")
    public ApiResponse<?> refreshCache(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (adminToken != null && !adminToken.isBlank() && !adminToken.equals(token)) {
            return ApiResponse.error(401, "Invalid admin token");
        }
        articleService.clearCache();
        return ApiResponse.success("cache refreshed");
    }

    private ArticleListResponse paginateSearchResult(List<Article> source, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(pageSize, 1);
        int from = (safePage - 1) * safeSize;
        int to = Math.min(from + safeSize, source.size());
        List<Article> list = from >= source.size()
                ? Collections.emptyList()
                : source.subList(from, to);

        int totalPages = source.isEmpty() ? 0 : (int) Math.ceil((double) source.size() / safeSize);
        return ArticleListResponse.builder()
                .list(list)
                .pagination(Pagination.builder()
                        .page(safePage)
                        .pageSize(safeSize)
                        .total(source.size())
                        .totalPages(totalPages)
                        .build())
                .build();
    }
}
