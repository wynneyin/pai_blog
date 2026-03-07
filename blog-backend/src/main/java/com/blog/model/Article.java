package com.blog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Article {
    private String id;
    private String slug;
    private String title;
    private String content;
    private String summary;
    private String author;
    private String category;
    private List<String> tags;
    private String coverImage;
    private boolean published;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int views;
}
