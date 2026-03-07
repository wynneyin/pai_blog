package com.blog.service.impl;

import com.blog.dto.ArticleFrontMatter;
import com.blog.dto.ArticleListResponse;
import com.blog.dto.Pagination;
import com.blog.model.Article;
import com.blog.model.Category;
import com.blog.model.Tag;
import com.blog.service.ArticleService;
import com.blog.util.FileUtil;
import com.blog.util.MarkdownParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarkdownArticleService implements ArticleService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int SUMMARY_LENGTH = 180;

    private final MarkdownParserService markdownParserService;
    private final Map<String, Integer> viewCounter = new ConcurrentHashMap<>();

    @Value("${articles.path:${user.dir}/articles}")
    private String articlesPath;

    private volatile long cachedMarker = Long.MIN_VALUE;
    private volatile List<Article> cachedArticles = Collections.emptyList();
    private final Object cacheLock = new Object();

    @Override
    public ArticleListResponse getArticles(int page, int pageSize) {
        return paginate(getPublishedArticles(), page, pageSize);
    }

    @Override
    public Article getArticleById(String id) {
        return getPublishedArticles().stream()
                .filter(article -> article.getId().equalsIgnoreCase(id))
                .findFirst()
                .map(this::toDetailArticle)
                .orElseThrow(() -> new RuntimeException("Article not found: " + id));
    }

    @Override
    public Article getArticleBySlug(String slug) {
        Article article = getPublishedArticles().stream()
                .filter(item -> item.getSlug().equalsIgnoreCase(slug))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Article not found: " + slug));
        return toDetailArticle(article);
    }

    @Override
    public ArticleListResponse getArticlesByCategory(String category, int page, int pageSize) {
        List<Article> filtered = getPublishedArticles().stream()
                .filter(article -> article.getCategory().equalsIgnoreCase(category))
                .toList();
        return paginate(filtered, page, pageSize);
    }

    @Override
    public ArticleListResponse getArticlesByTag(String tag, int page, int pageSize) {
        String normalized = tag.toLowerCase(Locale.ROOT);
        List<Article> filtered = getPublishedArticles().stream()
                .filter(article -> article.getTags().stream()
                        .anyMatch(item -> item.toLowerCase(Locale.ROOT).equals(normalized)))
                .toList();
        return paginate(filtered, page, pageSize);
    }

    @Override
    public List<Article> searchArticles(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return getPublishedArticles().stream().map(this::toListArticle).toList();
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return getPublishedArticles().stream()
                .filter(article -> article.getTitle().toLowerCase(Locale.ROOT).contains(normalized)
                        || article.getSummary().toLowerCase(Locale.ROOT).contains(normalized)
                        || article.getContent().toLowerCase(Locale.ROOT).contains(normalized))
                .map(this::toListArticle)
                .toList();
    }

    @Override
    public List<Category> getCategories() {
        Map<String, Long> countMap = getPublishedArticles().stream()
                .collect(Collectors.groupingBy(Article::getCategory, Collectors.counting()));

        return countMap.entrySet().stream()
                .map(entry -> Category.builder()
                        .name(entry.getKey())
                        .count(entry.getValue().intValue())
                        .build())
                .sorted(Comparator.comparingInt(Category::getCount).reversed())
                .toList();
    }

    @Override
    public List<Tag> getTags() {
        Map<String, Integer> countMap = new LinkedHashMap<>();
        for (Article article : getPublishedArticles()) {
            for (String tag : article.getTags()) {
                countMap.merge(tag, 1, Integer::sum);
            }
        }

        return countMap.entrySet().stream()
                .map(entry -> Tag.builder().name(entry.getKey()).count(entry.getValue()).build())
                .sorted(Comparator.comparingInt(Tag::getCount).reversed())
                .toList();
    }

    @Override
    public void clearCache() {
        synchronized (cacheLock) {
            cachedMarker = Long.MIN_VALUE;
            cachedArticles = Collections.emptyList();
        }
    }

    private List<Article> getPublishedArticles() {
        List<Path> files = FileUtil.findFiles(articlesPath, ".md").stream()
                .sorted()
                .toList();
        long marker = calculateMarker(files);
        if (marker == cachedMarker) {
            return cachedArticles;
        }

        synchronized (cacheLock) {
            if (marker == cachedMarker) {
                return cachedArticles;
            }
            List<Article> loaded = loadArticles(files);
            cachedArticles = loaded;
            cachedMarker = marker;
            log.info("Reloaded articles, total={}", loaded.size());
            return loaded;
        }
    }

    private long calculateMarker(List<Path> files) {
        long marker = 17;
        for (Path file : files) {
            marker = 31 * marker + file.toString().hashCode();
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                marker = 31 * marker + attributes.lastModifiedTime().toMillis();
                marker = 31 * marker + attributes.size();
            } catch (IOException e) {
                log.warn("Failed to read file attributes: {}", file, e);
            }
        }
        return marker;
    }

    private List<Article> loadArticles(List<Path> files) {
        List<Article> loaded = new ArrayList<>();
        Map<String, Integer> duplicateSlugCount = new LinkedHashMap<>();

        for (Path file : files) {
            try {
                String markdown = FileUtil.readFile(file);
                ArticleFrontMatter matter = markdownParserService.parseFrontMatter(markdown);
                if (!matter.isPublished()) {
                    continue;
                }

                String markdownBody = markdownParserService.removeFrontMatter(markdown).trim();
                String htmlContent = markdownParserService.parseMarkdown(markdownBody);
                String id = FileUtil.generateId(file);
                String title = blankToDefault(matter.getTitle(), id);
                String slug = blankToDefault(matter.getSlug(), markdownParserService.generateSlug(title));
                String category = blankToDefault(matter.getCategory(), FileUtil.extractCategory(file, articlesPath));
                List<String> tags = Optional.ofNullable(matter.getTags()).orElseGet(ArrayList::new);

                String normalizedSlug = deduplicateSlug(slug, duplicateSlugCount);
                LocalDateTime fileTime = resolveFileTime(file);
                LocalDateTime createdAt = Objects.requireNonNullElse(matter.getDate(), fileTime);

                Article article = Article.builder()
                        .id(id)
                        .slug(normalizedSlug)
                        .title(title)
                        .content(htmlContent)
                        .summary(blankToDefault(matter.getSummary(), makeSummary(markdownBody)))
                        .author(blankToDefault(matter.getAuthor(), "博主"))
                        .category(category)
                        .tags(tags)
                        .coverImage(blankToDefault(matter.getCover(), ""))
                        .published(true)
                        .createdAt(createdAt)
                        .updatedAt(fileTime)
                        .views(viewCounter.getOrDefault(normalizedSlug, 0))
                        .build();

                loaded.add(article);
            } catch (Exception e) {
                log.error("Failed to load article file: {}", file, e);
            }
        }

        return loaded.stream()
                .sorted(Comparator.comparing(Article::getCreatedAt).reversed())
                .toList();
    }

    private ArticleListResponse paginate(List<Article> source, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int from = (safePage - 1) * safeSize;
        int to = Math.min(from + safeSize, source.size());

        List<Article> list = from >= source.size()
                ? Collections.emptyList()
                : source.subList(from, to).stream().map(this::toListArticle).toList();

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

    private Article toListArticle(Article source) {
        return Article.builder()
                .id(source.getId())
                .slug(source.getSlug())
                .title(source.getTitle())
                .summary(source.getSummary())
                .author(source.getAuthor())
                .category(source.getCategory())
                .tags(source.getTags())
                .coverImage(source.getCoverImage())
                .published(source.isPublished())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .views(source.getViews())
                .build();
    }

    private Article toDetailArticle(Article source) {
        int views = viewCounter.merge(source.getSlug(), 1, Integer::sum);
        return Article.builder()
                .id(source.getId())
                .slug(source.getSlug())
                .title(source.getTitle())
                .content(source.getContent())
                .summary(source.getSummary())
                .author(source.getAuthor())
                .category(source.getCategory())
                .tags(source.getTags())
                .coverImage(source.getCoverImage())
                .published(source.isPublished())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .views(views)
                .build();
    }

    private String deduplicateSlug(String slug, Map<String, Integer> duplicateSlugCount) {
        int count = duplicateSlugCount.getOrDefault(slug, 0);
        duplicateSlugCount.put(slug, count + 1);
        return count == 0 ? slug : slug + "-" + (count + 1);
    }

    private LocalDateTime resolveFileTime(Path file) {
        try {
            return Files.getLastModifiedTime(file)
                    .toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (IOException e) {
            log.warn("Failed to get last modified time for {}", file, e);
            return LocalDateTime.now();
        }
    }

    private String makeSummary(String markdownBody) {
        String plainText = markdownBody
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("`", "")
                .replaceAll("#+", "")
                .replaceAll(">+", "")
                .replaceAll("\\*+", "")
                .replaceAll("_+", "")
                .replaceAll("\\[(.*?)]\\((.*?)\\)", "$1")
                .replaceAll("\\s+", " ")
                .trim();

        if (plainText.length() <= SUMMARY_LENGTH) {
            return plainText;
        }
        return plainText.substring(0, SUMMARY_LENGTH) + "...";
    }

    private String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
