package com.blog.util;

import com.blog.dto.ArticleFrontMatter;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.html.HtmlRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Slf4j
@Component
public class MarkdownParserService {

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MarkdownParserService() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
                YamlFrontMatterExtension.create()
        ));

        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    public ArticleFrontMatter parseFrontMatter(String markdown) {
        ArticleFrontMatter matter = ArticleFrontMatter.builder().build();
        matter.setPublished(true);

        String[] lines = markdown.split("\n");
        boolean inFrontMatter = false;
        StringBuilder yamlContent = new StringBuilder();

        for (String line : lines) {
            if (line.trim().equals("---")) {
                if (!inFrontMatter) {
                    inFrontMatter = true;
                    continue;
                } else {
                    // End of front matter
                    parseYaml(yamlContent.toString(), matter);
                    break;
                }
            }
            if (inFrontMatter) {
                yamlContent.append(line).append("\n");
            }
        }

        // Set defaults if not specified
        if (matter.getAuthor() == null || matter.getAuthor().isEmpty()) {
            matter.setAuthor("博主");
        }
        if (matter.getTags() == null) {
            matter.setTags(new ArrayList<>());
        }

        return matter;
    }

    private void parseYaml(String yaml, ArticleFrontMatter matter) {
        String[] lines = yaml.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = cleanValue(line.substring(colonIndex + 1).trim());

                switch (key) {
                    case "title":
                        matter.setTitle(value);
                        break;
                    case "slug":
                        matter.setSlug(value);
                        break;
                    case "category":
                        matter.setCategory(value);
                        break;
                    case "author":
                        matter.setAuthor(value);
                        break;
                    case "cover":
                        matter.setCover(value);
                        break;
                    case "summary":
                        matter.setSummary(value);
                        break;
                    case "published":
                        matter.setPublished(Boolean.parseBoolean(value));
                        break;
                    case "date":
                        matter.setDate(parseDate(value));
                        break;
                    case "tags":
                        String tagString = value.replaceAll("[\\[\\]\"]", "");
                        if (!tagString.isEmpty()) {
                            String[] tags = tagString.split(",");
                            List<String> tagList = new ArrayList<>();
                            for (String tag : tags) {
                                tag = tag.trim();
                                if (!tag.isEmpty()) {
                                    tagList.add(tag);
                                }
                            }
                            matter.setTags(tagList);
                        }
                        break;
                }
            }
        }
    }

    private String cleanValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned;
    }

    private LocalDateTime parseDate(String value) {
        try {
            return LocalDateTime.parse(value, dateFormatter);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (DateTimeParseException ignored) {
        }
        return LocalDateTime.now();
    }

    public String parseMarkdown(String markdown) {
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }

    public String removeFrontMatter(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        if (!markdown.startsWith("---")) {
            return markdown;
        }

        StringBuilder result = new StringBuilder();
        boolean inFrontMatter = true;
        int separatorCount = 0;

        String[] lines = markdown.split("\n");
        for (String line : lines) {
            if (line.trim().equals("---")) {
                separatorCount++;
                if (separatorCount == 2) {
                    inFrontMatter = false;
                }
                continue;
            }
            if (!inFrontMatter) {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    public String generateSlug(String title) {
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("[\\s-]+", "-")
                .replaceAll("^-|-$", "");
    }
}
