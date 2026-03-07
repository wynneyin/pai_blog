package com.blog.dto;

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
public class ArticleFrontMatter {
    private String title;
    private String slug;
    private String category;
    private List<String> tags;
    private String author;
    private LocalDateTime date;
    private String cover;
    private String summary;
    private boolean published;
}
