package com.blog.service;

import com.blog.dto.ArticleListResponse;
import com.blog.model.Article;
import com.blog.model.Category;
import com.blog.model.Tag;

import java.util.List;

public interface ArticleService {
    ArticleListResponse getArticles(int page, int pageSize);
    Article getArticleById(String id);
    Article getArticleBySlug(String slug);
    ArticleListResponse getArticlesByCategory(String category, int page, int pageSize);
    ArticleListResponse getArticlesByTag(String tag, int page, int pageSize);
    List<Article> searchArticles(String keyword);
    List<Category> getCategories();
    List<Tag> getTags();
    void clearCache();
}
